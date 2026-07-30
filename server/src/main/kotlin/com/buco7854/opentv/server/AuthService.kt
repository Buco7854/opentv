package com.buco7854.opentv.server

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import com.buco7854.opentv.contract.*
import com.buco7854.opentv.serverdata.AuthMethod
import com.buco7854.opentv.serverdata.ChallengeKind
import com.buco7854.opentv.serverdata.ClientKind
import com.buco7854.opentv.serverdata.UserRole
import com.buco7854.opentv.serverdata.UserStatus
import com.buco7854.opentv.serverdata.db.AuthChallengeRow
import com.buco7854.opentv.serverdata.db.AuthSessionRow
import com.buco7854.opentv.serverdata.db.OidcIdentityRow
import com.buco7854.opentv.serverdata.db.PendingOidcIdentityRow
import com.buco7854.opentv.serverdata.db.OpenTvServerDatabase
import com.buco7854.opentv.serverdata.db.UserRow
import com.buco7854.opentv.serverdata.db.WebAuthnCredentialRow
import com.buco7854.opentv.serverdata.db.MfaCompletionWrite
import com.buco7854.opentv.serverdata.db.completeMfa
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

data class Actor(
    val userId: String,
    val authSessionId: String,
    val username: String,
    val displayName: String,
    val roles: Set<String>,
    val authMethod: String,
    val clientKind: String,
) {
    val isAdmin: Boolean get() = UserRole.ADMIN in roles
}

/** What an actor may see, resolved once. A playlist being deleted is invisible to everyone. */
class PlaylistAccess internal constructor(
    private val admin: Boolean,
    private val granted: Set<Long>,
    private val deleting: Set<Long>,
) {
    fun allows(playlistId: Long): Boolean =
        playlistId !in deleting && (admin || playlistId in granted)
}

internal data class IssuedSession(
    val token: String,
    val row: AuthSessionRow,
    val user: UserRow,
)

internal data class AuthResult(
    val flow: AuthFlowDto,
    val oidcHandoff: String? = null,
) {
    val sessionToken: String? get() = flow.sessionToken
}

class AuthService(
    private val db: OpenTvServerDatabase,
    private val config: AuthConfig,
    private val dataDir: Path,
    private val clock: () -> Long = System::currentTimeMillis,
    private val playlistExists: suspend (Long) -> Boolean = { true },
    private val cleanup: UserStateCleanupCoordinator = NoopUserStateCleanupCoordinator,
    private val resumeTitles: suspend (Collection<String>) -> Map<String, String> = { emptyMap() },
) {
    private val limiter = AuthRateLimiter(clock)
    private val mutation = Mutex()
    private val bootstrapFile = dataDir.resolve("bootstrap.token")
    private val log = LoggerFactory.getLogger("opentv.auth")
    private val sessionService = PersistentSessionService(db, config, cleanup, clock)
    private val accounts = AuthAccountService(db, clock, playlistExists)
    private val credentials = AuthCredentialService(db, config, clock)
    internal val requestAuthenticator = RequestAuthenticator(sessionService)
    internal val flows = AuthFlowService(
        db,
        config,
        bootstrapFile,
        clock,
        mutation,
        limiter,
        accounts,
        credentials,
        sessionService,
    )
    private val userAdministration = UserAdministrationService(
        db,
        accounts,
        sessionService,
        credentials,
        config,
        resumeTitles,
        mutation,
        cleanup,
        clock,
    )
    suspend fun initialize(): Set<String> = mutation.withLock {
        val now = clock()
        val revokedUserIds = db.useWriterConnection { connection ->
            connection.immediateTransaction {
                db.sessions().prune(now - 24 * 60 * 60_000L)
                val affected = linkedSetOf<String>()
                for (user in db.users().all()) {
                    val revokesSession = db.sessions().activeForUser(user.id).any { session ->
                        session.authMethod == AuthMethod.PASSWORD &&
                            (!config.passwordEnabled ||
                                (session.mfaSatisfiedAtMs == null &&
                                    accounts.effectiveRole(user) in config.mfaRequiredRoles))
                    }
                    if (revokesSession) affected += user.id
                }
                if (!config.passwordEnabled) db.sessions().revokePasswordSessions(now)
                db.sessions().revokePasswordSessionsMissingMfa(
                    now,
                    UserRole.USER in config.mfaRequiredRoles,
                    UserRole.ADMIN in config.mfaRequiredRoles,
                )
                affected
            }
        }
        db.challenges().prune(now - 24 * 60 * 60_000L)
        val usableAdminExists = if (config.passwordEnabled) {
            db.users().activeAdminCount() > 0
        } else {
            db.oidc().hasUsableAdminIdentity()
        }
        if (usableAdminExists) {
            Files.deleteIfExists(bootstrapFile)
            return@withLock revokedUserIds
        }
        val seed = config.initialAdmin
        if (seed != null && config.passwordEnabled) {
            createSeedAdmin(seed, now)
            Files.deleteIfExists(bootstrapFile)
        } else if (config.passwordEnabled) {
            ensureBootstrapFile()
        } else {
            require(config.oidc?.adminGroups?.isNotEmpty() == true) {
                "OIDC-only startup without an administrator requires OPENTV_OIDC_ADMIN_GROUPS"
            }
        }
        revokedUserIds
    }

    /**
     * Capabilities as the configuration alone describes them. The route answers with the
     * relying party of the address being asked instead, so passkeys are only advertised
     * where a browser would run the ceremony; see [PublicOrigin].
     */
    internal suspend fun capabilities(
        relyingParty: WebAuthnRelyingParty = config.pinnedRelyingParty(),
    ): AuthCapabilitiesDto = flows.capabilities(relyingParty)

    internal suspend fun issueOidcState(payload: String, clientIp: String): Pair<String, Long> =
        mutation.withLock {
            val now = clock()
            limiter.consume("oidc-start:global", limit = 200, windowMs = 60_000)
            limiter.consume("oidc-start:ip:$clientIp", limit = 10, windowMs = 60_000)
            db.challenges().prune(now)
            if (db.challenges().activeCount(ChallengeKind.OIDC, now) >= MAX_ACTIVE_OIDC_STATES) {
                throw AuthRateLimitedException(now + 60_000)
            }
            issueChallenge(null, ChallengeKind.OIDC, payload, 5 * 60_000L)
        }

    internal suspend fun issueWebAuthnLoginChallenge(
        payload: String,
        clientIp: String,
    ): Pair<String, Long> = mutation.withLock {
        val now = clock()
        limiter.consume("webauthn-login-start:global", limit = 200, windowMs = 60_000)
        limiter.consume("webauthn-login-start:ip:$clientIp", limit = 10, windowMs = 60_000)
        db.challenges().prune(now)
        if (db.challenges().activeCount(ChallengeKind.WEBAUTHN_LOGIN, now) >=
            MAX_ACTIVE_WEBAUTHN_LOGINS
        ) {
            throw AuthRateLimitedException(now + 60_000)
        }
        issueChallenge(null, ChallengeKind.WEBAUTHN_LOGIN, payload, 5 * 60_000L)
    }

    internal suspend fun consumeOidcState(state: String): String {
        return mutation.withLock {
            val row = accounts.challenge(ChallengeKind.OIDC, state)
            if (db.challenges().consume(row.id, clock()) != 1) throw InvalidChallengeException()
            row.payloadJson
        }
    }

    internal suspend fun mfaChallenge(raw: String): AuthChallengeRow =
        accounts.challenge(ChallengeKind.MFA, raw)

    internal suspend fun reauthenticationChallenge(actor: Actor): String {
        val session = recentlyAuthenticatedSession(actor)
        return issueChallenge(
            actor.userId,
            ChallengeKind.MFA,
            "reauth:${session.id}",
            5 * 60_000L,
        ).first
    }

    internal suspend fun issueWebAuthnChallenge(
        userId: String,
        kind: String,
        payload: String,
    ): Pair<String, Long> = issueChallenge(userId, kind, payload, 5 * 60_000L)

    internal suspend fun webAuthnChallenge(kind: String, raw: String): AuthChallengeRow =
        accounts.challenge(kind, raw)

    internal fun checkFlowLimit(clientIp: String, flow: String, challenge: String) =
        limiter.check("ip:$clientIp", "$flow:${challenge.take(CHALLENGE_KEY_LENGTH)}")

    internal fun failFlowLimit(clientIp: String, flow: String, challenge: String) =
        limiter.fail("ip:$clientIp", "$flow:${challenge.take(CHALLENGE_KEY_LENGTH)}")

    internal fun clearFlowLimit(clientIp: String, flow: String, challenge: String) =
        limiter.success("ip:$clientIp", "$flow:${challenge.take(CHALLENGE_KEY_LENGTH)}")

    internal suspend fun finishWebAuthn(
        webAuthnChallengeId: String,
        parentChallengeId: String,
        credential: WebAuthnCredentialRow,
        enrollment: Boolean,
        clientKind: String = ClientKind.BROWSER,
    ): AuthResult = mutation.withLock {
        val now = clock()
        val parent = db.challenges().get(parentChallengeId)
            ?.takeIf { it.kind == ChallengeKind.MFA && it.consumedAtMs == null && it.expiresAtMs > now }
            ?: throw InvalidChallengeException()
        require(parent.userId == credential.userId) { "Credential owner mismatch" }
        val committedCredential = webAuthnCredentialForCommit(credential, enrollment)
        val user = activeUser(credential.userId)
        val replacedSession = parent.payloadJson.removePrefix("reauth:")
            .takeIf { parent.payloadJson.startsWith("reauth:") }
            ?.let { db.sessions().get(it) }
            ?.takeIf { it.userId == credential.userId }
        // Recovery codes answer the password flow's MFA challenge. A passkey-only account
        // signs in directly and has no MFA step for a recovery code to recover.
        val recovery = if (enrollment && db.credentials().password(credential.userId) != null) {
            credentials.newRecoveryCodes(credential.userId)
        } else {
            null
        }
        val session = prepareSession(
            user,
            replacedSession?.authMethod ?: AuthMethod.PASSWORD,
            mfa = true,
            clientKind = replacedSession?.clientKind ?: clientKind,
        )
        if (!db.completeMfa(
                challengeId = webAuthnChallengeId,
                parentChallengeId = parent.id,
                write = MfaCompletionWrite(
                    session = session.row,
                    loginAtMs = now,
                    webAuthnCredential = committedCredential,
                    replacementRecoveryCodes = recovery?.second,
                ),
            )
        ) {
            throw InvalidChallengeException()
        }
        replacedSession?.let { revokeSessionInternal(credential.userId, it.id, now) }
        AuthResult(sessionFlow(session, recovery?.first.orEmpty()))
    }

    internal suspend fun finishWebAuthnLogin(
        webAuthnChallengeId: String,
        credential: WebAuthnCredentialRow,
        clientKind: String = ClientKind.BROWSER,
    ): AuthResult = mutation.withLock {
        val now = clock()
        db.challenges().get(webAuthnChallengeId)
            ?.takeIf {
                it.kind == ChallengeKind.WEBAUTHN_LOGIN &&
                    it.userId == null &&
                    it.consumedAtMs == null &&
                    it.expiresAtMs > now
            } ?: throw InvalidChallengeException()
        val committedCredential = webAuthnCredentialForCommit(credential, enrollment = false)
        val user = activeUser(committedCredential.userId)
        val session = prepareSession(
            user,
            AuthMethod.WEBAUTHN,
            mfa = true,
            clientKind = clientKind,
        )
        if (!db.completeMfa(
                challengeId = webAuthnChallengeId,
                write = MfaCompletionWrite(
                    session = session.row,
                    loginAtMs = now,
                    webAuthnCredential = committedCredential,
                ),
            )
        ) {
            throw InvalidChallengeException()
        }
        AuthResult(sessionFlow(session))
    }

    /**
     * Rechecks ownership and the authenticator counter under the same process-wide mutation
     * lock that serializes the challenge/session commit. Assertion verification is deliberately
     * outside that lock because it can be expensive; without this second read, two primary
     * assertions can both verify against one old row and a late lower counter can overwrite a
     * counter that already committed.
     */
    private suspend fun webAuthnCredentialForCommit(
        verified: WebAuthnCredentialRow,
        enrollment: Boolean,
    ): WebAuthnCredentialRow {
        val current = db.credentials().webAuthnById(verified.credentialId)
        if (enrollment) {
            if (current != null) throw InvalidCredentialsException()
            return verified
        }
        current ?: throw InvalidCredentialsException()
        if (current.userId != verified.userId ||
            (current.signCount > 0 && verified.signCount <= current.signCount)
        ) {
            throw InvalidCredentialsException()
        }
        return current.copy(
            signCount = verified.signCount,
            backupEligible = verified.backupEligible,
            backedUp = verified.backedUp,
            lastUsedAtMs = verified.lastUsedAtMs,
        )
    }

    internal suspend fun completeOidc(
        issuer: String,
        subject: String,
        usernameClaim: String?,
        displayNameClaim: String?,
        groups: List<String>,
        adminMapped: Boolean,
        clientKind: String = ClientKind.BROWSER,
    ): AuthResult = mutation.withLock {
        val now = clock()
        val existingIdentity = db.oidc().get(issuer, subject)
        val existingUser = existingIdentity?.let { db.users().get(it.userId) }
        if (existingUser == null) {
            val oidc = requireNotNull(config.oidc)
            if (!oidc.autoProvision && !adminMapped) {
                val prior = db.oidc().pending(issuer, subject)
                db.oidc().upsertPending(
                    PendingOidcIdentityRow(
                        issuer, subject, usernameClaim, displayNameClaim,
                        Json.encodeToString(groups), false, prior?.createdAtMs ?: now, now,
                    ),
                )
                return@withLock AuthResult(AuthFlowDto(status = "PENDING_APPROVAL"))
            }
        }
        val (updatedUser, roleChanged) = db.useWriterConnection { connection ->
            connection.immediateTransaction {
                val user = existingUser ?: run {
                    val base = usernameClaim?.takeIf(String::isNotBlank) ?: "oidc-user"
                    val username = availableOidcUsername(base, issuer, subject)
                    createUser(
                        username,
                        displayNameClaim?.takeIf(String::isNotBlank) ?: username,
                        UserStatus.ACTIVE,
                        UserRole.USER,
                        now,
                    ).also { copyDefaultGrants(it.id, now) }
                }
                db.oidc().upsert(
                    OidcIdentityRow(
                        issuer, subject, user.id, usernameClaim, displayNameClaim,
                        Json.encodeToString(groups), adminMapped, now,
                    ),
                )
                val oidcAdmin = db.oidc().hasAdminMapping(user.id)
                val changed = user.oidcAdmin != oidcAdmin
                val updated = user.copy(
                    displayName = displayNameClaim?.takeIf(String::isNotBlank) ?: user.displayName,
                    oidcAdmin = oidcAdmin,
                    updatedAtMs = now,
                )
                db.users().update(updated)
                db.oidc().deletePending(issuer, subject)
                updated to changed
            }
        }
        if (roleChanged) revokeUserSessions(updatedUser.id, now)
        if (updatedUser.status != UserStatus.ACTIVE) throw InvalidCredentialsException()
        val session = issueSession(
            updatedUser,
            AuthMethod.OIDC,
            mfa = true,
            clientKind = clientKind,
        )
        db.users().markLogin(updatedUser.id, now)
        AuthResult(sessionFlow(session))
    }

    suspend fun pendingOidc(actor: Actor): List<PendingOidcDto> =
        userAdministration.pendingOidc(actor)

    internal suspend fun approveOidc(
        actor: Actor,
        request: ApproveOidcRequestDto,
    ): AdminUserDto = userAdministration.approveOidc(actor, request)

    internal suspend fun activate(
        request: ActivationRequestDto,
        clientIp: String,
        clientKind: String = ClientKind.BROWSER,
    ): AuthResult = flows.activate(request, clientIp, clientKind)

    internal suspend fun bootstrap(
        request: BootstrapRequestDto,
        clientIp: String,
        clientKind: String = ClientKind.BROWSER,
    ): AuthResult = flows.bootstrap(request, clientIp, clientKind)

    internal suspend fun passwordLogin(
        request: PasswordLoginRequestDto,
        clientIp: String,
        clientKind: String = ClientKind.BROWSER,
    ): AuthResult = flows.password(request, clientIp, clientKind)

    suspend fun startTotpEnrollment(
        rawChallenge: String,
        clientIp: String,
    ): TotpEnrollmentDto = flows.startTotpEnrollment(rawChallenge, clientIp)

    internal suspend fun completeTotpEnrollment(
        request: TotpCompleteRequestDto,
        clientIp: String,
        clientKind: String = ClientKind.BROWSER,
    ): AuthResult = flows.completeTotpEnrollment(request, clientIp, clientKind)

    internal suspend fun totpStatus(actor: Actor): TotpStatusDto {
        val confirmed = db.credentials().confirmedTotp(actor.userId).firstOrNull()
        return TotpStatusDto(
            enrolled = confirmed != null,
            confirmedAtMs = confirmed?.createdAtMs,
        )
    }

    internal suspend fun startAdditionalTotpEnrollment(actor: Actor): TotpEnrollmentDto =
        mutation.withLock {
            val replacedSession = recentlyAuthenticatedSession(actor)
            credentials.requirePassword(actor.userId, "enrolling an authenticator")
            if (db.credentials().confirmedTotp(actor.userId).isNotEmpty()) {
                throw TotpExistsException()
            }
            val user = activeUser(actor.userId)
            val pending = credentials.newPendingTotp(user.id)
            val parent = issueChallenge(
                user.id,
                ChallengeKind.MFA,
                "reauth:${replacedSession.id}",
                5 * 60_000L,
            )
            val parentRow = accounts.challenge(ChallengeKind.MFA, parent.first)
            val enrollment = issueChallenge(
                user.id,
                ChallengeKind.TOTP_ENROLL,
                "${pending.credential.id}|${parentRow.id}",
                5 * 60_000L,
            )
            TotpEnrollmentDto(
                challenge = enrollment.first,
                secret = AuthCrypto.base32(pending.secret),
                uri = AuthCrypto.totpUri(pending.secret, user.username),
                expiresAtMs = enrollment.second,
            )
        }

    internal suspend fun completeAdditionalTotpEnrollment(
        actor: Actor,
        request: TotpCompleteRequestDto,
        clientIp: String,
    ): AuthResult {
        require(request.challenge.length <= 512) { "TOTP challenge is too large" }
        credentials.requirePassword(actor.userId, "enrolling an authenticator")
        checkFlowLimit(clientIp, "totp-add", request.challenge)
        return try {
            mutation.withLock {
                val challenge = accounts.challenge(ChallengeKind.TOTP_ENROLL, request.challenge)
                val credentialId = challenge.payloadJson.substringBefore('|')
                val parentId = challenge.payloadJson.substringAfter('|', "")
                val credential = db.credentials().totp(credentialId)
                    ?.takeIf {
                        it.userId == actor.userId &&
                            !it.confirmed
                    } ?: throw InvalidChallengeException()
                val now = clock()
                val parent = db.challenges().get(parentId)
                    ?.takeIf {
                        it.kind == ChallengeKind.MFA &&
                            it.userId == actor.userId &&
                            it.payloadJson == "reauth:${actor.authSessionId}" &&
                            it.consumedAtMs == null &&
                            it.expiresAtMs > now
                    } ?: throw InvalidChallengeException()
                val replacedSession = db.sessions().get(actor.authSessionId)
                    ?.takeIf {
                        it.userId == actor.userId &&
                            it.revokedAtMs == null
                    } ?: throw InvalidChallengeException()
                val user = activeUser(actor.userId)
                val step = credentials.verifyTotp(credential, request.code)
                    ?: throw InvalidCredentialsException()
                val session = prepareSession(
                    user,
                    replacedSession.authMethod,
                    mfa = true,
                    clientKind = replacedSession.clientKind,
                )
                if (!db.completeMfa(
                        challengeId = challenge.id,
                        parentChallengeId = parent.id,
                        write = MfaCompletionWrite(
                            session = session.row,
                            loginAtMs = now,
                            totpCredential = credential.copy(
                                confirmed = true,
                                lastAcceptedStep = step,
                            ),
                        ),
                    )
                ) {
                    throw InvalidChallengeException()
                }
                revokeSessionInternal(actor.userId, replacedSession.id, now)
                AuthResult(sessionFlow(session))
            }.also {
                clearFlowLimit(clientIp, "totp-add", request.challenge)
            }
        } catch (error: Exception) {
            failFlowLimit(clientIp, "totp-add", request.challenge)
            throw error
        }
    }

    internal suspend fun completeTotp(
        request: TotpCompleteRequestDto,
        clientIp: String,
        clientKind: String = ClientKind.BROWSER,
    ): AuthResult = flows.completeTotp(request, clientIp, clientKind)

    internal suspend fun completeRecovery(
        request: RecoveryCompleteRequestDto,
        clientIp: String,
        clientKind: String = ClientKind.BROWSER,
    ): AuthResult = flows.completeRecovery(request, clientIp, clientKind)

    suspend fun authenticate(rawToken: String?): Pair<Actor, AuthSessionRow>? =
        sessionService.authenticate(rawToken)

    internal suspend fun authenticateSession(sessionId: String): Actor? =
        sessionService.authenticateSession(sessionId)?.first

    suspend fun current(actor: Actor): CurrentUserDto {
        val user = db.users().get(actor.userId) ?: throw UnauthenticatedApiException()
        return accounts.currentUserDto(
            user,
            actor.authMethod,
            actor.clientKind,
            actor.authSessionId,
        )
    }

    suspend fun logout(actor: Actor, all: Boolean) {
        val now = clock()
        if (all) revokeUserSessions(actor.userId, now)
        else revokeSessionInternal(actor.userId, actor.authSessionId, now)
    }

    suspend fun adminUsers(actor: Actor): List<AdminUserDto> =
        userAdministration.users(actor)

    suspend fun adminResume(actor: Actor, userId: String): List<AdminResumeDto> =
        userAdministration.resume(actor, userId)

    suspend fun adminDeleteResume(actor: Actor, userId: String, contentId: String) =
        userAdministration.deleteResume(actor, userId, contentId)

    internal suspend fun adminCreateUser(
        actor: Actor,
        request: CreateUserRequestDto,
    ): CreatedUserDto = userAdministration.create(actor, request)

    internal suspend fun adminUpdateUser(
        actor: Actor,
        userId: String,
        request: UpdateUserRequestDto,
    ): AdminUserDto = userAdministration.update(actor, userId, request)

    suspend fun adminDeleteUser(actor: Actor, userId: String) =
        userAdministration.delete(actor, userId)

    internal suspend fun adminResetUser(
        actor: Actor,
        userId: String,
    ): ResetUserDto = userAdministration.reset(actor, userId)

    suspend fun revokeSession(actor: Actor, userId: String, sessionId: String?) =
        userAdministration.revokeSession(actor, userId, sessionId)

    suspend fun adminSessions(actor: Actor, userId: String): List<AuthSessionDto> =
        userAdministration.sessions(actor, userId)

    suspend fun defaultPlaylists(actor: Actor): List<Long> =
        userAdministration.defaultPlaylists(actor)

    suspend fun setDefaultPlaylists(actor: Actor, ids: List<Long>) =
        userAdministration.setDefaultPlaylists(actor, ids)

    suspend fun setUserPlaylists(actor: Actor, userId: String, ids: List<Long>) =
        userAdministration.setUserPlaylists(actor, userId, ids)

    suspend fun grants(userId: String): List<Long> = db.grants().forUser(userId)

    internal suspend fun regenerateRecoveryCodes(actor: Actor): AuthResult = mutation.withLock {
        recentlyAuthenticatedSession(actor)
        credentials.requirePassword(actor.userId, "generating recovery codes")
        val codes = credentials.replaceRecoveryCodes(actor.userId)
        val user = db.users().get(actor.userId) ?: throw UnauthenticatedApiException()
        revokeSessionInternal(actor.userId, actor.authSessionId, clock())
        val session = issueSession(
            user,
            actor.authMethod,
            mfa = true,
            clientKind = actor.clientKind,
        )
        AuthResult(sessionFlow(session, codes))
    }

    internal suspend fun changePassword(
        actor: Actor,
        request: PasswordChangeRequestDto,
    ): AuthResult = mutation.withLock {
        require(config.passwordEnabled) { "Password authentication is disabled" }
        recentlyAuthenticatedSession(actor)
        db.credentials().password(actor.userId) ?: throw ForbiddenApiException()
        credentials.setPassword(actor.userId, request.password, clock())
        revokeSessionInternal(actor.userId, actor.authSessionId, clock())
        val user = db.users().get(actor.userId) ?: throw UnauthenticatedApiException()
        val session = issueSession(
            user,
            AuthMethod.PASSWORD,
            mfa = true,
            clientKind = actor.clientKind,
        )
        AuthResult(sessionFlow(session))
    }

    internal suspend fun webAuthnCredentials(actor: Actor): List<WebAuthnCredentialRow> =
        db.credentials().webAuthn(actor.userId)

    internal suspend fun deleteWebAuthnCredential(
        actor: Actor,
        credentialId: ByteArray,
    ): AuthResult =
        mutation.withLock {
            val replacedSession = recentlyAuthenticatedSession(actor)
            val credentialsForUser = db.credentials().webAuthn(actor.userId)
            val target = credentialsForUser.firstOrNull {
                MessageDigest.isEqual(it.credentialId, credentialId)
            }
            val user = db.users().get(actor.userId) ?: throw UnauthenticatedApiException()
            if (target != null) {
                val remaining = credentialsForUser.filterNot {
                    MessageDigest.isEqual(it.credentialId, target.credentialId)
                }
                ensureFactorsRemain(
                    user,
                    webAuthnRemaining = remaining.isNotEmpty(),
                    totpRemaining = db.credentials().confirmedTotp(actor.userId).isNotEmpty(),
                )
                if (db.credentials().deleteWebAuthn(actor.userId, target.credentialId) != 1) {
                    throw InvalidChallengeException()
                }
            }
            rotateAfterFactorRemoval(user, replacedSession)
        }

    internal suspend fun deleteTotp(actor: Actor): AuthResult = mutation.withLock {
        val replacedSession = recentlyAuthenticatedSession(actor)
        val user = db.users().get(actor.userId) ?: throw UnauthenticatedApiException()
        if (db.credentials().confirmedTotp(actor.userId).isNotEmpty()) {
            ensureFactorsRemain(
                user,
                webAuthnRemaining = db.credentials().webAuthn(actor.userId).isNotEmpty(),
                totpRemaining = false,
            )
        }
        val now = clock()
        db.credentials().deleteTotp(actor.userId)
        db.challenges().consumeForUser(ChallengeKind.TOTP_ENROLL, actor.userId, now)
        rotateAfterFactorRemoval(user, replacedSession, now)
    }

    private suspend fun ensureFactorsRemain(
        user: UserRow,
        webAuthnRemaining: Boolean,
        totpRemaining: Boolean,
    ) {
        val canSignIn = (config.passwordEnabled &&
            db.credentials().password(user.id) != null) ||
            (config.oidc != null && db.oidc().forUser(user.id).isNotEmpty()) ||
            webAuthnRemaining
        if (!canSignIn) throw LastFactorException()
        if (accounts.effectiveRole(user) in config.mfaRequiredRoles) {
            val canSatisfyMfa = totpRemaining ||
                webAuthnRemaining ||
                db.credentials().unusedRecoveryCodes(user.id).isNotEmpty()
            if (!canSatisfyMfa) throw LastFactorException()
        }
    }

    private suspend fun rotateAfterFactorRemoval(
        user: UserRow,
        replacedSession: AuthSessionRow,
        now: Long = clock(),
    ): AuthResult {
        revokeUserSessions(user.id, now)
        val session = issueSession(
            user,
            replacedSession.authMethod,
            mfa = true,
            clientKind = replacedSession.clientKind,
        )
        return AuthResult(sessionFlow(session))
    }

    internal suspend fun requireMfaSatisfied(actor: Actor) {
        sessionService.requireMfaSatisfied(actor)
    }

    internal suspend fun claimDeviceLink(
        challengeId: String,
        userId: String,
        approvedPayloadJson: String,
        method: String,
        deviceName: String?,
    ): AuthResult = mutation.withLock {
        val now = clock()
        val user = activeUser(userId)
        val session = sessionService.prepare(
            user,
            method,
            mfa = true,
            deviceName = deviceName,
            clientKind = ClientKind.LINKED_DEVICE,
        )
        val committed = db.useWriterConnection { connection ->
            connection.immediateTransaction {
                val currentUser = db.users().get(userId)
                    ?.takeIf { it.status == UserStatus.ACTIVE }
                    ?: return@immediateTransaction false
                val challenge = db.challenges().get(challengeId)
                    ?.takeIf {
                        it.kind == ChallengeKind.DEVICE_LINK &&
                            it.userId == currentUser.id &&
                            it.payloadJson == approvedPayloadJson &&
                            it.consumedAtMs == null &&
                            it.expiresAtMs > now
                    } ?: return@immediateTransaction false
                if (db.challenges().consume(challenge.id, now) != 1) {
                    return@immediateTransaction false
                }
                db.sessions().insert(session.row)
                db.users().markLogin(currentUser.id, now)
                true
            }
        }
        if (!committed) {
            throw InvalidChallengeException()
        }
        AuthResult(sessionFlow(session))
    }
    /**
     * One snapshot of the playlists [actor] may see. List endpoints take it once instead of
     * re-reading entitlements for every row; a single check costs the same as it always did.
     */
    suspend fun playlistAccess(actor: Actor): PlaylistAccess {
        // Actor is a request-start snapshot. Intersect it with persisted authority so a
        // demotion already committed while this request was waiting cannot retain admin access.
        val user = db.users().get(actor.userId)
            ?.takeIf { it.status == UserStatus.ACTIVE }
            ?: throw UnauthenticatedApiException()
        val admin = actor.isAdmin && accounts.effectiveRole(user) == UserRole.ADMIN
        return PlaylistAccess(
            admin = admin,
            granted = if (admin) emptySet() else db.grants().forUser(actor.userId).toSet(),
            deleting = db.maintenance().pendingPlaylistDeletions()
                .mapTo(mutableSetOf()) { it.playlistId },
        )
    }

    suspend fun hasPlaylistAccess(actor: Actor, playlistId: Long): Boolean =
        playlistAccess(actor).allows(playlistId)

    internal suspend fun hasCurrentAdminAuthority(actor: Actor): Boolean {
        if (!actor.isAdmin) return false
        val user = db.users().get(actor.userId)
            ?.takeIf { it.status == UserStatus.ACTIVE }
            ?: throw UnauthenticatedApiException()
        return accounts.effectiveRole(user) == UserRole.ADMIN
    }

    internal suspend fun requireActiveActor(actor: Actor) = sessionService.requireActive(actor)

    private suspend fun issueSession(
        user: UserRow,
        method: String,
        mfa: Boolean,
        clientKind: String = ClientKind.BROWSER,
    ): IssuedSession = sessionService.issue(user, method, mfa, clientKind)

    private suspend fun prepareSession(
        user: UserRow,
        method: String,
        mfa: Boolean,
        clientKind: String = ClientKind.BROWSER,
    ): IssuedSession = sessionService.prepare(user, method, mfa, clientKind = clientKind)

    private suspend fun sessionFlow(
        session: IssuedSession,
        recoveryCodes: List<String> = emptyList(),
    ) = AuthFlowDto(
        status = "AUTHENTICATED",
        user = accounts.currentUserDto(
            session.user,
            session.row.authMethod,
            session.row.clientKind,
            session.row.id,
        ),
        sessionToken = session.token,
        recoveryCodes = recoveryCodes,
    )

    private suspend fun createSeedAdmin(seed: InitialAdminConfig, now: Long) {
        val user = createUser(seed.username, seed.username, UserStatus.ACTIVE, UserRole.ADMIN, now)
        credentials.setPassword(user.id, seed.password, now)
        copyDefaultGrants(user.id, now)
    }

    internal suspend fun createUser(
        username: String,
        displayName: String,
        status: String,
        role: String,
        now: Long,
    ): UserRow = accounts.createUser(username, displayName, status, role, now)

    internal suspend fun availableOidcUsername(base: String, issuer: String, subject: String): String =
        accounts.availableOidcUsername(base, issuer, subject)

    internal suspend fun issueChallenge(
        userId: String?,
        kind: String,
        payload: String,
        ttlMs: Long,
    ): Pair<String, Long> = accounts.issueChallenge(userId, kind, payload, ttlMs)

    internal suspend fun copyDefaultGrants(userId: String, now: Long) =
        accounts.copyDefaultGrants(userId, now)

    private suspend fun activeUser(userId: String): UserRow =
        db.users().get(userId)?.takeIf { it.status == UserStatus.ACTIVE }
            ?: throw InvalidChallengeException()

    internal suspend fun revokeUserSessions(userId: String, atMs: Long) {
        sessionService.revokeUser(userId, atMs)
    }

    internal suspend fun revokeSessionInternal(userId: String, sessionId: String, atMs: Long) {
        sessionService.revoke(userId, sessionId, atMs)
    }

    private suspend fun recentlyAuthenticatedSession(actor: Actor): AuthSessionRow =
        sessionService.recentlyAuthenticated(actor)

    private fun ensureBootstrapFile() {
        if (Files.exists(bootstrapFile)) return
        Files.createDirectories(bootstrapFile.parent)
        val ownerOnly = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        val posix = bootstrapFile.fileSystem.supportedFileAttributeViews().contains("posix")
        val temporary = if (posix) {
            Files.createTempFile(
                bootstrapFile.parent,
                ".bootstrap-",
                ".tmp",
                PosixFilePermissions.asFileAttribute(ownerOnly),
            )
        } else {
            Files.createTempFile(bootstrapFile.parent, ".bootstrap-", ".tmp")
        }
        try {
            Files.writeString(temporary, AuthCrypto.token() + "\n")
            if (posix) Files.setPosixFilePermissions(temporary, ownerOnly)
            try {
                Files.move(temporary, bootstrapFile, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, bootstrapFile)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
        log.warn("Initial administrator bootstrap token created at {}", bootstrapFile)
    }

    private companion object {
        const val MAX_ACTIVE_OIDC_STATES = 4_096
        const val MAX_ACTIVE_WEBAUTHN_LOGINS = 4_096
        const val CHALLENGE_KEY_LENGTH = 16
    }
}
