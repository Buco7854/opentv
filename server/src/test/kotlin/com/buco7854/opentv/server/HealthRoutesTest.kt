package com.buco7854.opentv.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class HealthRoutesTest {
    @Test
    fun healthDoesNotRequireProviderAccess() = testApplication {
        application {
            install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) {
                json(Json { encodeDefaults = true })
            }
            routing { healthRoutes { true } }
        }
        val live = client.get("/health/live")
        assertEquals(HttpStatusCode.OK, live.status)
        assertEquals(HealthDto(status = "ok"), Json.decodeFromString(live.bodyAsText()))

        val ready = client.get("/health/ready")
        assertEquals(HttpStatusCode.OK, ready.status)
        assertEquals(
            HealthDto(status = "ready", ffmpegAvailable = true),
            Json.decodeFromString(ready.bodyAsText()),
        )
    }
}
