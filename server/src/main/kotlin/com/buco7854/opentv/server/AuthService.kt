package com.buco7854.opentv.server

import com.buco7854.opentv.serverdata.AuthMethod
import com.buco7854.opentv.serverdata.ChallengeKind
import com.buco7854.opentv.serverdata.UserRole
import com.buco7854.opentv.serverdata.UserStatus
import com.buco7854.opentv.serverdata.db.AuthChallengeRow
import com.buco7854.opentv.serverdata.db.AuthSessionRow
import com.buco7854.opentv.serverdata.db.OidcIdentityRow
import com.buco7854.opentv.serverdata.db.PendingOidcIdentityRow
import com.buco7854.opentv.serverdata.db.ServerUserDatabase
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
    val sessionToken: String? = null,
)

class AuthService(
    private val db: ServerUserDatabase,
    private val config: AuthConfig,
    private val dataDir: Path,
    private val clock: () -> Long = System::currentTimeMillis,
    private val playlistExists: suspend (Long) -> Boolean = { true },
    private val cleanup: UserStateCleanupCoordinator = NoopUserStateCleanupCoordinator,
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
        mutation,
        cleanup,
        clock,
    )
    suspend fun initialize() = mutation.withLock {
        val now = clock()
        db.sessions().prune(now - 24 * 60 * 60_000L)
        db.challenges().prune(now - 24 * 60 * 60_000L)
        if (!config.passwordEnabled) db.sessions().revokePasswordSessions(now)
        db.sessions().revokePasswordSessionsMissingMfa(
            now,
            UserRole.USER in config.mfaRequiredRoles,
            UserRole.ADMIN in config.mfaRequiredRoles,
        )
        val usableAdminExists = if (config.passwordEnabled) {
            db.users().activeAdminCount() > 0
        } else {
            db.oidc().hasUsableAdminIdentity()
        }
        if (usableAdminExists) {
            Files.deleteIfExists(bootstrapFile)
            return@withLock
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
    }

    suspend fun capabilities(): AuthCapabilitiesDto = flows.capabilities()

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
    ): AuthResult = mutation.withLock {
        val now = clock()
        val parent = db.challenges().get(parentChallengeId)
            ?.takeIf { it.kind == ChallengeKind.MFA && it.consumedAtMs == null && it.expiresAtMs > now }
            ?: throw InvalidChallengeException()
        require(parent.userId == credential.userId) { "Credential owner mismatch" }
        val user = activeUser(credential.userId)
        val replacedSession = parent.payloadJson.removePrefix("reauth:")
            .takeIf { parent.payloadJson.startsWith("reauth:") }
            ?.let { db.sessions().get(it) }
            ?.takeIf { it.userId == credential.userId }
        val recovery = if (enrollment) credentials.newRecoveryCodes(credential.userId) else null
        val session = prepareSession(
            user,
            replacedSession?.authMethod ?: AuthMethod.PASSWORD,
            mfa = true,
        )
        if (!db.completeMfa(
                challengeId = webAuthnChallengeId,
                parentChallengeId = parent.id,
                write = MfaCompletionWrite(
                    session = session.row,
                    loginAtMs = now,
                    webAuthnCredential = credential,
                    replacementRecoveryCodes = recovery?.second,
                ),
            )
        ) {
            throw InvalidChallengeException()
        }
        replacedSession?.let { revokeSessionInternal(credential.userId, it.id, now) }
        AuthResult(sessionFlow(session, recovery?.first.orEmpty()), session.token)
    }

    internal suspend fun completeOidc(
        issuer: String,
        subject: String,
        usernameClaim: String?,
        displayNameClaim: String?,
        groups: List<String>,
        adminMapped: Boolean,
    ): AuthResult = mutation.withLock {
        val now = clock()
        val existingIdentity = db.oidc().get(issuer, subject)
        var user = existingIdentity?.let { db.users().get(it.userId) }
        if (user == null) {
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
            val base = usernameClaim?.takeIf(String::isNotBlank) ?: "oidc-user"
            val username = availableOidcUsername(base, issuer, subject)
            user = createUser(
                username,
                displayNameClaim?.takeIf(String::isNotBlank) ?: username,
                UserStatus.ACTIVE,
                UserRole.USER,
                now,
            )
            copyDefaultGrants(user.id, now)
        }
        db.oidc().upsert(
            OidcIdentityRow(
                issuer, subject, user.id, usernameClaim, displayNameClaim,
                Json.encodeToString(groups), adminMapped, now,
            ),
        )
        val oidcAdmin = db.oidc().hasAdminMapping(user.id)
        val roleChanged = user.oidcAdmin != oidcAdmin
        val updatedUser = user.copy(
            displayName = displayNameClaim?.takeIf(String::isNotBlank) ?: user.displayName,
            oidcAdmin = oidcAdmin,
            updatedAtMs = now,
        )
        db.users().update(updatedUser)
        db.oidc().deletePending(issuer, subject)
        if (roleChanged) revokeUserSessions(updatedUser.id, now)
        if (updatedUser.status != UserStatus.ACTIVE) throw InvalidCredentialsException()
        val session = issueSession(updatedUser, AuthMethod.OIDC, mfa = true)
        db.users().markLogin(updatedUser.id, now)
        AuthResult(sessionFlow(session), session.token)
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
    ): AuthResult = flows.activate(request, clientIp)

    internal suspend fun bootstrap(request: BootstrapRequestDto, clientIp: String): AuthResult =
        flows.bootstrap(request, clientIp)

    internal suspend fun passwordLogin(
        request: PasswordLoginRequestDto,
        clientIp: String,
    ): AuthResult = flows.password(request, clientIp)

    suspend fun startTotpEnrollment(
        rawChallenge: String,
        clientIp: String,
    ): TotpEnrollmentDto = flows.startTotpEnrollment(rawChallenge, clientIp)

    internal suspend fun completeTotpEnrollment(
        request: TotpCompleteRequestDto,
        clientIp: String,
    ): AuthResult = flows.completeTotpEnrollment(request, clientIp)

    internal suspend fun completeTotp(
        request: TotpCompleteRequestDto,
        clientIp: String,
    ): AuthResult = flows.completeTotp(request, clientIp)

    internal suspend fun completeRecovery(
        request: RecoveryCompleteRequestDto,
        clientIp: String,
    ): AuthResult = flows.completeRecovery(request, clientIp)

    suspend fun authenticate(rawToken: String?): Pair<Actor, AuthSessionRow>? =
        sessionService.authenticate(rawToken)

    suspend fun current(actor: Actor): CurrentUserDto {
        val user = db.users().get(actor.userId) ?: throw UnauthenticatedApiException()
        return accounts.currentUserDto(
            user,
            actor.authMethod,
            actor.clientKind,
            csrfToken(actor),
        )
    }

    suspend fun logout(actor: Actor, all: Boolean) {
        val now = clock()
        if (all) revokeUserSessions(actor.userId, now)
        else revokeSessionInternal(actor.userId, actor.authSessionId, now)
    }

    suspend fun adminUsers(actor: Actor): List<AdminUserDto> =
        userAdministration.users(actor)

    suspend fun adminResume(actor: Actor, userId: String): List<ResumePointDto> =
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

    suspend fun deletePlaylistState(playlistId: Long) {
        db.grants().removeDefault(playlistId)
        db.grants().deletePlaylist(playlistId)
    }

    suspend fun csrfToken(actor: Actor): String =
        sessionService.csrf(actor)

    suspend fun validateCsrf(actor: Actor, provided: String?) {
        val expected = csrfToken(actor)
        if (provided == null || !constantEquals(expected, provided)) throw CsrfException()
    }

    suspend fun grants(userId: String): List<Long> = db.grants().forUser(userId)

    internal suspend fun regenerateRecoveryCodes(actor: Actor): AuthResult = mutation.withLock {
        recentlyAuthenticatedSession(actor)
        val codes = credentials.replaceRecoveryCodes(actor.userId)
        val user = db.users().get(actor.userId) ?: throw UnauthenticatedApiException()
        revokeSessionInternal(actor.userId, actor.authSessionId, clock())
        val session = issueSession(user, actor.authMethod, mfa = true)
        AuthResult(sessionFlow(session, codes), session.token)
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
        val session = issueSession(user, AuthMethod.PASSWORD, mfa = true)
        AuthResult(sessionFlow(session), session.token)
    }
    /**
     * One snapshot of the playlists [actor] may see. List endpoints take it once instead of
     * re-reading entitlements for every row; a single check costs the same as it always did.
     */
    suspend fun playlistAccess(actor: Actor): PlaylistAccess = PlaylistAccess(
        admin = actor.isAdmin,
        granted = if (actor.isAdmin) emptySet() else db.grants().forUser(actor.userId).toSet(),
        deleting = db.maintenance().pendingPlaylistDeletions().mapTo(mutableSetOf()) { it.playlistId },
    )

    suspend fun hasPlaylistAccess(actor: Actor, playlistId: Long): Boolean =
        playlistAccess(actor).allows(playlistId)

    internal suspend fun requireActiveActor(actor: Actor) = sessionService.requireActive(actor)

    private suspend fun issueSession(user: UserRow, method: String, mfa: Boolean): IssuedSession =
        sessionService.issue(user, method, mfa)

    private suspend fun prepareSession(user: UserRow, method: String, mfa: Boolean): IssuedSession =
        sessionService.prepare(user, method, mfa)

    private suspend fun sessionFlow(
        session: IssuedSession,
        recoveryCodes: List<String> = emptyList(),
    ) = AuthFlowDto(
        status = "AUTHENTICATED",
        user = accounts.currentUserDto(
            session.user,
            session.row.authMethod,
            session.row.clientKind,
            session.row.csrfToken,
        ),
        csrfToken = session.row.csrfToken,
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

    private fun constantEquals(left: String, right: String): Boolean =
        MessageDigest.isEqual(left.toByteArray(Charsets.UTF_8), right.toByteArray(Charsets.UTF_8))

    private companion object {
        const val MAX_ACTIVE_OIDC_STATES = 4_096
        const val CHALLENGE_KEY_LENGTH = 16
    }
}
