package com.buco7854.opentv.server

import com.buco7854.opentv.serverdata.createServerUserDatabase
import kotlinx.coroutines.test.runTest
import java.net.URI
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WebAuthnServiceTest {
    @Test
    fun registrationPinsServerPropertiesAndRateLimitsMalformedResponses() = runTest {
        val dir = Files.createTempDirectory("opentv-webauthn-test")
        val db = createServerUserDatabase(dir.resolve("server-users.db").toString())
        try {
            val config = config()
            val auth = AuthService(db, config, dir)
            auth.initialize()
            val bootstrapToken = Files.readString(dir.resolve("bootstrap.token")).trim()
            val bootstrap = auth.bootstrap(
                BootstrapRequestDto(
                    bootstrapToken,
                    "Admin",
                    "a sufficiently long password",
                    "Administrator",
                ),
                "127.0.0.1",
            )
            val parentChallenge = assertNotNull(bootstrap.flow.challenge)
            val webAuthn = WebAuthnService(db, auth, config)

            val options = webAuthn.registrationOptions(
                WebAuthnOptionsRequestDto(parentChallenge),
                "127.0.0.1",
            )

            assertEquals("localhost", options.rp?.id)
            assertEquals("Admin", options.user?.name)
            assertEquals("none", options.attestation)
            assertTrue(options.challenge.isNotBlank())
            val invalid = WebAuthnCompleteRequestDto(
                challenge = options.serverChallenge,
                credential = "{}",
                label = "Test key",
            )
            assertFailsWith<InvalidCredentialsException> {
                webAuthn.completeRegistration(invalid, "127.0.0.1")
            }
            repeat(4) {
                assertFailsWith<InvalidCredentialsException> {
                    webAuthn.completeRegistration(invalid, "127.0.0.1")
                }
            }
            assertFailsWith<AuthRateLimitedException> {
                webAuthn.completeRegistration(invalid, "127.0.0.1")
            }
        } finally {
            db.close()
            dir.toFile().deleteRecursively()
        }
    }

    private fun config() = AuthConfig(
        publicUrl = URI("http://localhost:8080"),
        passwordEnabled = true,
        encryptionKey = ByteArray(32) { it.toByte() },
        initialAdmin = null,
        mfaRequiredRoles = setOf("USER", "ADMIN"),
        oidc = null,
        secureCookies = false,
        webAuthnRpId = "localhost",
        webAuthnOrigin = "http://localhost:8080",
        sessionIdleMs = 24 * 60 * 60_000L,
        sessionAbsoluteMs = 30L * 24 * 60 * 60_000L,
    )
}
