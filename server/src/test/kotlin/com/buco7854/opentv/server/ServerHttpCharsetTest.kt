package com.buco7854.opentv.server

import com.buco7854.opentv.core.net.ConditionalFetch
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class ServerHttpCharsetTest {
    @Test
    fun `provider text uses declared charset or the same streaming UTF-8 fallback`() = runBlocking {
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
        val upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        cases.forEachIndexed { index, case ->
            upstream.createContext("/feed-$index") { exchange ->
                case.contentType?.let { exchange.responseHeaders.add("Content-Type", it) }
                exchange.sendResponseHeaders(200, case.bytes.size.toLong())
                exchange.responseBody.use { it.write(case.bytes) }
            }
        }
        upstream.start()
        try {
            val fetcher = ServerHttp().conditionalFetcher
            cases.forEachIndexed { index, case ->
                val fetched = fetcher.conditionalGet(
                    "http://127.0.0.1:${upstream.address.port}/feed-$index",
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
                assertEquals(case.expected, actual, case.name)
            }
        } finally {
            upstream.stop(0)
        }
    }
}
