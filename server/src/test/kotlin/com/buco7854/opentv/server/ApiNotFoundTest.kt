package com.buco7854.opentv.server

import com.buco7854.opentv.contract.*
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.decodeFromString

class ApiNotFoundTest {
    private fun ApplicationTestBuilder.routes(terminateApiPrefix: Boolean) = application {
        install(ContentNegotiation) { json(Json { encodeDefaults = true }) }
        routing {
            route("/api/v1") {
                get("/health") { call.respond(HealthDto("ok")) }
                if (terminateApiPrefix) unknownApiPaths()
            }
            webClient(TEST_WEB_PACKAGE)
        }
    }

    @Test
    fun `without a terminating route an unknown api path is answered by the spa`() = testApplication {
        routes(terminateApiPrefix = false)

        val response = client.get("/api/v1/unknown")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Text.Html, response.contentType()?.withoutParameters())
        assertTrue("<div id=\"root\">" in response.bodyAsText())
    }

    @Test
    fun `an unknown api path is a json 404 and the spa still serves its own routes`() = testApplication {
        routes(terminateApiPrefix = true)

        val missing = client.get("/api/v1/unknown")
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertEquals(ContentType.Application.Json, missing.contentType()?.withoutParameters())
        assertTrue("\"code\":\"not_found\"" in missing.bodyAsText())

        assertEquals(HttpStatusCode.NotFound, client.post("/api/v1/unknown").status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/health").status)

        val document = client.get("/browse/7")
        assertEquals(HttpStatusCode.OK, document.status)
        assertTrue("<div id=\"root\">" in document.bodyAsText())
    }

    @Test
    fun `server info is public and exposes only identity and protocol version`() = testApplication {
        application {
            install(ContentNegotiation) { json(Json { encodeDefaults = true }) }
            routing {
                route("/api/v1") {
                    serverInfoRoutes("test-version")
                    apiSecurityBoundary(
                        ApiSecurity(ApiAuthenticator { null }),
                        clientIp = { "127.0.0.1" },
                    ) {
                        get("/protected") { error("must not run") }
                    }
                    unknownApiPaths()
                }
            }
        }

        val response = client.get("/api/v1/server-info")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            ServerInfoDto("opentv", 1, "test-version"),
            Json.decodeFromString<ServerInfoDto>(response.bodyAsText()),
        )
    }
}
