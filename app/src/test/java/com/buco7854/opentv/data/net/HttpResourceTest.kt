package com.buco7854.opentv.data.net

import java.io.ByteArrayInputStream
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.BufferedSource
import okio.buffer
import okio.source
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpResourceTest {

    @Test
    fun malformedGzipClosesTheUnderlyingResponseStream() {
        var closed = false
        val input = object : ByteArrayInputStream(byteArrayOf(0x1f, 0x8b.toByte())) {
            override fun close() {
                closed = true
                super.close()
            }
        }
        val body = object : ResponseBody() {
            override fun contentType() = null
            override fun contentLength() = 2L
            override fun source(): BufferedSource = input.source().buffer()
        }
        val response = Response.Builder()
            .request(Request.Builder().url("https://example.test/guide.xml.gz").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body)
            .build()

        assertThrows(Exception::class.java) {
            Http.bodyStream(response)
        }

        assertTrue(closed)
    }
}
