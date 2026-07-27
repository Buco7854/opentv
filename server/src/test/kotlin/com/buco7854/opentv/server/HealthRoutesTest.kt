package com.buco7854.opentv.server

import io.ktor.client.statement.bodyAsText
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertTrue

class HealthRoutesTest {
    @Test
    fun healthDoesNotRequireProviderAccess() = testApplication {
        application {
            install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) {
                json(Json { encodeDefaults = true })
            }
            routing { healthRoutes { true } }
        }
        assertTrue("\"status\":\"ok\"" in client.get("/health/live").bodyAsText())
        val ready = client.get("/health/ready").bodyAsText()
        assertTrue("\"status\":\"ready\"" in ready)
        assertTrue("\"ffmpegAvailable\":true" in ready)
    }
}
