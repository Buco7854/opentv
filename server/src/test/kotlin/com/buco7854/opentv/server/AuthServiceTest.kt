package com.buco7854.opentv.server

import com.buco7854.opentv.serverdata.db.DefaultPlaylistRow
import com.buco7854.opentv.serverdata.createServerUserDatabase
import com.buco7854.opentv.serverdata.UserStatus
import kotlinx.coroutines.test.runTest
import java.net.URI
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class AuthServiceTest {
    @Test
    fun bootstrapRequiresMfaCopiesTemplateAndIssuesRevocableSession() = runTest {
        val dir = Files.createTempDirectory("opentv-auth-test")
        val db = createServerUserDatabase(dir.resolve("server-users.db").toString())
        var now = 1_700_000_000_000L
        var revokedSession: String? = null
        val cleanup = object : UserStateCleanupCoordinator {
            override suspend fun sessionRevoked(userId: String, authSessionId: String?) {
                revokedSession = authSessionId
            }
            override suspend fun playlistGrantRevoked(userId: String, playlistId: Long) = Unit
            override suspend fun userDeleted(userId: String) = Unit
            override suspend fun playlistDeleting(playlistId: Long) = Unit
            override suspend fun <T> admitPlayback(block: suspend () -> T): T = block()
        }
        val service = AuthService(db, authConfig(), dir, { now }, cleanup = cleanup)
        try {
            db.grants().addDefault(DefaultPlaylistRow(42))
            service.initialize()
            val bootstrapToken = Files.readString(dir.resolve("bootstrap.token")).trim()
            val first = service.bootstrap(
                BootstrapRequestDto(
                    bootstrapToken,
                    "Admin",
                    "a sufficiently long password",
                    "Administrator",
                ),
                "127.0.0.1",
            )
            assertEquals("ENROLLMENT_REQUIRED", first.flow.status)
            assertEquals("challenge_required", first.flow.code)
            assertNull(first.sessionToken)

            val enrollment = service.startTotpEnrollment(
                requireNotNull(first.flow.challenge),
                "127.0.0.1",
            )
            val code = AuthCrypto.totp(
                AuthCrypto.decodeBase32(enrollment.secret),
                now / 30_000L,
            )
            val completed = service.completeTotpEnrollment(
                TotpCompleteRequestDto(enrollment.challenge, code),
                "127.0.0.1",
            )
            assertEquals("AUTHENTICATED", completed.flow.status)
            assertEquals(10, completed.flow.recoveryCodes.size)
            assertEquals(listOf(42L), completed.flow.user?.playlistIds)
            assertTrue(!Files.exists(dir.resolve("bootstrap.token")))

            val authenticated = service.authenticate(assertNotNull(completed.sessionToken))
            assertEquals("Admin", authenticated?.first?.username)
            assertFailsWith<IllegalArgumentException> {
                service.adminUpdateUser(
                    requireNotNull(authenticated).first,
                    authenticated.first.userId,
                    UpdateUserRequestDto(status = UserStatus.DISABLED),
                )
            }
            assertFailsWith<IllegalArgumentException> {
                service.adminResetUser(
                    requireNotNull(authenticated).first,
                    authenticated.first.userId,
                )
            }
            service.logout(requireNotNull(authenticated).first, all = false)
            assertEquals(authenticated.first.authSessionId, revokedSession)
            assertNull(service.authenticate(completed.sessionToken))

            val secondLogin = service.passwordLogin(
                PasswordLoginRequestDto("Admin", "a sufficiently long password"),
                "127.0.0.1",
            )
            assertEquals("MFA_REQUIRED", secondLogin.flow.status)
            assertFailsWith<InvalidCredentialsException> {
                service.completeTotp(
                    TotpCompleteRequestDto(requireNotNull(secondLogin.flow.challenge), code),
                    "127.0.0.1",
                )
            }
            now += 30_000
            val thirdLogin = service.passwordLogin(
                PasswordLoginRequestDto("Admin", "a sufficiently long password"),
                "127.0.0.1",
            )
            val freshCode = AuthCrypto.totp(
                AuthCrypto.decodeBase32(enrollment.secret),
                now / 30_000L,
            )
            val completedTotp = service.completeTotp(
                TotpCompleteRequestDto(requireNotNull(thirdLogin.flow.challenge), freshCode),
                "127.0.0.1",
            )
            assertEquals("AUTHENTICATED", completedTotp.flow.status)
            assertNotNull(completedTotp.sessionToken)
        } finally {
            db.close()
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun invalidMfaChallengesContributeToTheIpRateLimit() = runTest {
        val dir = Files.createTempDirectory("opentv-auth-limit-test")
        val db = createServerUserDatabase(dir.resolve("server-users.db").toString())
        val service = AuthService(db, authConfig(), dir)
        try {
            repeat(5) {
                assertFailsWith<InvalidChallengeException> {
                    service.completeTotp(
                        TotpCompleteRequestDto("not-a-real-challenge-$it", "000000"),
                        "203.0.113.10",
                    )
                }
            }
            assertFailsWith<AuthRateLimitedException> {
                service.completeRecovery(
                    RecoveryCompleteRequestDto("another-invalid-challenge", "INVALID"),
                    "203.0.113.10",
                )
            }
        } finally {
            db.close()
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun accountSecurityIsReachableWithoutMfaButOnlyRightAfterSigningIn() = runTest {
        val dir = Files.createTempDirectory("opentv-auth-stepup-test")
        val db = createServerUserDatabase(dir.resolve("server-users.db").toString())
        var now = 1_700_000_000_000L
        val service = AuthService(db, authConfig(mfaRequiredRoles = emptySet()), dir, { now })
        try {
            service.initialize()
            val bootstrapToken = Files.readString(dir.resolve("bootstrap.token")).trim()
            val created = service.bootstrap(
                BootstrapRequestDto(bootstrapToken, "Admin", "a sufficiently long password", "Administrator"),
                "127.0.0.1",
            )
            assertEquals("AUTHENTICATED", created.flow.status)
            val actor = requireNotNull(service.authenticate(assertNotNull(created.sessionToken))).first

            val changed = service.changePassword(
                actor,
                PasswordChangeRequestDto("another sufficiently long password"),
            )
            assertEquals("AUTHENTICATED", changed.flow.status)

            val fresh = requireNotNull(service.authenticate(assertNotNull(changed.sessionToken))).first
            now += 5 * 60_000L + 1
            assertFailsWith<ForbiddenApiException> {
                service.changePassword(fresh, PasswordChangeRequestDto("a third sufficiently long password"))
            }

            val again = service.passwordLogin(
                PasswordLoginRequestDto("Admin", "another sufficiently long password"),
                "127.0.0.1",
            )
            assertEquals("AUTHENTICATED", again.flow.status)
            val reauthenticated =
                requireNotNull(service.authenticate(assertNotNull(again.sessionToken))).first
            service.changePassword(
                reauthenticated,
                PasswordChangeRequestDto("a third sufficiently long password"),
            )
        } finally {
            db.close()
            dir.toFile().deleteRecursively()
        }
    }

    private fun authConfig(mfaRequiredRoles: Set<String> = setOf("USER", "ADMIN")) = AuthConfig(
        publicUrl = URI("http://localhost:8080"),
        passwordEnabled = true,
        encryptionKey = ByteArray(32) { it.toByte() },
        initialAdmin = null,
        mfaRequiredRoles = mfaRequiredRoles,
        oidc = null,
        secureCookies = false,
        webAuthnRpId = "localhost",
        webAuthnOrigin = "http://localhost:8080",
        sessionIdleMs = 24 * 60 * 60_000L,
        sessionAbsoluteMs = 30L * 24 * 60 * 60_000L,
    )
}
