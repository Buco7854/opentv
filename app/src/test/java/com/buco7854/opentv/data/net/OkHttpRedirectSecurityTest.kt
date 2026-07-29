package com.buco7854.opentv.data.net

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OkHttpRedirectSecurityTest {

    @Test
    fun crossOriginRedirectStripsAuthorization() {
        val hub = MockWebServer()
        val otherOrigin = MockWebServer()
        try {
            otherOrigin.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
            hub.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .addHeader("Location", otherOrigin.url("/capture")),
            )

            OkHttpClient().newCall(
                Request.Builder()
                    .url(hub.url("/api/v1/auth/me"))
                    .header("Authorization", "Bearer native-session")
                    .build(),
            ).execute().use { response ->
                assertEquals(200, response.code)
            }

            assertEquals("Bearer native-session", hub.takeRequest().headers["Authorization"])
            assertNull(otherOrigin.takeRequest().headers["Authorization"])
        } finally {
            hub.close()
            otherOrigin.close()
        }
    }
}
