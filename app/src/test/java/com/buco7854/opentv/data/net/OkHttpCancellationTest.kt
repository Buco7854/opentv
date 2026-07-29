package com.buco7854.opentv.data.net

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertTrue
import org.junit.Test

class OkHttpCancellationTest {

    @Test
    fun cancellationClosesACallWhileItsResponseBodyIsBlocked() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("late body")
                .setBodyDelay(30, TimeUnit.SECONDS),
        )
        server.start()
        try {
            val call = OkHttpClient.Builder()
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
                .newCall(Request.Builder().url(server.url("/slow")).build())
            val request = launch(Dispatchers.IO) {
                call.executeCancellable { response ->
                    response.body.string()
                }
            }
            checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))

            withTimeout(2_000) {
                request.cancelAndJoin()
            }

            assertTrue(request.isCancelled)
            assertTrue(call.isCanceled())
        } finally {
            server.shutdown()
        }
    }
}
