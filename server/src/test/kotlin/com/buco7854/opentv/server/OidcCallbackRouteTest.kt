package com.buco7854.opentv.server

import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The single-use OIDC transaction cookie is cleared on the way out of every callback.
 *
 * It used to be cleared in a `finally`, which runs after the redirect has already completed
 * the response. Netty refuses a header at that point, so every finished sign-in ended in an
 * `UnsupportedOperationException` and left the cookie in the browser.
 *
 * This pins the contract, not that crash: Ktor's test engine accepts a header set after the
 * response, so it stayed green while production threw. The guard against a repeat is that
 * every cookie is appended before the handler responds.
 */
class OidcCallbackRouteTest {

    @Test
    fun `a rejected callback still clears the transaction cookie`() = withPublicAuthServer {
        val response = client.config { followRedirects = false }
            .get("/api/v1/auth/oidc/callback")

        assertEquals(HttpStatusCode.Found, response.status)
        assertEquals("/login?auth=oidc_error", response.headers[HttpHeaders.Location])
        assertTrue(response.expiresTransactionCookie(), response.setCookies().toString())
    }

    private fun HttpResponse.setCookies() = headers.getAll(HttpHeaders.SetCookie).orEmpty()

    private fun HttpResponse.expiresTransactionCookie() = setCookies().any {
        it.startsWith("$OIDC_TRANSACTION_COOKIE=") && "Max-Age=0" in it
    }
}
