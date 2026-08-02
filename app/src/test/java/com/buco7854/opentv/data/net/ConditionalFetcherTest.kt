package com.buco7854.opentv.data.net

import com.buco7854.opentv.core.net.ConditionalFetch
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConditionalFetcherTest {

    @Test
    fun providerTextUsesDeclaredCharsetOrTheSameStreamingUtf8Fallback() = runBlocking {
        data class Case(
            val name: String,
            val bytes: ByteArray,
            val contentType: String?,
            val expected: String,
        )

        val latin1Cafe = byteArrayOf(0x43, 0x61, 0x66, 0xE9.toByte())
        val utf8Cafe = byteArrayOf(0x43, 0x61, 0x66, 0xC3.toByte(), 0xA9.toByte())
        val truncatedAtBoundary = ByteArray(8 * 1_024) { 'a'.code.toByte() }
            .also { it[it.lastIndex] = 0xC3.toByte() }
        val splitAtBoundary = truncatedAtBoundary + 0xA9.toByte()
        val cases = listOf(
            Case("Latin-1 fallback", latin1Cafe, null, "Caf\u00e9"),
            Case("UTF-8", utf8Cafe, null, "Caf\u00e9"),
            Case(
                "UTF-8 BOM",
                byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + utf8Cafe,
                null,
                "Caf\u00e9",
            ),
            Case("declared Latin-1", latin1Cafe, "text/plain; charset=ISO-8859-1", "Caf\u00e9"),
            Case(
                "declaration wins over contradictory bytes",
                utf8Cafe,
                "text/plain; charset=ISO-8859-1",
                "Caf\u00c3\u00a9",
            ),
            Case(
                "UTF-8 code point split at reader buffer boundary",
                splitAtBoundary,
                null,
                "a".repeat(truncatedAtBoundary.lastIndex) + "\u00e9",
            ),
            Case(
                "truncated UTF-8 at reader buffer boundary",
                truncatedAtBoundary,
                null,
                "a".repeat(truncatedAtBoundary.lastIndex) + "\u00c3",
            ),
        )

        val server = MockWebServer()
        cases.forEach { case ->
            val response = MockResponse()
                .setResponseCode(200)
                .setBody(Buffer().write(case.bytes))
            case.contentType?.let { response.addHeader("Content-Type", it) }
            server.enqueue(response)
        }
        server.start()
        try {
            val fetcher = createConditionalFetcher(
                client = { OkHttpClient() },
                userAgent = { "OpenTV-Test" },
            )
            cases.forEachIndexed { index, case ->
                val fetched = fetcher.conditionalGet(
                    server.url("/feed-$index").toString(),
                    null,
                    null,
                ) as ConditionalFetch.Success
                val actual = fetched.body.readChars { chars ->
                    buildString {
                        while (true) {
                            val next = chars.nextChar()
                            if (next == -1) break
                            append(next.toChar())
                        }
                    }
                }
                assertEquals(case.name, case.expected, actual)
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun largePlaylistIsStreamedAndParsedOffTheCallerThread() = runBlocking {
        val server = MockWebServer()
        val body = Buffer()
            .writeUtf8("#EXTM3U\n")
            .write(ByteArray(512 * 1024) { 'x'.code.toByte() })
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(body)
                // A buffering implementation would need roughly 50 seconds.
                .throttleBody(1_024, 100, TimeUnit.MILLISECONDS),
        )
        server.start()
        val caller = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "refresh-main")
        }.asCoroutineDispatcher()
        try {
            val fetcher = createConditionalFetcher(
                client = { OkHttpClient() },
                userAgent = { "OpenTV-Test" },
            )
            val fetched = withContext(caller) {
                withTimeout(2_000) {
                    fetcher.conditionalGet(server.url("/large.m3u").toString(), null, null)
                }
            } as ConditionalFetch.Success

            var parsingThread = ""
            val first = withContext(caller) {
                withTimeout(2_000) {
                    fetched.body.readLines { lines ->
                        parsingThread = Thread.currentThread().name
                        lines.first()
                    }
                }
            }

            assertEquals("#EXTM3U", first)
            assertNotEquals("refresh-main", parsingThread)

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("<tv/>"),
            )
            val epg = fetcher.conditionalGet(
                server.url("/guide.xml").toString(),
                null,
                null,
            ) as ConditionalFetch.Success
            var epgParsingThread = ""
            withContext(caller) {
                epg.body.readChars { chars ->
                    epgParsingThread = Thread.currentThread().name
                    while (chars.nextChar() != -1) Unit
                }
            }
            assertNotEquals("refresh-main", epgParsingThread)
        } finally {
            caller.close()
            server.shutdown()
        }
    }

    @Test
    fun cancellationInterruptsAnInFlightEpgBodyRead() = runBlocking {
        val callCancelled = CompletableDeferred<Unit>()
        val client = OkHttpClient.Builder()
            .eventListener(
                object : EventListener() {
                    override fun callFailed(call: Call, ioe: IOException) {
                        if (call.isCanceled()) callCancelled.complete(Unit)
                    }
                },
            )
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("<tv><programme/></tv>")
                // Let the gzip sniff and parser callback start, then block the next socket read.
                .throttleBody(2, 30, TimeUnit.SECONDS),
        )
        server.start()
        try {
            val fetcher = createConditionalFetcher(
                client = { client },
                userAgent = { "OpenTV-Test" },
            )
            val fetched = fetcher.conditionalGet(
                server.url("/guide.xml").toString(),
                null,
                null,
            ) as ConditionalFetch.Success
            val bodyReadStarted = CompletableDeferred<Unit>()
            val refresh = launch {
                fetched.body.readChars { chars ->
                    bodyReadStarted.complete(Unit)
                    while (chars.nextChar() != -1) {
                        // The adapter checks cancellation before every character.
                    }
                }
            }
            withTimeout(2_000) { bodyReadStarted.await() }

            withTimeout(2_000) {
                refresh.cancelAndJoin()
                callCancelled.await()
            }

            assertTrue(refresh.isCancelled)
        } finally {
            server.shutdown()
        }
    }
}
