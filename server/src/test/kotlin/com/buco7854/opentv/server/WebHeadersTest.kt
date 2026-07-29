package com.buco7854.opentv.server

import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebHeadersTest {
    private fun ApplicationTestBuilder.webApplication(publicUrl: String) = application {
        install(ContentNegotiation) { json(Json { encodeDefaults = true }) }
        installOpenTvSecurityHeaders { URI(publicUrl).scheme == "https" }
        routing {
            get("/api/v1/probe") { call.respond(HealthDto("ok")) }
            webClient(TEST_WEB_PACKAGE)
        }
    }

    private fun HttpResponse.header(name: String) = headers[name]

    @Test
    fun `every response carries the browser security headers`() = testApplication {
        webApplication("https://tv.example.com")

        listOf("/", "/browse/7", "/assets/app-A1b2C3d4.js", "/api/v1/probe").forEach { path ->
            val response = client.get(path)
            assertEquals(CONTENT_SECURITY_POLICY, response.header(CONTENT_SECURITY_POLICY_HEADER), path)
            assertEquals("DENY", response.header(FRAME_OPTIONS_HEADER), path)
            assertEquals("nosniff", response.header(CONTENT_TYPE_OPTIONS_HEADER), path)
            assertEquals("no-referrer", response.header(REFERRER_POLICY_HEADER), path)
            assertEquals("same-origin", response.header(OPENER_POLICY_HEADER), path)
        }
    }

    @Test
    fun `the policy allows what the player and the QR code need`() {
        val directives = CONTENT_SECURITY_POLICY.split("; ").associate {
            it.substringBefore(' ') to it.substringAfter(' ', "")
        }

        assertEquals("'none'", directives["frame-ancestors"])
        assertEquals("'none'", directives["object-src"])
        assertEquals("'self'", directives["base-uri"])
        assertEquals("'self'", directives["default-src"])
        assertEquals("'self'", directives["script-src"])
        assertEquals("'self'", directives["script-src-elem"])
        assertEquals("'none'", directives["script-src-attr"])
        assertEquals("'none'", directives["frame-src"])
        assertEquals("'self'", directives["style-src"])
        assertEquals("'self'", directives["connect-src"])
        assertTrue("blob:" in directives.getValue("worker-src"))
        assertTrue("blob:" in directives.getValue("media-src"))
        assertTrue("blob:" in directives.getValue("img-src"))
        assertTrue("data:" in directives.getValue("img-src"))
    }

    @Test
    fun `transport security is only promised to a browser that arrived over https`() {
        val secure = browserSecurityHeaders(secure = true).toMap()
        val plain = browserSecurityHeaders(secure = false).toMap()

        assertEquals(
            "max-age=$HSTS_MAX_AGE_SECONDS; includeSubDomains",
            secure[HttpHeaders.StrictTransportSecurity],
        )
        assertNull(plain[HttpHeaders.StrictTransportSecurity])
        assertEquals(CONTENT_SECURITY_POLICY, plain[CONTENT_SECURITY_POLICY_HEADER])
    }

    @Test
    fun `hashed assets are immutable and the entry document is revalidated`() = testApplication {
        webApplication("https://tv.example.com")

        assertEquals(
            IMMUTABLE_CACHE_CONTROL,
            client.get("/assets/app-A1b2C3d4.js").header(HttpHeaders.CacheControl),
        )
        listOf("/", "/index.html", "/browse/7").forEach { path ->
            assertEquals(
                REVALIDATED_CACHE_CONTROL,
                client.get(path).header(HttpHeaders.CacheControl),
                path,
            )
        }
    }

    @Test
    fun `caching headers never reach an api route`() = testApplication {
        webApplication("https://tv.example.com")

        assertNull(client.get("/api/v1/probe").header(HttpHeaders.CacheControl))
    }

    @Test
    fun `cache policy classifies content-hashed paths only`() {
        listOf(
            "/assets/index-B1cD2eF3.js",
            "/assets/index-B1cD2eF3.css",
            "jar:file:/opt/opentv.jar!/web/assets/PlayerScreen-a_b-1234.js",
        ).forEach { assertEquals(IMMUTABLE_CACHE_CONTROL, webCacheControl(it), it) }

        listOf(
            "/index.html",
            "/icon.svg",
            "/assets/logo.svg",
            "jar:file:/opt/opentv.jar!/web/index.html",
        ).forEach { assertEquals(REVALIDATED_CACHE_CONTROL, webCacheControl(it), it) }
    }

    @Test
    fun `compression stays an allowlist of text responses`() {
        listOf(
            ContentType.Text.Html,
            ContentType.Text.CSS,
            ContentType.Text.Plain,
            ContentType.parse("text/vtt"),
            ContentType.Application.Json,
            ContentType.Application.JavaScript,
            ContentType.Image.SVG,
        ).forEach { assertTrue(isCompressible(it), "$it should be compressed") }

        listOf(
            ContentType.Application.OctetStream,
            ContentType.Video.MP4,
            ContentType.Video.MPEG,
            ContentType.Audio.MPEG,
            ContentType.Image.PNG,
            ContentType.parse("application/vnd.apple.mpegurl"),
            ContentType.parse("video/mp2t"),
        ).forEach { assertFalse(isCompressible(it), "$it must be streamed untouched") }
    }
}

internal const val TEST_WEB_PACKAGE = "webclient"
