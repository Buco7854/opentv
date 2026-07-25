package com.buco7854.opentv.server

import com.buco7854.opentv.serverdata.createServerUserDatabase
import java.nio.file.Files
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.test.runTest
import java.net.URI
import kotlin.test.assertFailsWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class ApiSecurityTest {
    private val request = ApiRequestCredentials(
        authorization = null,
        cookie = null,
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
    fun `route boundary stops unauthenticated requests before the handler`() = testApplication {
        application {
            install(StatusPages) {
                exception<UnauthenticatedApiException> { call, _ ->
                    call.respond(HttpStatusCode.Unauthorized)
                }
            }
            routing {
                route("/api/v1") {
                    apiSecurityBoundary(
                        ApiSecurity(ApiAuthenticator { null }),
                        clientIp = { "127.0.0.1" },
                    )
                    get("/probe") { error("protected handler must not run") }
                }
            }
        }

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/probe").status)
    }

    @Test
    fun authenticatedGuardRequiresMatchingOriginAndCsrfTokenOnUnsafeRequests() = runTest {
        val dir = Files.createTempDirectory("opentv-api-security")
        val db = createServerUserDatabase(dir.resolve("users.db").toString())
        try {
            val config = authConfig()
            val auth = AuthService(db, config, dir)
            auth.initialize()
            val token = Files.readString(dir.resolve("bootstrap.token")).trim()
            val created = auth.bootstrap(
                BootstrapRequestDto(token, "Admin", "a sufficiently long password", "Administrator"),
                "127.0.0.1",
            )
            val actor = requireNotNull(
                auth.authenticate(requireNotNull(created.sessionToken)),
            ).first
            val csrf = auth.csrfToken(actor)
            val principal = ApiPrincipal(
                subject = actor.userId,
                roles = actor.roles,
                authSessionId = actor.authSessionId,
                username = actor.username,
                authMethod = actor.authMethod,
            )
            val guard = ApiSecurity.authenticated(auth, config)
            fun request(
                method: String,
                origin: String? = "https://tv.example.com",
                csrfToken: String? = csrf,
                path: String = "/api/v1/playlists",
            ) = ApiRequestCredentials(
                authorization = null, cookie = null, method = method, path = path,
                clientIp = "127.0.0.1", csrfToken = csrfToken, origin = origin,
            )

            guard.validate(principal, request("GET", origin = null, csrfToken = null))
            guard.validate(principal, request("POST"))
            assertFailsWith<CsrfException> {
                guard.validate(principal, request("POST", csrfToken = null))
            }
            assertFailsWith<CsrfException> {
                guard.validate(principal, request("POST", csrfToken = "wrong-token"))
            }
            assertFailsWith<CsrfException> {
                guard.validate(principal, request("POST", origin = null))
            }
            assertFailsWith<CsrfException> {
                guard.validate(principal, request("POST", origin = "https://evil.example"))
            }
            assertFailsWith<CsrfException> {
                guard.validate(
                    principal,
                    request(
                        "GET",
                        origin = "https://evil.example",
                        csrfToken = null,
                        path = "/api/v1/playback/lease/ws",
                    ),
                )
            }
        } finally {
            db.close()
            dir.toFile().deleteRecursively()
        }
    }

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
