package com.buco7854.opentv.server

import io.ktor.http.CookieEncoding
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteReadChannel
import com.webauthn4j.data.client.Origin
import com.webauthn4j.data.client.challenge.DefaultChallenge
import java.net.URI
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class AuthBoundaryTest {
    @Test
    fun rateLimitAppliesToEverySuppliedDimensionAndCanBeClearedIndependently() {
        var now = 1_000L
        val limiter = AuthRateLimiter { now }

        repeat(5) {
            limiter.check("ip:203.0.113.5", "account:alice")
            limiter.fail("ip:203.0.113.5", "account:alice")
        }

        val blockedByIp = assertFailsWith<AuthRateLimitedException> {
            limiter.check("ip:203.0.113.5", "account:bob")
        }
        val blockedByAccount = assertFailsWith<AuthRateLimitedException> {
            limiter.check("ip:198.51.100.9", "account:alice")
        }
        assertEquals(now + 2_000L, blockedByIp.retryAtMs)
        assertEquals(now + 2_000L, blockedByAccount.retryAtMs)

        limiter.success("account:alice")
        limiter.check("ip:198.51.100.9", "account:alice")
        assertFailsWith<AuthRateLimitedException> {
            limiter.check("ip:203.0.113.5", "account:alice")
        }

        now += 2_000L
        limiter.check("ip:203.0.113.5", "account:alice")
    }

    @Test
    fun fixedWindowLimiterBoundsSuccessfulRequests() {
        var now = 1_000L
        val limiter = AuthRateLimiter { now }

        repeat(3) { limiter.consume("oidc-start:ip:test", 3, 60_000) }
        assertFailsWith<AuthRateLimitedException> {
            limiter.consume("oidc-start:ip:test", 3, 60_000)
        }
        now += 60_000
        limiter.consume("oidc-start:ip:test", 3, 60_000)
    }

    @Test
    fun oversizedRequestBodiesAreRejected() = testApplication {
        application {
            installOpenTvRequestBodyLimit()
            routing {
                post("/body") {
                    call.respondText(call.receiveText())
                }
            }
        }

        val response = client.post("/body") {
            setBody("x".repeat((MAX_REQUEST_BODY_BYTES + 1).toInt()))
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)

        val chunked = client.post("/body") {
            setBody(object : OutgoingContent.ReadChannelContent() {
                override fun readFrom() =
                    ByteReadChannel("x".repeat((MAX_REQUEST_BODY_BYTES + 1).toInt()))
            })
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, chunked.status)
    }

    @Test
    fun publicAuthBodiesUseTheSmallerRouteLimit() = testApplication {
        application {
            installOpenTvRequestBodyLimit()
            routing {
                post("/api/v1/auth/password") {
                    call.respondText(call.receiveText())
                }
                post("/regular") {
                    call.respondText(call.receiveText())
                }
            }
        }
        val body = "x".repeat((MAX_PUBLIC_AUTH_REQUEST_BODY_BYTES + 1).toInt())

        assertEquals(
            HttpStatusCode.PayloadTooLarge,
            client.post("/api/v1/auth/password") { setBody(body) }.status,
        )
        assertEquals(HttpStatusCode.OK, client.post("/regular") { setBody(body) }.status)
    }

    @Test
    fun forwardedAddressParsingAcceptsOnlyIpLiterals() {
        assertContentEquals(byteArrayOf(127, 0, 0, 1), parseIpLiteral("127.0.0.1"))
        assertEquals(16, parseIpLiteral("2001:db8::1")?.size)
        assertEquals(null, parseIpLiteral("localhost"))
        assertEquals(null, parseIpLiteral("attacker.example"))
        assertEquals(null, IpRange.parse("localhost"))
    }

    @Test
    fun browserSessionCookieHasTheRequiredSecurityAttributes() {
        val cookie = sessionCookie("opaque-token", authConfig(secure = true))

        assertEquals(SESSION_COOKIE, cookie.name)
        assertEquals("opaque-token", cookie.value)
        assertEquals("/", cookie.path)
        assertTrue(cookie.httpOnly)
        assertTrue(cookie.secure)
        assertEquals("Lax", cookie.extensions["SameSite"])
        assertEquals(CookieEncoding.RAW, cookie.encoding)
    }

    @Test
    fun localhostDevelopmentIsTheOnlyInsecureCookieConfiguration() {
        val insecure = AuthConfig.fromEnv(
            baseEnv() + mapOf("OPENTV_PUBLIC_URL" to "http://localhost:8080"),
        )
        assertFalse(insecure.secureCookies)

        val secure = AuthConfig.fromEnv(
            baseEnv() + mapOf("OPENTV_PUBLIC_URL" to "https://tv.example.com"),
        )
        assertTrue(secure.secureCookies)
    }

    private fun baseEnv() = mapOf(
        "OPENTV_AUTH_ENCRYPTION_KEY" to Base64.getEncoder().encodeToString(ByteArray(32)),
    )

    @Test
    fun oidcGroupClaimsAcceptOnlyBoundedTopLevelStrings() {
        assertContentEquals(emptyList(), parseOidcGroups(null))
        assertContentEquals(listOf("admins"), parseOidcGroups("admins"))
        assertContentEquals(listOf("admins", "viewers"), parseOidcGroups(listOf("admins", "viewers")))

        assertFailsWith<IllegalArgumentException> { parseOidcGroups(7) }
        assertFailsWith<IllegalArgumentException> { parseOidcGroups(listOf("admins", 7)) }
        assertFailsWith<IllegalArgumentException> { parseOidcGroups(List(101) { "group-$it" }) }
        assertFailsWith<IllegalArgumentException> { parseOidcGroups("x".repeat(257)) }
    }

    @Test
    fun oidcTransactionBindingRequiresTheExactShortLivedCookieToken() {
        val token = AuthCrypto.token()
        val expectedHash = AuthCrypto.hashToken(token).joinToString("") { "%02x".format(it) }

        assertTrue(oidcTransactionMatches(expectedHash, token))
        assertFalse(oidcTransactionMatches(expectedHash, null))
        assertFalse(oidcTransactionMatches(expectedHash, "$token-different"))
        assertFalse(oidcTransactionMatches(expectedHash, "x".repeat(513)))
    }

    @Test
    fun webAuthnVerificationPropertyPinsOriginRpAndChallenge() {
        val challenge = ByteArray(32) { it.toByte() }
        val config = authConfig(secure = true)
        val property = webAuthnServerProperty(
            config,
            Base64.getUrlEncoder().withoutPadding().encodeToString(challenge),
        )

        assertEquals("tv.example.test", property.rpId)
        assertTrue(property.originPredicate.test(Origin("https://tv.example.test")))
        assertFalse(property.originPredicate.test(Origin("https://evil.example.test")))
        assertContentEquals(challenge, (property.challenge as DefaultChallenge).value)
        assertFailsWith<IllegalArgumentException> {
            webAuthnServerProperty(config, "%%%")
        }
    }

    private fun authConfig(secure: Boolean) = AuthConfig(
        publicUrl = URI(if (secure) "https://tv.example.test" else "http://localhost:8080"),
        passwordEnabled = true,
        encryptionKey = ByteArray(32),
        initialAdmin = null,
        mfaRequiredRoles = setOf("USER", "ADMIN"),
        oidc = null,
        secureCookies = secure,
        webAuthnRpId = if (secure) "tv.example.test" else "localhost",
        webAuthnOrigin = if (secure) "https://tv.example.test" else "http://localhost:8080",
        sessionIdleMs = 24 * 60 * 60_000L,
        sessionAbsoluteMs = 30L * 24 * 60 * 60_000L,
    )
}
