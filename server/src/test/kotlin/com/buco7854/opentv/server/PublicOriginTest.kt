package com.buco7854.opentv.server

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the server believes its own address to be, per request. The resolver needs a real
 * call to read headers from, so each case goes through a route that reports its answer.
 */
class PublicOriginTest {

    @Test
    fun `a configured public url answers every request`() = withResolver(pinned = true) {
        // A proxied deployment gets one predictable identity: the OIDC callback is
        // registered at the provider and a passkey belongs to a single relying party.
        assertEquals(
            "https://tv.example.com secure=true rp=tv.example.com usable=true",
            probe(host = "192.168.1.10:8080"),
        )
    }

    @Test
    fun `an unset public url follows the host the request was addressed to`() =
        withResolver(pinned = false) {
            assertEquals(
                "http://192.168.1.10:8080 secure=false rp=192.168.1.10 usable=false",
                probe(host = "192.168.1.10:8080"),
            )
            assertEquals(
                "http://opentv.local:8080 secure=false rp=opentv.local usable=false",
                probe(host = "opentv.local:8080"),
            )
            // Localhost is a secure context even over plain HTTP, so passkeys work there.
            assertEquals(
                "http://localhost:8080 secure=false rp=localhost usable=true",
                probe(host = "localhost:8080"),
            )
        }

    @Test
    fun `forwarded headers are read only from a trusted proxy`() {
        withResolver(pinned = false, trusted = true) {
            assertEquals(
                "https://tv.example.com secure=true rp=tv.example.com usable=true",
                probe(host = "10.0.0.5:8080", proto = "https", forwardedHost = "tv.example.com"),
            )
        }
        // The test client is the peer, and it is not a configured proxy here: an untrusted
        // hop must not be able to talk the server into minting addresses for another host.
        withResolver(pinned = false) {
            assertEquals(
                "http://10.0.0.5:8080 secure=false rp=10.0.0.5 usable=false",
                probe(host = "10.0.0.5:8080", proto = "https", forwardedHost = "evil.example"),
            )
        }
    }

    @Test
    fun `an unusable host falls back to the configured address`() = withResolver(pinned = false) {
        assertEquals(
            "http://localhost:8080 secure=false rp=localhost usable=true",
            probe(host = "not a host"),
        )
    }

    @Test
    fun `a pinned relying party survives an unset public url`() {
        val config = authConfig(pinned = false).copy(
            webAuthnRpId = "tv.example.com",
            webAuthnOrigin = "https://tv.example.com",
            webAuthnPinned = true,
        )
        withResolver(config) {
            assertEquals(
                "http://192.168.1.10:8080 secure=false rp=tv.example.com usable=true",
                probe(host = "192.168.1.10:8080"),
            )
        }
    }

    private suspend fun ApplicationTestBuilder.probe(
        host: String,
        proto: String? = null,
        forwardedHost: String? = null,
    ): String = client.get("/probe") {
        header(HttpHeaders.Host, host)
        proto?.let { header(HttpHeaders.XForwardedProto, it) }
        forwardedHost?.let { header(HttpHeaders.XForwardedHost, it) }
    }.bodyAsText()

    private fun withResolver(
        pinned: Boolean,
        trusted: Boolean = false,
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = withResolver(authConfig(pinned), trusted, block)

    private fun withResolver(
        config: AuthConfig,
        trusted: Boolean = false,
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        application {
            val origins = PublicOrigin(config) { trusted }
            routing {
                get("/probe") {
                    val webAuthn = origins.webAuthn(call)
                    call.respondText(
                        "${origins.of(call)} secure=${origins.secure(call)} " +
                            "rp=${webAuthn.rpId} usable=${webAuthn.usable}",
                    )
                }
            }
        }
        block()
    }

    private fun authConfig(pinned: Boolean) = AuthConfig(
        publicUrl = URI(if (pinned) "https://tv.example.com" else "http://localhost:8080"),
        publicUrlPinned = pinned,
        passwordEnabled = true,
        encryptionKey = ByteArray(32) { it.toByte() },
        initialAdmin = null,
        mfaRequiredRoles = emptySet(),
        oidc = null,
        secureCookies = pinned,
        webAuthnRpId = if (pinned) "tv.example.com" else "localhost",
        webAuthnOrigin = if (pinned) "https://tv.example.com" else "http://localhost:8080",
        webAuthnPinned = pinned,
        sessionIdleMs = 24 * 60 * 60_000L,
        sessionAbsoluteMs = 30L * 24 * 60 * 60_000L,
    )
}
