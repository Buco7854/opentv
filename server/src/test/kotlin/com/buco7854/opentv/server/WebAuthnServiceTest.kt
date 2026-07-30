package com.buco7854.opentv.server

import com.buco7854.opentv.contract.*
import com.buco7854.opentv.serverdata.AuthMethod
import com.buco7854.opentv.serverdata.createOpenTvServerDatabase
import com.buco7854.opentv.serverdata.db.WebAuthnCredentialRow
import com.webauthn4j.credential.CredentialRecord
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import java.lang.reflect.Proxy
import java.net.URI
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WebAuthnServiceTest {
    @Test
    fun registrationPinsServerPropertiesAndRateLimitsMalformedResponses() = runTest {
        val dir = Files.createTempDirectory("opentv-webauthn-test")
        val db = createOpenTvServerDatabase(dir.resolve("opentv.db").toString())
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
            assertEquals("preferred", options.authenticatorSelection?.residentKey)
            assertEquals("preferred", options.authenticatorSelection?.userVerification)
            assertTrue(options.challenge.isNotBlank())
            val verificationParameters =
                webAuthnRegistrationParameters(config.pinnedRelyingParty(), options.challenge)
            val verifiedAlgorithms = requireNotNull(verificationParameters.pubKeyCredParams)
                .map { it.alg.value.toInt() }
            assertEquals(options.pubKeyCredParams.map { it.alg }, verifiedAlgorithms)
            assertFalse(verificationParameters.isUserVerificationRequired)
            assertTrue(verificationParameters.isUserPresenceRequired)
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

    @Test
    fun loginOptionsAreDiscoverableAndFloodGuarded() = runTest {
        val dir = Files.createTempDirectory("opentv-webauthn-login-options-test")
        val db = createOpenTvServerDatabase(dir.resolve("opentv.db").toString())
        try {
            val config = config()
            val auth = AuthService(db, config, dir)
            auth.initialize()
            val webAuthn = WebAuthnService(db, auth, config)

            val options = webAuthn.loginOptions(
                WebAuthnLoginOptionsRequestDto(),
                "203.0.113.20",
            )

            assertTrue(options.allowCredentials.isEmpty())
            assertEquals("required", options.userVerification)
            assertEquals("localhost", options.rpId)
            assertTrue(options.serverChallenge.isNotBlank())
            repeat(9) {
                webAuthn.loginOptions(
                    WebAuthnLoginOptionsRequestDto(),
                    "203.0.113.20",
                )
            }
            assertFailsWith<AuthRateLimitedException> {
                webAuthn.loginOptions(
                    WebAuthnLoginOptionsRequestDto(),
                    "203.0.113.20",
                )
            }
        } finally {
            db.close()
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun authenticationParametersBindCredentialAndVerificationFlagsInLibraryOrder() {
        val credentialId = byteArrayOf(4, 8, 15, 16, 23, 42)
        val challenge = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(32) { it.toByte() })
        val record = Proxy.newProxyInstance(
            CredentialRecord::class.java.classLoader,
            arrayOf(CredentialRecord::class.java),
        ) { _, _, _ -> null } as CredentialRecord

        val primary = webAuthnAuthenticationParameters(
            config().pinnedRelyingParty(),
            challenge,
            record,
            credentialId,
            userVerificationRequired = true,
        )
        val secondFactor = webAuthnAuthenticationParameters(
            config().pinnedRelyingParty(),
            challenge,
            record,
            credentialId,
            userVerificationRequired = false,
        )

        assertContentEquals(
            credentialId,
            requireNotNull(primary.allowCredentials).single(),
        )
        assertTrue(primary.isUserVerificationRequired)
        assertTrue(primary.isUserPresenceRequired)
        assertFalse(secondFactor.isUserVerificationRequired)
        assertTrue(secondFactor.isUserPresenceRequired)
    }

    @Test
    fun loginIsSingleUseAndRequiresUserVerificationOnlyOnThePrimaryPath() = runTest {
        val dir = Files.createTempDirectory("opentv-webauthn-login-test")
        val db = createOpenTvServerDatabase(dir.resolve("opentv.db").toString())
        var now = 1_700_000_000_000L
        try {
            val config = config()
            val auth = AuthService(db, config, dir, { now })
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
            val user = assertNotNull(db.users().byNormalizedUsername("admin"))
            val credentialId = byteArrayOf(4, 8, 15, 16, 23, 42)
            db.credentials().upsertWebAuthn(
                WebAuthnCredentialRow(
                    credentialId = credentialId,
                    userId = user.id,
                    publicKeyCose = byteArrayOf(1),
                    signCount = 0,
                    transportsJson = "[]",
                    backupEligible = true,
                    backedUp = true,
                    label = "Test passkey",
                    createdAtMs = now,
                    lastUsedAtMs = null,
                ),
            )
            val requirements = mutableListOf<Boolean>()
            val verifier = object : WebAuthnAssertionVerifier {
                override suspend fun verify(
                    credentialJson: String,
                    browserChallenge: String,
                    userVerificationRequired: Boolean,
                    credentialLookup: suspend (ByteArray) -> WebAuthnCredentialRow?,
                ): VerifiedWebAuthnAssertion {
                    requirements += userVerificationRequired
                    val row = assertNotNull(credentialLookup(credentialId))
                    return VerifiedWebAuthnAssertion(
                        credential = row,
                        userHandle = row.userId.toByteArray(Charsets.UTF_8),
                        signCount = row.signCount + 1,
                        backupEligible = true,
                        backedUp = true,
                    )
                }
            }
            val webAuthn = WebAuthnService(db, auth, config, verifier, { now })
            val loginOptions = webAuthn.loginOptions(
                WebAuthnLoginOptionsRequestDto(),
                "127.0.0.1",
            )
            val loginRequest = WebAuthnLoginCompleteRequestDto(
                loginOptions.serverChallenge,
                """{"test":true}""",
            )

            val login = webAuthn.completeLogin(loginRequest, "127.0.0.1")

            assertEquals("AUTHENTICATED", login.flow.status)
            assertEquals(AuthMethod.WEBAUTHN, login.flow.user?.authMethod)
            assertNotNull(auth.authenticate(assertNotNull(login.sessionToken)))
            assertFailsWith<InvalidChallengeException> {
                webAuthn.completeLogin(loginRequest, "127.0.0.1")
            }

            val secondFactorOptions = webAuthn.authenticationOptions(
                WebAuthnOptionsRequestDto(assertNotNull(bootstrap.flow.challenge)),
                "198.51.100.2",
            )
            val secondFactor = webAuthn.completeAuthentication(
                WebAuthnCompleteRequestDto(
                    secondFactorOptions.serverChallenge,
                    """{"test":true}""",
                ),
                "198.51.100.2",
            )

            assertEquals(AuthMethod.PASSWORD, secondFactor.flow.user?.authMethod)
            assertEquals(listOf(true, false), requirements)
        } finally {
            db.close()
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun concurrentPrimaryAssertionsCannotMoveTheSignatureCounterBackwards() = runTest {
        val dir = Files.createTempDirectory("opentv-webauthn-counter-race-test")
        val db = createOpenTvServerDatabase(dir.resolve("opentv.db").toString())
        val now = 1_700_000_000_000L
        try {
            val config = config()
            val auth = AuthService(db, config, dir, { now })
            auth.initialize()
            val bootstrapToken = Files.readString(dir.resolve("bootstrap.token")).trim()
            auth.bootstrap(
                BootstrapRequestDto(
                    bootstrapToken,
                    "Admin",
                    "a sufficiently long password",
                    "Administrator",
                ),
                "127.0.0.1",
            )
            val user = assertNotNull(db.users().byNormalizedUsername("admin"))
            val credentialId = byteArrayOf(4, 8, 15, 16, 23, 42)
            db.credentials().upsertWebAuthn(
                WebAuthnCredentialRow(
                    credentialId = credentialId,
                    userId = user.id,
                    publicKeyCose = byteArrayOf(1),
                    signCount = 10,
                    transportsJson = "[]",
                    backupEligible = true,
                    backedUp = true,
                    label = "Test passkey",
                    createdAtMs = now,
                    lastUsedAtMs = null,
                ),
            )
            val verifiedCount = AtomicInteger()
            val bothVerified = CompletableDeferred<Unit>()
            val newerCommitted = CompletableDeferred<Unit>()
            val verifier = object : WebAuthnAssertionVerifier {
                override suspend fun verify(
                    credentialJson: String,
                    browserChallenge: String,
                    userVerificationRequired: Boolean,
                    credentialLookup: suspend (ByteArray) -> WebAuthnCredentialRow?,
                ): VerifiedWebAuthnAssertion {
                    val snapshot = assertNotNull(credentialLookup(credentialId))
                    if (verifiedCount.incrementAndGet() == 2) bothVerified.complete(Unit)
                    bothVerified.await()
                    if (credentialJson == "older") newerCommitted.await()
                    return VerifiedWebAuthnAssertion(
                        credential = snapshot,
                        userHandle = snapshot.userId.toByteArray(Charsets.UTF_8),
                        signCount = if (credentialJson == "newer") 12 else 11,
                        backupEligible = true,
                        backedUp = true,
                    )
                }
            }
            val webAuthn = WebAuthnService(db, auth, config, verifier, { now })
            val newerOptions = webAuthn.loginOptions(
                WebAuthnLoginOptionsRequestDto(),
                "192.0.2.10",
            )
            val olderOptions = webAuthn.loginOptions(
                WebAuthnLoginOptionsRequestDto(),
                "192.0.2.11",
            )

            supervisorScope {
                val newer = async {
                    webAuthn.completeLogin(
                        WebAuthnLoginCompleteRequestDto(newerOptions.serverChallenge, "newer"),
                        "192.0.2.10",
                    ).also { newerCommitted.complete(Unit) }
                }
                val older = async {
                    webAuthn.completeLogin(
                        WebAuthnLoginCompleteRequestDto(olderOptions.serverChallenge, "older"),
                        "192.0.2.11",
                    )
                }

                assertEquals("AUTHENTICATED", newer.await().flow.status)
                assertFailsWith<InvalidCredentialsException> { older.await() }
            }
            assertEquals(12, assertNotNull(db.credentials().webAuthnById(credentialId)).signCount)
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
