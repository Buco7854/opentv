package com.buco7854.opentv.server

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The first request a fresh server ever receives is a bootstrap POST, and it arrives on
 * whatever address the operator typed - rarely the default `OPENTV_PUBLIC_URL`.
 */
class PublicAuthOriginTest {

    @Test
    fun `the first administrator can be created from the address the browser used`() =
        withPublicAuthServer { token ->
            val response = client.post("/api/v1/auth/bootstrap") {
            header(HttpHeaders.Origin, "http://192.168.1.10:8080")
            header(HttpHeaders.Host, "192.168.1.10:8080")
            contentType(ContentType.Application.Json)
            setBody(bootstrapBody(token))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue("AUTHENTICATED" in response.bodyAsText(), response.bodyAsText())
    }

    @Test
    fun `a foreign origin is refused with an actionable code`() = withPublicAuthServer { token ->
        val response = client.post("/api/v1/auth/bootstrap") {
            header(HttpHeaders.Origin, "https://evil.example")
            header(HttpHeaders.Host, "192.168.1.10:8080")
            contentType(ContentType.Application.Json)
            setBody(bootstrapBody(token))
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertTrue("origin_rejected" in response.bodyAsText(), response.bodyAsText())
    }

    private fun bootstrapBody(token: String) = """
        {"token":"$token","username":"admin",
         "password":"a sufficiently long password","displayName":"Administrator"}
    """.trimIndent()
}
