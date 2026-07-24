package com.buco7854.opentv.server

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
}
