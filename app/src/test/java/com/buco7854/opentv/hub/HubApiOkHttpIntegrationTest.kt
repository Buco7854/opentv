package com.buco7854.opentv.hub

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.buco7854.opentv.contract.SessionHeartbeatDto
import com.buco7854.opentv.data.net.Http
import com.buco7854.opentv.data.net.OkHttpTransport
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HubApiOkHttpIntegrationTest {
    private lateinit var server: MockWebServer
    private lateinit var api: HubApi
    private lateinit var baseUrl: String

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Http.init(context)
        server = MockWebServer()
        server.start()
        baseUrl = server.url("/").toString()
        api = HubApi(OkHttpTransport { "OpenTV-HubApi-E2E" })
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun getCarriesNativeHeadersAndPreservesEncodedQuery() = runBlocking {
        server.enqueue(jsonResponse(200, "{}"))

        val results = api.search(
            HubCredentials(baseUrl, "opaque native token"),
            playlistId = 7,
            query = "news & météo/HD",
        )

        assertTrue(results.live.isEmpty())
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/v1/playlists/7/search", request.requestUrl?.encodedPath)
        assertEquals("news & météo/HD", request.requestUrl?.queryParameter("q"))
        assertEquals("Bearer opaque native token", request.getHeader("Authorization"))
        assertEquals("native", request.getHeader("X-OpenTV-Client"))
        assertEquals("application/json", request.getHeader("Accept"))
    }

    @Test
    fun postCarriesExactJsonAndContentTypeAndDecodesConflictFlow() = runBlocking {
        server.enqueue(
            jsonResponse(
                409,
                """{"status":"MFA_REQUIRED","code":"challenge_required","challenge":"next-1","methods":["totp"]}""",
            ),
        )

        val flow = api.password(baseUrl, "bo", "séc ret")

        assertEquals("MFA_REQUIRED", flow.status)
        assertEquals("next-1", flow.challenge)
        assertEquals(listOf("totp"), flow.methods)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/auth/password", request.path)
        assertEquals(
            """{"username":"bo","password":"séc ret"}""",
            request.body.readUtf8(),
        )
        assertEquals("application/json", request.getHeader("Content-Type"))
    }

    @Test
    fun goneResponseMapsThroughTheRealTransportToHubGoneException() = runBlocking {
        server.enqueue(
            jsonResponse(
                410,
                """{"code":"playback_revoked","message":"Lease ended"}""",
            ),
        )

        val failure = runCatching {
            api.heartbeat(
                HubCredentials(baseUrl, "native-session"),
                "lease/one",
                SessionHeartbeatDto(id = "lease/one"),
            )
        }.exceptionOrNull()

        assertTrue(failure is HubGoneException)
        failure as HubGoneException
        assertEquals("playback_revoked", failure.code)
        assertEquals("Lease ended", failure.message)
        assertEquals("/api/v1/playback/lease%2Fone/heartbeat", server.takeRequest().path)
    }

    private fun jsonResponse(status: Int, body: String) =
        MockResponse()
            .setResponseCode(status)
            .setHeader("Content-Type", "application/json")
            .setBody(body)
}
