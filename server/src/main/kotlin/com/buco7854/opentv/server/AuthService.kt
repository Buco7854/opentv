package com.buco7854.opentv.server

import com.buco7854.opentv.serverdata.AuthMethod
import com.buco7854.opentv.serverdata.ChallengeKind
import com.buco7854.opentv.serverdata.UserRecord
import com.buco7854.opentv.serverdata.UserRole
import com.buco7854.opentv.serverdata.UserStatus
import com.buco7854.opentv.serverdata.db.AuthChallengeRow
import com.buco7854.opentv.serverdata.db.AuthSessionRow
import com.buco7854.opentv.serverdata.db.PasswordCredentialRow
import com.buco7854.opentv.serverdata.db.OidcIdentityRow
import com.buco7854.opentv.serverdata.db.PendingOidcIdentityRow
import com.buco7854.opentv.serverdata.db.RecoveryCodeRow
import com.buco7854.opentv.serverdata.db.SecurityEventRow
import com.buco7854.opentv.serverdata.db.ServerUserDatabase
import com.buco7854.opentv.serverdata.db.TotpCredentialRow
import com.buco7854.opentv.serverdata.db.UserPlaylistGrantRow
import com.buco7854.opentv.serverdata.db.UserRow
import com.buco7854.opentv.serverdata.db.WebAuthnCredentialRow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.UUID
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
    private val dummySalt = AuthCrypto.sha256("opentv-dummy-salt".toByteArray()).copyOf(16)
    private val dummyHash by lazy {
        AuthCrypto.passwordHash(
            "not-a-real-password",
            dummySalt,
            AuthCrypto.ARGON_MEMORY_KB,
            AuthCrypto.ARGON_ITERATIONS,
            AuthCrypto.ARGON_PARALLELISM,
        ).first
    }
    private val bootstrapFile = dataDir.resolve("bootstrap.token")
    private val log = LoggerFactory.getLogger("opentv.auth")
    private val sessionService = PersistentSessionService(db, config, cleanup, clock)
    internal val requestAuthenticator = RequestAuthenticator(sessionService)
    internal val flows = AuthFlowService(this)
    private val userAdministration by lazy {
        UserAdministrationService(db, this, cleanup, clock)
    }
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

    suspend fun capabilities(): AuthCapabilitiesDto = AuthCapabilitiesDto(
        passwordEnabled = config.passwordEnabled,
        oidcEnabled = config.oidc != null,
        bootstrapRequired = db.users().activeAdminCount() == 0 &&
            config.passwordEnabled && Files.exists(bootstrapFile),
        webAuthnRpId = config.webAuthnRpId,
        oidcStartUrl = config.oidc?.let { "/api/v1/auth/oidc/start" },
    )

    internal suspend fun issueOidcState(payload: String): Pair<String, Long> =
        issueChallenge(null, ChallengeKind.OIDC, payload, 5 * 60_000L)

    internal suspend fun consumeOidcState(state: String): String {
        return mutation.withLock {
            val row = challenge(ChallengeKind.OIDC, state)
            if (db.challenges().consume(row.id, clock()) != 1) throw InvalidChallengeException()
            row.payloadJson
        }
    }

    internal suspend fun mfaChallenge(raw: String): AuthChallengeRow =
        challenge(ChallengeKind.MFA, raw)

    internal suspend fun recentMfaChallenge(actor: Actor): String {
        val session = recentMfaSession(actor)
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

    internal suspend fun consumeWebAuthnChallenge(kind: String, raw: String): AuthChallengeRow {
        return mutation.withLock {
            val row = challenge(kind, raw)
            if (db.challenges().consume(row.id, clock()) != 1) throw InvalidChallengeException()
            row
        }
    }

    internal fun checkFlowLimit(clientIp: String, flow: String, challenge: String) =
        limiter.check("ip:$clientIp", "$flow:${challenge.take(16)}")

    internal fun failFlowLimit(clientIp: String, flow: String, challenge: String) =
        limiter.fail("ip:$clientIp", "$flow:${challenge.take(16)}")

    internal fun clearFlowLimit(clientIp: String, flow: String, challenge: String) =
        limiter.success("ip:$clientIp", "$flow:${challenge.take(16)}")

    internal suspend fun finishWebAuthn(
        parentChallengeId: String,
        credential: WebAuthnCredentialRow,
        enrollment: Boolean,
        clientIp: String,
    ): AuthResult = mutation.withLock {
        val now = clock()
        val parent = db.challenges().get(parentChallengeId)
            ?.takeIf { it.kind == ChallengeKind.MFA && it.consumedAtMs == null && it.expiresAtMs > now }
            ?: throw InvalidChallengeException()
        require(parent.userId == credential.userId) { "Credential owner mismatch" }
        val user = activeUser(credential.userId)
        if (db.challenges().consume(parent.id, now) != 1) throw InvalidChallengeException()
        db.credentials().upsertWebAuthn(credential)
        val replacedSession = parent.payloadJson.removePrefix("reauth:")
            .takeIf { parent.payloadJson.startsWith("reauth:") }
            ?.let { db.sessions().get(it) }
            ?.takeIf { it.userId == credential.userId }
        replacedSession?.let { revokeSessionInternal(credential.userId, it.id, now) }
        val recovery = if (enrollment) replaceRecoveryCodes(credential.userId) else emptyList()
        val session = issueSession(
            user,
            replacedSession?.authMethod ?: AuthMethod.PASSWORD,
            mfa = true,
        )
        db.users().markLogin(user.id, now)
        event(user.id, user.id, if (enrollment) "webauthn_enrolled" else "webauthn_login", clientIp)
        AuthResult(sessionFlow(session, recovery), session.token)
    }

    internal suspend fun completeOidc(
        issuer: String,
        subject: String,
        usernameClaim: String?,
        displayNameClaim: String?,
        groups: List<String>,
        adminMapped: Boolean,
        clientIp: String,
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
                event(null, null, "oidc_identity_pending", clientIp)
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
        event(updatedUser.id, updatedUser.id, "oidc_login_succeeded", clientIp)
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
    ): AuthResult = mutation.withLock {
        require(config.passwordEnabled) { "Password authentication is disabled" }
        val keys = arrayOf("ip:$clientIp", "activation:${request.token.take(16)}")
        limiter.check(*keys)
        val challenge = runCatching { challenge(ChallengeKind.ACTIVATION, request.token) }
            .recoverCatching { challenge(ChallengeKind.PASSWORD_RESET, request.token) }
            .getOrElse {
                limiter.fail(*keys)
                throw InvalidChallengeException()
            }
        val user = challenge.userId?.let { db.users().get(it) } ?: run {
            limiter.fail(*keys)
            throw InvalidChallengeException()
        }
        AuthCrypto.validatePassword(request.password)
        if (db.challenges().consume(challenge.id, clock()) != 1) {
            limiter.fail(*keys)
            throw InvalidChallengeException()
        }
        setPassword(user.id, request.password, clock())
        db.credentials().clearMfa(user.id)
        val activated = user.copy(
            status = UserStatus.ACTIVE,
            updatedAtMs = clock(),
        )
        db.users().update(activated)
        if (challenge.kind == ChallengeKind.ACTIVATION) copyDefaultGrants(activated.id, clock())
        limiter.success(*keys)
        event(activated.id, activated.id, "account_activated", clientIp)
        beginPostPassword(activated, clock())
    }

    internal suspend fun bootstrap(request: BootstrapRequestDto, clientIp: String): AuthResult =
        mutation.withLock {
            require(config.passwordEnabled) { "Password authentication is disabled" }
            val normalized = runCatching { AuthCrypto.normalizeUsername(request.username) }
                .getOrDefault("__invalid__")
            val keys = arrayOf("ip:$clientIp", "bootstrap", "user:$normalized")
            limiter.check(*keys)
            val expected = runCatching { Files.readString(bootstrapFile).trim() }.getOrNull()
            if (expected == null || !constantEquals(expected, request.token) ||
                db.users().activeAdminCount() > 0
            ) {
                limiter.fail(*keys)
                throw InvalidCredentialsException()
            }
            val now = clock()
            val user = createUser(
                username = request.username,
                displayName = request.displayName.ifBlank { request.username },
                status = UserStatus.ACTIVE,
                role = UserRole.ADMIN,
                now = now,
            )
            setPassword(user.id, request.password, now)
            copyDefaultGrants(user.id, now)
            Files.deleteIfExists(bootstrapFile)
            event(null, user.id, "bootstrap_admin_created", clientIp)
            limiter.success(*keys)
            beginPostPassword(user, now)
        }

    internal suspend fun passwordLogin(request: PasswordLoginRequestDto, clientIp: String): AuthResult {
        require(config.passwordEnabled) { "Password authentication is disabled" }
        val normalized = runCatching { AuthCrypto.normalizeUsername(request.username) }.getOrNull()
            ?: "__invalid__"
        val keys = arrayOf("ip:$clientIp", "user:$normalized")
        limiter.check(*keys)
        val user = db.users().byNormalizedUsername(normalized)
        val credential = user?.let { db.credentials().password(it.id) }
        val verified = if (credential != null) {
            AuthCrypto.verifyPassword(
                request.password,
                credential.hash,
                credential.salt,
                credential.memoryKb,
                credential.iterations,
                credential.parallelism,
            )
        } else {
            AuthCrypto.verifyPassword(
                request.password.ifBlank { "invalid-password" },
                dummyHash,
                dummySalt,
                AuthCrypto.ARGON_MEMORY_KB,
                AuthCrypto.ARGON_ITERATIONS,
                AuthCrypto.ARGON_PARALLELISM,
            )
            false
        }
        if (!verified || user == null || user.status != UserStatus.ACTIVE) {
            limiter.fail(*keys)
            event(user?.id, user?.id, "password_login_failed", clientIp)
            throw InvalidCredentialsException()
        }
        limiter.success(*keys)
        maybeRehash(user.id, request.password, requireNotNull(credential))
        event(user.id, user.id, "password_verified", clientIp)
        return beginPostPassword(user, clock())
    }

    suspend fun startTotpEnrollment(
        rawChallenge: String,
        clientIp: String,
    ): TotpEnrollmentDto = mutation.withLock {
        val keys = arrayOf("ip:$clientIp", "totp-enroll:${rawChallenge.take(16)}")
        limiter.check(*keys)
        val parent = runCatching { challenge(ChallengeKind.MFA, rawChallenge) }
            .getOrElse {
                limiter.fail(*keys)
                throw it
            }
        val user = parent.userId?.let { activeUser(it) } ?: throw InvalidChallengeException()
        if (parent.payloadJson.isBlank() &&
            (db.credentials().confirmedTotp(user.id).isNotEmpty() ||
                db.credentials().webAuthn(user.id).isNotEmpty())
        ) {
            throw ForbiddenApiException()
        }
        if (db.credentials().confirmedTotp(user.id).isNotEmpty()) throw ForbiddenApiException()
        db.credentials().deleteUnconfirmedTotp(user.id)
        val secret = AuthCrypto.randomBytes(20)
        val id = UUID.randomUUID().toString()
        val now = clock()
        db.credentials().upsertTotp(
            TotpCredentialRow(
                id = id,
                userId = user.id,
                encryptedSecret = AuthCrypto.encrypt(
                    requireNotNull(config.encryptionKey),
                    "totp:${user.id}:$id",
                    secret,
                ),
                label = "Authenticator",
                confirmed = false,
                lastAcceptedStep = null,
                createdAtMs = now,
            )
        )
        val enroll = issueChallenge(
            user.id,
            ChallengeKind.TOTP_ENROLL,
            payload = "$id|${parent.id}",
            ttlMs = 5 * 60_000L,
        )
        TotpEnrollmentDto(
            challenge = enroll.first,
            secret = AuthCrypto.base32(secret),
            uri = AuthCrypto.totpUri(secret, user.username),
            expiresAtMs = enroll.second,
        ).also { limiter.success(*keys) }
    }

    internal suspend fun completeTotpEnrollment(
        request: TotpCompleteRequestDto,
        clientIp: String,
    ): AuthResult = mutation.withLock {
        val keys = arrayOf("ip:$clientIp", "challenge:${request.challenge.take(16)}")
        limiter.check(*keys)
        val challenge = challenge(ChallengeKind.TOTP_ENROLL, request.challenge)
        val credentialId = challenge.payloadJson.substringBefore('|')
        val parentId = challenge.payloadJson.substringAfter('|', "")
        val credential = db.credentials().totp(credentialId)
            ?: throw InvalidChallengeException()
        val now = clock()
        val parent = db.challenges().get(parentId)
            ?.takeIf {
                it.kind == ChallengeKind.MFA &&
                    it.userId == credential.userId &&
                    it.consumedAtMs == null &&
                    it.expiresAtMs > now
            } ?: throw InvalidChallengeException()
        val user = activeUser(credential.userId)
        val step = verifyTotp(credential, request.code) ?: run {
            limiter.fail(*keys)
            throw InvalidCredentialsException()
        }
        if (db.challenges().consume(challenge.id, now) != 1 ||
            db.challenges().consume(parent.id, now) != 1
        ) {
            throw InvalidChallengeException()
        }
        db.credentials().upsertTotp(credential.copy(confirmed = true, lastAcceptedStep = step))
        val codes = replaceRecoveryCodes(credential.userId)
        val session = issueSession(user, AuthMethod.PASSWORD, mfa = true)
        db.users().markLogin(user.id, now)
        limiter.success(*keys)
        event(user.id, user.id, "totp_enrolled", clientIp)
        AuthResult(sessionFlow(session, codes), session.token)
    }

    internal suspend fun completeTotp(
        request: TotpCompleteRequestDto,
        clientIp: String,
    ): AuthResult = mutation.withLock {
        val keys = arrayOf("ip:$clientIp", "challenge:${request.challenge.take(12)}")
        limiter.check(*keys)
        val challenge = challenge(ChallengeKind.MFA, request.challenge)
        val userId = challenge.userId ?: throw InvalidChallengeException()
        val user = activeUser(userId)
        val matched = db.credentials().confirmedTotp(userId).firstNotNullOfOrNull { credential ->
            verifyTotp(credential, request.code)?.let { credential to it }
        }
        if (matched == null) {
            limiter.fail(*keys)
            event(userId, userId, "totp_login_failed", clientIp)
            throw InvalidCredentialsException()
        }
        db.credentials().upsertTotp(matched.first.copy(lastAcceptedStep = matched.second))
        if (db.challenges().consume(challenge.id, clock()) != 1) throw InvalidChallengeException()
        limiter.success(*keys)
        val session = issueSession(user, AuthMethod.PASSWORD, mfa = true)
        db.users().markLogin(user.id, clock())
        event(user.id, user.id, "login_succeeded", clientIp)
        AuthResult(sessionFlow(session), session.token)
    }

    internal suspend fun completeRecovery(
        request: RecoveryCompleteRequestDto,
        clientIp: String,
    ): AuthResult = mutation.withLock {
        val keys = arrayOf("ip:$clientIp", "challenge:${request.challenge.take(12)}")
        limiter.check(*keys)
        val challenge = challenge(ChallengeKind.MFA, request.challenge)
        val userId = challenge.userId ?: throw InvalidChallengeException()
        val user = activeUser(userId)
        val hash = AuthCrypto.hashToken(request.code.trim().uppercase())
        val row = db.credentials().unusedRecoveryCodes(userId)
            .firstOrNull { MessageDigest.isEqual(it.codeHash, hash) }
        if (row == null || db.credentials().consumeRecoveryCode(row.id, clock()) != 1) {
            limiter.fail(*keys)
            throw InvalidCredentialsException()
        }
        if (db.challenges().consume(challenge.id, clock()) != 1) throw InvalidChallengeException()
        limiter.success(*keys)
        val session = issueSession(user, AuthMethod.PASSWORD, mfa = true)
        db.users().markLogin(user.id, clock())
        event(user.id, user.id, "recovery_code_used", clientIp)
        AuthResult(sessionFlow(session), session.token)
    }

    suspend fun authenticate(rawToken: String?): Pair<Actor, AuthSessionRow>? =
        sessionService.authenticate(rawToken)

    suspend fun current(actor: Actor): CurrentUserDto {
        val user = db.users().get(actor.userId) ?: throw UnauthenticatedApiException()
        return userDto(user, actor.authMethod, actor.clientKind, csrfToken(actor))
    }

    suspend fun logout(actor: Actor, all: Boolean) {
        val now = clock()
        if (all) revokeUserSessions(actor.userId, now)
        else revokeSessionInternal(actor.userId, actor.authSessionId, now)
        event(actor.userId, actor.userId, if (all) "logout_all" else "logout", null)
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
        clientIp: String,
    ): CreatedUserDto = userAdministration.create(actor, request, clientIp)

    internal suspend fun adminUpdateUser(
        actor: Actor,
        userId: String,
        request: UpdateUserRequestDto,
        clientIp: String,
    ): AdminUserDto = userAdministration.update(actor, userId, request, clientIp)

    suspend fun adminDeleteUser(actor: Actor, userId: String, clientIp: String) =
        userAdministration.delete(actor, userId, clientIp)

    internal suspend fun adminResetUser(
        actor: Actor,
        userId: String,
        clientIp: String,
    ): ResetUserDto = userAdministration.reset(actor, userId, clientIp)

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
        recentMfaSession(actor)
        val codes = replaceRecoveryCodes(actor.userId)
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
        recentMfaSession(actor)
        db.credentials().password(actor.userId) ?: throw ForbiddenApiException()
        setPassword(actor.userId, request.password, clock())
        revokeSessionInternal(actor.userId, actor.authSessionId, clock())
        val user = db.users().get(actor.userId) ?: throw UnauthenticatedApiException()
        val session = issueSession(user, AuthMethod.PASSWORD, mfa = true)
        AuthResult(sessionFlow(session), session.token)
    }
    suspend fun hasPlaylistAccess(actor: Actor, playlistId: Long): Boolean =
        actor.isAdmin || playlistId in db.grants().forUser(actor.userId)

    internal suspend fun validatePlaylistIds(ids: List<Long>): List<Long> {
        require(ids.size <= 1_000) { "Too many playlist assignments" }
        val distinct = ids.distinct()
        distinct.forEach { require(playlistExists(it)) { "Unknown playlist: $it" } }
        return distinct
    }

    private suspend fun beginPostPassword(user: UserRow, now: Long): AuthResult {
        val methods = buildList {
            if (db.credentials().confirmedTotp(user.id).isNotEmpty()) add("totp")
            if (db.credentials().webAuthn(user.id).isNotEmpty()) add("webauthn")
            if (db.credentials().unusedRecoveryCodes(user.id).isNotEmpty()) add("recovery")
        }
        val role = effectiveRole(user)
        if (role in config.mfaRequiredRoles) {
            val issued = issueChallenge(user.id, ChallengeKind.MFA, "", 5 * 60_000L)
            return AuthResult(
                AuthFlowDto(
                    status = if (methods.any { it == "totp" || it == "webauthn" }) {
                        "MFA_REQUIRED"
                    } else {
                        "ENROLLMENT_REQUIRED"
                    },
                    code = "challenge_required",
                    challenge = issued.first,
                    methods = methods.ifEmpty { listOf("totp", "webauthn") },
                    expiresAtMs = issued.second,
                )
            )
        }
        val session = issueSession(user, AuthMethod.PASSWORD, mfa = false)
        db.users().markLogin(user.id, now)
        return AuthResult(sessionFlow(session), session.token)
    }

    private suspend fun issueSession(user: UserRow, method: String, mfa: Boolean): IssuedSession =
        sessionService.issue(user, method, mfa)

    private suspend fun sessionFlow(
        session: IssuedSession,
        recoveryCodes: List<String> = emptyList(),
    ) = AuthFlowDto(
        status = "AUTHENTICATED",
        user = userDto(
            session.user,
            session.row.authMethod,
            session.row.clientKind,
            session.row.csrfToken,
        ),
        csrfToken = session.row.csrfToken,
        recoveryCodes = recoveryCodes,
    )

    private suspend fun userDto(
        user: UserRow,
        method: String,
        clientKind: String,
        csrfToken: String,
    ) = CurrentUserDto(
        id = user.id,
        username = user.username,
        displayName = user.displayName,
        role = effectiveRole(user),
        authMethod = method,
        clientKind = clientKind,
        playlistIds = db.grants().forUser(user.id),
        csrfToken = csrfToken,
    )

    private fun effectiveRole(user: UserRow) =
        if (user.manualRole == UserRole.ADMIN || user.oidcAdmin) UserRole.ADMIN else UserRole.USER

    private suspend fun createSeedAdmin(seed: InitialAdminConfig, now: Long) {
        val user = createUser(seed.username, seed.username, UserStatus.ACTIVE, UserRole.ADMIN, now)
        setPassword(user.id, seed.password, now)
        copyDefaultGrants(user.id, now)
        event(null, user.id, "initial_admin_seeded", null)
    }

    internal suspend fun createUser(
        username: String,
        displayName: String,
        status: String,
        role: String,
        now: Long,
    ): UserRow {
        val normalized = AuthCrypto.normalizeUsername(username)
        require(db.users().byNormalizedUsername(normalized) == null) { "Username is already in use" }
        val cleanDisplayName = displayName.trim().ifBlank { username.trim() }
        require(cleanDisplayName.codePointCount(0, cleanDisplayName.length) <= 128) {
            "Display name must be at most 128 characters"
        }
        return UserRow(
            id = UUID.randomUUID().toString(),
            username = username.trim(),
            normalizedUsername = normalized,
            displayName = cleanDisplayName,
            status = status,
            manualRole = role,
            oidcAdmin = false,
            createdAtMs = now,
            updatedAtMs = now,
            lastLoginAtMs = null,
        ).also { db.users().insert(it) }
    }

    internal suspend fun availableOidcUsername(base: String, issuer: String, subject: String): String {
        val cleanBase = base.trim().ifBlank { "oidc-user" }
        if (db.users().byNormalizedUsername(AuthCrypto.normalizeUsername(cleanBase)) == null) {
            return cleanBase
        }
        val suffix = AuthCrypto.sha256("$issuer\u0000$subject".toByteArray())
            .take(4).joinToString("") { "%02x".format(it) }
        return "$cleanBase-$suffix"
    }

    private suspend fun setPassword(userId: String, password: String, now: Long) {
        val (hash, salt) = AuthCrypto.passwordHash(password)
        db.credentials().upsertPassword(
            PasswordCredentialRow(
                userId = userId,
                hash = hash,
                salt = salt,
                memoryKb = AuthCrypto.ARGON_MEMORY_KB,
                iterations = AuthCrypto.ARGON_ITERATIONS,
                parallelism = AuthCrypto.ARGON_PARALLELISM,
                version = AuthCrypto.ARGON_VERSION,
                changedAtMs = now,
            )
        )
    }

    private suspend fun maybeRehash(userId: String, password: String, row: PasswordCredentialRow) {
        if (row.version == AuthCrypto.ARGON_VERSION &&
            row.memoryKb == AuthCrypto.ARGON_MEMORY_KB &&
            row.iterations == AuthCrypto.ARGON_ITERATIONS &&
            row.parallelism == AuthCrypto.ARGON_PARALLELISM
        ) return
        setPassword(userId, password, clock())
    }

    internal suspend fun issueChallenge(
        userId: String?,
        kind: String,
        payload: String,
        ttlMs: Long,
    ): Pair<String, Long> {
        val raw = AuthCrypto.token()
        val now = clock()
        val expires = now + ttlMs
        db.challenges().insert(
            AuthChallengeRow(
                id = UUID.randomUUID().toString(),
                userId = userId,
                kind = kind,
                tokenHash = AuthCrypto.hashToken(raw),
                payloadJson = payload,
                attempts = 0,
                createdAtMs = now,
                expiresAtMs = expires,
                consumedAtMs = null,
            )
        )
        return raw to expires
    }

    private suspend fun challenge(kind: String, raw: String): AuthChallengeRow {
        if (raw.isBlank() || raw.length > 512) throw InvalidChallengeException()
        val now = clock()
        return db.challenges().byToken(kind, AuthCrypto.hashToken(raw))
            ?.takeIf { it.consumedAtMs == null && it.expiresAtMs > now }
            ?: throw InvalidChallengeException()
    }

    private fun verifyTotp(row: TotpCredentialRow, code: String): Long? {
        if (!code.matches(Regex("\\d{6}"))) return null
        val secret = AuthCrypto.decrypt(
            requireNotNull(config.encryptionKey),
            "totp:${row.userId}:${row.id}",
            row.encryptedSecret,
        )
        val current = clock() / 30_000L
        return (current - 1..current + 1).firstOrNull { step ->
            step > (row.lastAcceptedStep ?: Long.MIN_VALUE) &&
                constantEquals(AuthCrypto.totp(secret, step), code)
        }
    }

    private suspend fun replaceRecoveryCodes(userId: String): List<String> {
        val now = clock()
        val raw = List(10) {
            AuthCrypto.token(9).uppercase().chunked(4).joinToString("-")
        }
        db.credentials().replaceRecoveryCodes(
            userId,
            raw.map {
                RecoveryCodeRow(
                    id = UUID.randomUUID().toString(),
                    userId = userId,
                    codeHash = AuthCrypto.hashToken(it),
                    createdAtMs = now,
                    usedAtMs = null,
                )
            },
        )
        return raw
    }

    internal suspend fun copyDefaultGrants(userId: String, now: Long) {
        db.grants().defaults().forEach {
            db.grants().grant(UserPlaylistGrantRow(userId, it, now))
        }
    }

    internal suspend fun adminUserDto(user: UserRow): AdminUserDto {
        val methods = buildList {
            if (db.credentials().password(user.id) != null) add("password")
            if (db.credentials().confirmedTotp(user.id).isNotEmpty()) add("totp")
            if (db.credentials().webAuthn(user.id).isNotEmpty()) add("webauthn")
            if (db.oidc().forUser(user.id).isNotEmpty()) add("oidc")
        }
        return AdminUserDto(
            id = user.id,
            username = user.username,
            displayName = user.displayName,
            status = user.status,
            manualRole = user.manualRole,
            effectiveRole = effectiveRole(user),
            authMethods = methods,
            playlistIds = db.grants().forUser(user.id),
            createdAtMs = user.createdAtMs,
            lastLoginAtMs = user.lastLoginAtMs,
        )
    }

    private fun requireAdmin(actor: Actor) {
        if (!actor.isAdmin) throw ForbiddenApiException()
    }

    private suspend fun activeUser(userId: String): UserRow =
        db.users().get(userId)?.takeIf { it.status == UserStatus.ACTIVE }
            ?: throw InvalidChallengeException()

    internal suspend fun revokeUserSessions(userId: String, atMs: Long) {
        sessionService.revokeUser(userId, atMs)
    }

    internal suspend fun revokeSessionInternal(userId: String, sessionId: String, atMs: Long) {
        sessionService.revoke(userId, sessionId, atMs)
    }

    private suspend fun recentMfaSession(actor: Actor): AuthSessionRow =
        sessionService.recentMfa(actor)

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

    internal suspend fun event(
        actorUserId: String?,
        subjectUserId: String?,
        type: String,
        clientIp: String?,
    ) {
        db.securityEvents().insert(
            SecurityEventRow(
                id = UUID.randomUUID().toString(),
                actorUserId = actorUserId,
                subjectUserId = subjectUserId,
                type = type,
                detail = "",
                clientIp = clientIp,
                createdAtMs = clock(),
            )
        )
    }

    private fun constantEquals(left: String, right: String): Boolean =
        MessageDigest.isEqual(left.toByteArray(Charsets.UTF_8), right.toByteArray(Charsets.UTF_8))

    internal suspend fun <T> mutate(block: suspend () -> T): T = mutation.withLock { block() }
}

private fun UserRow.toRecord() = UserRecord(
    id,
    username,
    normalizedUsername,
    displayName,
    status,
    manualRole,
    oidcAdmin,
    createdAtMs,
    updatedAtMs,
    lastLoginAtMs,
)
