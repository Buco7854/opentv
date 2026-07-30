package com.buco7854.opentv.server

import com.buco7854.opentv.serverdata.createOpenTvServerDatabase
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OidcServiceTest {
    @Test
    fun authorizationCodeFlowBindsTransactionNonceAndConsumesState() = runTest {
        val key = RSAKeyGenerator(2048).keyID("test-key").generate()
        val untrustedKey = RSAKeyGenerator(2048).keyID("test-key").generate()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val issuer = "http://127.0.0.1:${server.address.port}"
        var nonce = ""
        var tokenMode = TokenMode.VALID
        server.createContext("/.well-known/openid-configuration") { exchange ->
            exchange.json(
                """
                {
                  "issuer":"$issuer",
                  "authorization_endpoint":"$issuer/authorize",
                  "token_endpoint":"$issuer/token",
                  "jwks_uri":"$issuer/jwks",
                  "response_types_supported":["code"],
                  "subject_types_supported":["public"],
                  "id_token_signing_alg_values_supported":["RS256"]
                }
                """.trimIndent(),
            )
        }
        server.createContext("/jwks") { exchange ->
            exchange.json(JWKSet(key.toPublicJWK()).toString())
        }
        server.createContext("/token") { exchange ->
            val now = System.currentTimeMillis()
            val claims = JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject("subject-123")
                .audience(if (tokenMode == TokenMode.WRONG_AUDIENCE) "another-client" else "opentv-test")
                .issueTime(Date(now))
                .expirationTime(Date(if (tokenMode == TokenMode.EXPIRED) now - 60_000 else now + 60_000))
                .claim("nonce", if (tokenMode == TokenMode.WRONG_NONCE) "wrong-nonce" else nonce)
                .claim("preferred_username", "oidc-admin")
                .claim("name", "OIDC Administrator")
                .claim("groups", listOf("opentv-admins"))
                .build()
            val token = SignedJWT(
                JWSHeader.Builder(JWSAlgorithm.RS256)
                    .keyID(key.keyID)
                    .type(JOSEObjectType.JWT)
                    .build(),
                claims,
            ).also {
                it.sign(RSASSASigner(if (tokenMode == TokenMode.BAD_SIGNATURE) untrustedKey else key))
            }.serialize()
            exchange.json(
                """{"access_token":"discarded","token_type":"Bearer","id_token":"$token"}""",
            )
        }
        server.start()

        val dir = Files.createTempDirectory("opentv-oidc-test")
        val db = createOpenTvServerDatabase(dir.resolve("opentv.db").toString())
        try {
            val config = oidcConfig(URI(issuer))
            val auth = AuthService(db, config, dir)
            auth.initialize()
            val oidc = OidcService(auth, config)

            val rejected = oidc.start("127.0.0.1")
            val rejectedQuery = query(rejected.authorizationUrl)
            assertFailsWith<InvalidChallengeException> {
                oidc.callback(
                    code = "authorization-code",
                    state = rejectedQuery.getValue("state"),
                    providerError = null,
                    transactionToken = "wrong-cookie",
                )
            }

            // The flow is started on the address the browser used, and the token exchange
            // has to repeat that exact redirect URI - so it travels with the flow.
            val callback = URI("https://tv.example.com/api/v1/auth/oidc/callback")
            val handoff = "browser-handoff-correlation-value-1"
            val start = oidc.start("127.0.0.1", callback, handoff)
            val query = query(start.authorizationUrl)
            nonce = query.getValue("nonce")
            assertEquals(callback.toString(), query["redirect_uri"])
            assertEquals("S256", query["code_challenge_method"])
            assertTrue(query.getValue("code_challenge").isNotBlank())

            val result = oidc.callback(
                code = "authorization-code",
                state = query.getValue("state"),
                providerError = null,
                transactionToken = start.transactionToken,
            )

            assertEquals("AUTHENTICATED", result.flow.status)
            assertEquals(handoff, result.oidcHandoff)
            val user = assertNotNull(result.flow.user)
            assertEquals("ADMIN", user.role)
            assertEquals("oidc-admin", user.username)
            assertNotNull(result.sessionToken)
            assertFailsWith<InvalidChallengeException> {
                oidc.callback(
                    code = "authorization-code",
                    state = query.getValue("state"),
                    providerError = null,
                    transactionToken = start.transactionToken,
                )
            }

            for (invalidMode in listOf(
                TokenMode.WRONG_NONCE,
                TokenMode.WRONG_AUDIENCE,
                TokenMode.EXPIRED,
                TokenMode.BAD_SIGNATURE,
            )) {
                val invalidStart = oidc.start("127.0.0.1")
                val invalidQuery = query(invalidStart.authorizationUrl)
                nonce = invalidQuery.getValue("nonce")
                tokenMode = invalidMode
                assertFailsWith<InvalidCredentialsException>(invalidMode.name) {
                    oidc.callback(
                        code = "authorization-code",
                        state = invalidQuery.getValue("state"),
                        providerError = null,
                        transactionToken = invalidStart.transactionToken,
                    )
                }
            }
            repeat(4) { oidc.start("127.0.0.1") }
            assertFailsWith<AuthRateLimitedException> {
                oidc.start("127.0.0.1")
            }
        } finally {
            db.close()
            dir.toFile().deleteRecursively()
            server.stop(0)
        }
    }

    private fun oidcConfig(issuer: URI) = AuthConfig(
        publicUrl = URI("http://localhost:8080"),
        passwordEnabled = false,
        encryptionKey = ByteArray(32),
        initialAdmin = null,
        mfaRequiredRoles = setOf("USER", "ADMIN"),
        oidc = OidcConfig(
            issuer = issuer,
            clientId = "opentv-test",
            clientSecret = "client-secret",
            scopes = listOf("openid", "profile"),
            usernameClaim = "preferred_username",
            displayNameClaim = "name",
            groupsClaim = "groups",
            adminGroups = setOf("opentv-admins"),
            autoProvision = true,
        ),
        secureCookies = false,
        webAuthnRpId = "localhost",
        webAuthnOrigin = "http://localhost:8080",
        sessionIdleMs = 24 * 60 * 60_000L,
        sessionAbsoluteMs = 30L * 24 * 60 * 60_000L,
    )

    private fun query(url: String): Map<String, String> =
        URI(url).rawQuery.split('&').associate { entry ->
            val parts = entry.split('=', limit = 2)
            URLDecoder.decode(parts[0], StandardCharsets.UTF_8) to
                URLDecoder.decode(parts.getOrElse(1) { "" }, StandardCharsets.UTF_8)
        }

    private fun HttpExchange.json(body: String) {
        val bytes = body.toByteArray()
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private enum class TokenMode {
        VALID,
        WRONG_NONCE,
        WRONG_AUDIENCE,
        EXPIRED,
        BAD_SIGNATURE,
    }
}
