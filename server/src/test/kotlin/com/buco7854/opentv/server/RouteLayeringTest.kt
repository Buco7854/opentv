package com.buco7854.opentv.server

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RouteLayeringTest {
    @Test
    fun `feature routes do not access storage or core persistence models`() {
        val sourceDir = Path.of("src", "main", "kotlin", "com", "buco7854", "opentv", "server")
        Files.list(sourceDir).use { files ->
            files.filter { it.name.endsWith("Routes.kt") }.forEach { file ->
                val source = Files.readString(file)
                assertFalse(
                    "graph.storage" in source || "service.storage" in source ||
                        "graph.userDatabase" in source,
                    "${file.name} bypasses its application service",
                )
                assertFalse(
                    "import com.buco7854.opentv.core.storage" in source,
                    "${file.name} imports persistence abstractions",
                )
            }
        }
    }

    @Test
    fun `the api prefix is terminated before the single page application`() {
        val sourceDir = Path.of("src", "main", "kotlin", "com", "buco7854", "opentv", "server")
        val api = Files.readString(sourceDir.resolve("ApiRoutes.kt"))
        val composition = Files.readString(sourceDir.resolve("Application.kt"))

        assertTrue("unknownApiPaths()" in api, "ApiRoutes.kt leaves unknown API paths unhandled")
        val apiRegistration = composition.indexOf("api(graph, apiSecurity)")
        val spaRegistration = composition.indexOf("webClient()")
        assertTrue(apiRegistration >= 0 && spaRegistration >= 0, "routing composition changed shape")
        assertTrue(
            apiRegistration < spaRegistration,
            "the SPA fallback must not be reachable before the API prefix is resolved",
        )
    }

    @Test
    fun `application services stay independent from Ktor`() {
        val sourceDir = Path.of("src", "main", "kotlin", "com", "buco7854", "opentv", "server")
        Files.list(sourceDir).use { files ->
            files.filter { it.name.endsWith("ApplicationService.kt") }.forEach { file ->
                assertFalse(
                    "import io.ktor" in Files.readString(file),
                    "${file.name} depends on the HTTP framework",
                )
            }
        }
    }
}
