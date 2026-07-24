package com.buco7854.opentv.server

import io.ktor.http.CookieEncoding
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
        assertFalse(authConfig(secure = false).secureCookies)
        assertTrue(authConfig(secure = true).secureCookies)
    }

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
