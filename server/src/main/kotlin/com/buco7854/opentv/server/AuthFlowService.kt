package com.buco7854.opentv.server

import com.buco7854.opentv.serverdata.AuthMethod
import com.buco7854.opentv.serverdata.ChallengeKind
import com.buco7854.opentv.serverdata.UserRole
import com.buco7854.opentv.serverdata.UserStatus
import com.buco7854.opentv.serverdata.db.MfaCompletionWrite
import com.buco7854.opentv.serverdata.db.ServerUserDatabase
import com.buco7854.opentv.serverdata.db.TotpCredentialRow
import com.buco7854.opentv.serverdata.db.completeMfa
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID

/** Local password, activation, MFA, and recovery flows, independent from HTTP delivery. */
internal class AuthFlowService(
    private val db: ServerUserDatabase,
    private val config: AuthConfig,
    private val bootstrapFile: Path,
    private val clock: () -> Long,
    private val mutation: Mutex,
    private val limiter: AuthRateLimiter,
    private val accounts: AuthAccountService,
    private val credentials: AuthCredentialService,
    private val sessions: PersistentSessionService,
) {
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

    suspend fun capabilities() = AuthCapabilitiesDto(
        passwordEnabled = config.passwordEnabled,
        oidcEnabled = config.oidc != null,
        bootstrapRequired = db.users().activeAdminCount() == 0 &&
            config.passwordEnabled && Files.exists(bootstrapFile),
        webAuthnRpId = config.webAuthnRpId,
        oidcStartUrl = config.oidc?.let { "/api/v1/auth/oidc/start" },
    )

    suspend fun bootstrap(request: BootstrapRequestDto, clientIp: String): AuthResult =
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
            val user = accounts.createUser(
                username = request.username,
                displayName = request.displayName.ifBlank { request.username },
                status = UserStatus.ACTIVE,
                role = UserRole.ADMIN,
                now = now,
            )
            credentials.setPassword(user.id, request.password, now)
            accounts.copyDefaultGrants(user.id, now)
            Files.deleteIfExists(bootstrapFile)
            accounts.event(null, user.id, "bootstrap_admin_created", clientIp)
            limiter.success(*keys)
            beginPostPassword(user, now)
        }

    suspend fun password(request: PasswordLoginRequestDto, clientIp: String): AuthResult {
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
            accounts.event(user?.id, user?.id, "password_login_failed", clientIp)
            throw InvalidCredentialsException()
        }
        limiter.success(*keys)
        credentials.maybeRehash(user.id, request.password, requireNotNull(credential))
        accounts.event(user.id, user.id, "password_verified", clientIp)
        return beginPostPassword(user, clock())
    }

    suspend fun activate(request: ActivationRequestDto, clientIp: String): AuthResult =
        mutation.withLock {
            require(config.passwordEnabled) { "Password authentication is disabled" }
            val keys = arrayOf("ip:$clientIp", "activation:${request.token.take(16)}")
            limiter.check(*keys)
            val challenge = runCatching {
                accounts.challenge(ChallengeKind.ACTIVATION, request.token)
            }.recoverCatching {
                accounts.challenge(ChallengeKind.PASSWORD_RESET, request.token)
            }.getOrElse {
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
            credentials.setPassword(user.id, request.password, clock())
            db.credentials().clearMfa(user.id)
            val activated = user.copy(status = UserStatus.ACTIVE, updatedAtMs = clock())
            db.users().update(activated)
            if (challenge.kind == ChallengeKind.ACTIVATION) {
                accounts.copyDefaultGrants(activated.id, clock())
            }
            limiter.success(*keys)
            accounts.event(activated.id, activated.id, "account_activated", clientIp)
            beginPostPassword(activated, clock())
        }

    suspend fun startTotpEnrollment(
        rawChallenge: String,
        clientIp: String,
    ): TotpEnrollmentDto = mutation.withLock {
        val keys = arrayOf("ip:$clientIp", "totp-enroll:${rawChallenge.take(16)}")
        limiter.check(*keys)
        val parent = runCatching { accounts.challenge(ChallengeKind.MFA, rawChallenge) }
            .getOrElse {
                limiter.fail(*keys)
                throw it
            }
        val user = runCatching {
            parent.userId?.let { activeUser(it) } ?: throw InvalidChallengeException()
        }.getOrElse {
            limiter.fail(*keys)
            throw it
        }
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
            ),
        )
        val enroll = accounts.issueChallenge(
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

    suspend fun completeTotpEnrollment(
        request: TotpCompleteRequestDto,
        clientIp: String,
    ): AuthResult = mutation.withLock {
        val keys = arrayOf("ip:$clientIp", "challenge:${request.challenge.take(16)}")
        limiter.check(*keys)
        val challenge = runCatching {
            accounts.challenge(ChallengeKind.TOTP_ENROLL, request.challenge)
        }.getOrElse {
            limiter.fail(*keys)
            throw it
        }
        val credentialId = challenge.payloadJson.substringBefore('|')
        val parentId = challenge.payloadJson.substringAfter('|', "")
        val credential = db.credentials().totp(credentialId) ?: run {
            limiter.fail(*keys)
            throw InvalidChallengeException()
        }
        val now = clock()
        val parent = db.challenges().get(parentId)
            ?.takeIf {
                it.kind == ChallengeKind.MFA &&
                    it.userId == credential.userId &&
                    it.consumedAtMs == null &&
                    it.expiresAtMs > now
            } ?: run {
            limiter.fail(*keys)
            throw InvalidChallengeException()
        }
        val user = runCatching { activeUser(credential.userId) }.getOrElse {
            limiter.fail(*keys)
            throw it
        }
        val step = credentials.verifyTotp(credential, request.code) ?: run {
            limiter.fail(*keys)
            throw InvalidCredentialsException()
        }
        val codes = credentials.newRecoveryCodes(credential.userId)
        val session = sessions.prepare(user, AuthMethod.PASSWORD, mfa = true)
        if (!db.completeMfa(
                challengeId = challenge.id,
                parentChallengeId = parent.id,
                write = MfaCompletionWrite(
                    session = session.row,
                    loginAtMs = now,
                    totpCredential = credential.copy(confirmed = true, lastAcceptedStep = step),
                    replacementRecoveryCodes = codes.second,
                ),
            )
        ) {
            throw InvalidChallengeException()
        }
        limiter.success(*keys)
        accounts.event(user.id, user.id, "totp_enrolled", clientIp)
        authenticated(session, codes.first)
    }

    suspend fun completeTotp(
        request: TotpCompleteRequestDto,
        clientIp: String,
    ): AuthResult = mutation.withLock {
        val keys = arrayOf("ip:$clientIp", "challenge:${request.challenge.take(12)}")
        limiter.check(*keys)
        val challenge = runCatching { accounts.challenge(ChallengeKind.MFA, request.challenge) }
            .getOrElse {
                limiter.fail(*keys)
                throw it
            }
        val userId = challenge.userId ?: run {
            limiter.fail(*keys)
            throw InvalidChallengeException()
        }
        val user = runCatching { activeUser(userId) }.getOrElse {
            limiter.fail(*keys)
            throw it
        }
        val matched = db.credentials().confirmedTotp(userId).firstNotNullOfOrNull { credential ->
            credentials.verifyTotp(credential, request.code)?.let { credential to it }
        }
        if (matched == null) {
            limiter.fail(*keys)
            accounts.event(userId, userId, "totp_login_failed", clientIp)
            throw InvalidCredentialsException()
        }
        val now = clock()
        val session = sessions.prepare(user, AuthMethod.PASSWORD, mfa = true)
        if (!db.completeMfa(
                challengeId = challenge.id,
                write = MfaCompletionWrite(
                    session = session.row,
                    loginAtMs = now,
                    totpCredential = matched.first.copy(lastAcceptedStep = matched.second),
                ),
            )
        ) {
            throw InvalidChallengeException()
        }
        limiter.success(*keys)
        accounts.event(user.id, user.id, "login_succeeded", clientIp)
        authenticated(session)
    }

    suspend fun completeRecovery(
        request: RecoveryCompleteRequestDto,
        clientIp: String,
    ): AuthResult = mutation.withLock {
        val keys = arrayOf("ip:$clientIp", "challenge:${request.challenge.take(12)}")
        limiter.check(*keys)
        val challenge = runCatching { accounts.challenge(ChallengeKind.MFA, request.challenge) }
            .getOrElse {
                limiter.fail(*keys)
                throw it
            }
        val userId = challenge.userId ?: run {
            limiter.fail(*keys)
            throw InvalidChallengeException()
        }
        val user = runCatching { activeUser(userId) }.getOrElse {
            limiter.fail(*keys)
            throw it
        }
        val hash = AuthCrypto.hashToken(request.code.trim().uppercase())
        val row = db.credentials().unusedRecoveryCodes(userId)
            .firstOrNull { MessageDigest.isEqual(it.codeHash, hash) }
        if (row == null) {
            limiter.fail(*keys)
            throw InvalidCredentialsException()
        }
        val now = clock()
        val session = sessions.prepare(user, AuthMethod.PASSWORD, mfa = true)
        if (!db.completeMfa(
                challengeId = challenge.id,
                recoveryCodeId = row.id,
                write = MfaCompletionWrite(session.row, now),
            )
        ) {
            throw InvalidChallengeException()
        }
        limiter.success(*keys)
        accounts.event(user.id, user.id, "recovery_code_used", clientIp)
        authenticated(session)
    }

    private suspend fun beginPostPassword(
        user: com.buco7854.opentv.serverdata.db.UserRow,
        now: Long,
    ): AuthResult {
        val methods = buildList {
            if (db.credentials().confirmedTotp(user.id).isNotEmpty()) add("totp")
            if (db.credentials().webAuthn(user.id).isNotEmpty()) add("webauthn")
            if (db.credentials().unusedRecoveryCodes(user.id).isNotEmpty()) add("recovery")
        }
        if (accounts.effectiveRole(user) in config.mfaRequiredRoles) {
            val issued = accounts.issueChallenge(
                user.id,
                ChallengeKind.MFA,
                "",
                5 * 60_000L,
            )
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
                ),
            )
        }
        val session = sessions.issue(user, AuthMethod.PASSWORD, mfa = false)
        db.users().markLogin(user.id, now)
        return authenticated(session)
    }

    private suspend fun authenticated(
        session: IssuedSession,
        recoveryCodes: List<String> = emptyList(),
    ): AuthResult {
        val user = accounts.currentUserDto(
            session.user,
            session.row.authMethod,
            session.row.clientKind,
            session.row.csrfToken,
        )
        return AuthResult(
            AuthFlowDto(
                status = "AUTHENTICATED",
                user = user,
                csrfToken = session.row.csrfToken,
                recoveryCodes = recoveryCodes,
            ),
            session.token,
        )
    }

    private suspend fun activeUser(userId: String) =
        db.users().get(userId)?.takeIf { it.status == UserStatus.ACTIVE }
            ?: throw InvalidChallengeException()

    private fun constantEquals(left: String, right: String): Boolean =
        MessageDigest.isEqual(left.toByteArray(Charsets.UTF_8), right.toByteArray(Charsets.UTF_8))
}
