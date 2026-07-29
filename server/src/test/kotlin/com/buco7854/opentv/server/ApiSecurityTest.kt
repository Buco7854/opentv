package com.buco7854.opentv.server

import com.buco7854.opentv.contract.*
import com.buco7854.opentv.serverdata.createServerUserDatabase
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.net.URI
import java.nio.file.Files
import java.util.Base64
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ApiSecurityTest {
    private val request = ApiRequestCredentials(
        authorization = null,
        method = "GET",
        path = "/api/v1/settings",
        clientIp = "127.0.0.1",
    )

    @Test
    fun `open access adapter supplies an explicit principal`() = runTest {
        val principal = assertNotNull(ApiSecurity.openAccess().authenticate(request))
        assertEquals("anonymous", principal.subject)
        assertEquals(setOf("user"), principal.roles)
    }

    @Test
    fun `access policy can reject an authenticated principal`() = runTest {
        val security = ApiSecurity(
            authenticator = ApiAuthenticator { ApiPrincipal("alice") },
            accessPolicy = ApiAccessPolicy { _, _ -> false },
        )

        val principal = assertNotNull(security.authenticate(request))
        assertFalse(security.isAllowed(principal, request))
    }

    @Test
    fun `route boundary stops unauthenticated requests and leaves public siblings reachable`() =
        testApplication {
            application {
                install(StatusPages) {
                    exception<UnauthenticatedApiException> { call, _ ->
                        call.respond(HttpStatusCode.Unauthorized)
                    }
                }
                routing {
                    route("/api/v1") {
                        get("/server-info") { call.respond(HttpStatusCode.OK) }
                        apiSecurityBoundary(
                            ApiSecurity(ApiAuthenticator { null }),
                            clientIp = { "127.0.0.1" },
                        ) {
                            get("/probe") { error("protected handler must not run") }
                        }
                    }
                }
            }

            assertEquals(HttpStatusCode.OK, client.get("/api/v1/server-info").status)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/probe").status)
        }

    @Test
    fun `route boundary rejects multiple authorization headers`() = testApplication {
        application {
            install(StatusPages) {
                exception<UnauthenticatedApiException> { call, _ ->
                    call.respond(HttpStatusCode.Unauthorized)
                }
            }
            routing {
                route("/api/v1") {
                    apiSecurityBoundary(
                        ApiSecurity(
                            ApiAuthenticator { credentials ->
                                credentials.authorization
                                    ?.takeIf { it == "Bearer accepted" }
                                    ?.let { ApiPrincipal("alice") }
                            },
                        ),
                        clientIp = { "127.0.0.1" },
                    ) {
                        get("/probe") { call.respond(HttpStatusCode.OK) }
                    }
                }
            }
        }

        val acceptedThenAttacker = client.get("/api/v1/probe") {
            headers {
                append(HttpHeaders.Authorization, "Bearer accepted")
                append(HttpHeaders.Authorization, "Bearer attacker-controlled")
            }
        }
        val attackerThenAccepted = client.get("/api/v1/probe") {
            headers {
                append(HttpHeaders.Authorization, "Bearer attacker-controlled")
                append(HttpHeaders.Authorization, "Bearer accepted")
            }
        }

        assertEquals(HttpStatusCode.Unauthorized, acceptedThenAttacker.status)
        assertEquals(HttpStatusCode.Unauthorized, attackerThenAccepted.status)
    }

    @Test
    fun `bearer is the only session transport and unsafe methods need no ambient guard`() = runTest {
        withAuthentication { auth, cipher, sessionToken, _ ->
            val security = ApiSecurity.authenticated(auth, cipher)
            val valid = request.copy(
                authorization = "bEaReR $sessionToken",
                method = "POST",
                path = "/api/v1/playlists",
            )

            assertNotNull(security.authenticate(valid))
            assertNotNull(
                security.authenticate(valid.copy(authorization = "  Bearer\t $sessionToken  ")),
            )
            assertNull(security.authenticate(valid.copy(authorization = null)))
            assertNull(security.authenticate(valid.copy(authorization = "Bearer")))
            assertNull(security.authenticate(valid.copy(authorization = "Bearer   ")))
            assertNull(security.authenticate(valid.copy(authorization = "Basic $sessionToken")))
            assertNull(
                security.authenticate(valid.copy(authorization = "Bearer $sessionToken extra")),
            )
            assertNull(security.authenticate(valid.copy(authorization = "Bearer invalid")))
            assertNull(
                security.authenticate(valid.copy(authorization = "Bearer ${"x".repeat(513)}")),
            )
        }
    }

    @Test
    fun `websocket upgrade authenticates with a short lived lease-bound capability`() = runTest {
        withAuthentication { auth, cipher, _, sessionId ->
            val security = ApiSecurity.authenticated(auth, cipher)
            val token = cipher.encryptWebSocket(sessionId, "lease-1").token
            val request = request.copy(
                path = "/api/v1/playback/lease-1/ws",
                webSocketToken = token,
            )

            assertNotNull(security.authenticate(request))
            assertNull(
                security.authenticate(
                    request.copy(path = "/api/v1/playback/another-lease/ws"),
                ),
            )
            assertNull(security.authenticate(request.copy(webSocketToken = "w.invalid")))
        }
    }

    private suspend fun withAuthentication(
        block: suspend (AuthService, StreamCipher, String, String) -> Unit,
    ) {
        val dir = Files.createTempDirectory("opentv-api-security")
        val db = createServerUserDatabase(dir.resolve("users.db").toString())
        try {
            val auth = AuthService(db, authConfig(), dir)
            auth.initialize()
            val bootstrap = Files.readString(dir.resolve("bootstrap.token")).trim()
            val created = auth.bootstrap(
                BootstrapRequestDto(
                    bootstrap,
                    "Admin",
                    "a sufficiently long password",
                    "Administrator",
                ),
                "127.0.0.1",
            )
            val sessionToken = requireNotNull(created.flow.sessionToken)
            val actor = requireNotNull(auth.authenticate(sessionToken)).first
            block(auth, StreamCipher(streamKey()), sessionToken, actor.authSessionId)
        } finally {
            db.close()
            dir.toFile().deleteRecursively()
        }
    }

    private fun streamKey() =
        Base64.getEncoder().encodeToString(ByteArray(32) { (it + 1).toByte() })

    private fun authConfig() = AuthConfig(
        publicUrl = URI("https://tv.example.com"),
        passwordEnabled = true,
        encryptionKey = ByteArray(32) { it.toByte() },
        initialAdmin = null,
        mfaRequiredRoles = emptySet(),
        oidc = null,
        secureCookies = true,
        webAuthnRpId = "tv.example.com",
        webAuthnOrigin = "https://tv.example.com",
        sessionIdleMs = 24 * 60 * 60_000L,
        sessionAbsoluteMs = 30L * 24 * 60 * 60_000L,
    )
}
