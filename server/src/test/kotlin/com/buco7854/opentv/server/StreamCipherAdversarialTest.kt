package com.buco7854.opentv.server

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamCipherAdversarialTest {
    private val cipher = StreamCipher(
        Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() }),
    )

    @Test
    fun `clear stream tag cannot be changed into HLS provenance`() {
        val direct = cipher.encryptStream(
            "https://provider.example/movie.mp4",
            "lease-1",
        )
        val forgedHls = "h${direct.drop(1)}"

        assertNull(cipher.tryDecryptStream(forgedHls))
    }

    @Test
    fun `delimiters are rejected in every structured identity field`() {
        assertFailsWith<IllegalArgumentException> {
            cipher.encryptStream("https://provider.example/movie.mp4", "lease\ninjected")
        }
        assertFailsWith<IllegalArgumentException> {
            cipher.encryptImage("https://provider.example/poster.jpg", "user\ninjected")
        }
        assertFailsWith<IllegalArgumentException> {
            cipher.encryptWebSocket("session\ninjected", "lease-1")
        }
        assertFailsWith<IllegalArgumentException> {
            cipher.encryptWebSocket("session-1", "lease\ninjected")
        }
        assertFailsWith<IllegalArgumentException> {
            cipher.encryptDownloadFile("user-1", "session\ninjected", "download-1")
        }
    }

    @Test
    fun `every structured parser rejects wrong field arity`() {
        val future = Long.MAX_VALUE.toString()

        assertNull(cipher.tryDecryptWebSocket(raw('w', "$future\nsession-1")))
        assertNull(cipher.tryDecryptWebSocket(raw('w', "$future\nsession-1\nlease-1\nextra")))
        assertNull(cipher.tryDecryptDownloadFile(raw('f', "$future\nuser-1\ndownload-1")))
        assertNull(
            cipher.tryDecryptDownloadFile(
                raw('f', "$future\nuser-1\nsession-1\ndownload-1\nextra"),
            ),
        )
        assertNull(cipher.tryDecryptImage(raw('i', "$future\nuser-1\nhttps://image.example")))
    }

    @Test
    fun `purpose AAD rejects a correctly tagged token from every other purpose`() {
        val stream = cipher.encryptStream("https://provider.example/movie.mp4", "lease-1")
        val image = cipher.encryptImage("https://provider.example/poster.jpg", "user-1")
        val socket = cipher.encryptWebSocket("session-1", "lease-1").token
        val file = cipher.encryptDownloadFile("user-1", "session-1", "download-1").token

        assertNull(cipher.tryDecryptStream(retag(image, 'd')))
        assertNull(cipher.tryDecryptStream(retag(socket, 'd')))
        assertNull(cipher.tryDecryptStream(retag(file, 'd')))
        assertNull(cipher.tryDecryptImage(retag(stream, 'i')))
        assertNull(cipher.tryDecryptImage(retag(socket, 'i')))
        assertNull(cipher.tryDecryptImage(retag(file, 'i')))
        assertNull(cipher.tryDecryptWebSocket(retag(stream, 'w')))
        assertNull(cipher.tryDecryptWebSocket(retag(image, 'w')))
        assertNull(cipher.tryDecryptWebSocket(retag(file, 'w')))
        assertNull(cipher.tryDecryptDownloadFile(retag(stream, 'f')))
        assertNull(cipher.tryDecryptDownloadFile(retag(image, 'f')))
        assertNull(cipher.tryDecryptDownloadFile(retag(socket, 'f')))
    }

    @Test
    fun `truncated and extended envelopes fail authentication`() {
        val token = cipher.encryptDownloadFile("user-1", "session-1", "download-1").token

        assertTrue(cipher.tryDecryptDownloadFile(token) != null)
        assertNull(cipher.tryDecryptDownloadFile(token.dropLast(1)))
        assertNull(cipher.tryDecryptDownloadFile("${token}A"))
    }

    @Test
    fun `download v1 purpose and old field arity are both rejected`() {
        val oldToken = raw(
            tag = 'f',
            plaintext = "${Long.MAX_VALUE}\nuser-1\ndownload-1",
            purpose = "opentv-download-file-v1",
        )

        assertNull(cipher.tryDecryptDownloadFile(oldToken))
    }

    private fun retag(token: String, tag: Char) = "$tag${token.drop(1)}"

    private fun raw(
        tag: Char,
        plaintext: String,
        purpose: String = when (tag) {
            'w' -> "opentv-websocket-v1"
            'f' -> "opentv-download-file-v2"
            'i' -> "opentv-image-v1"
            else -> error("Unsupported test purpose")
        },
    ): String {
        val method = StreamCipher::class.java.getDeclaredMethod(
            "encryptValue",
            Char::class.javaPrimitiveType,
            String::class.java,
            ByteArray::class.java,
        )
        method.isAccessible = true
        return method.invoke(cipher, tag, plaintext, purpose.toByteArray()) as String
    }
}
