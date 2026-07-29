package com.buco7854.opentv.server

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class ImageCapability(
    val url: String,
    val userId: String,
    val playlistId: Long?,
    val expiresAtMs: Long,
)

data class StreamCapability(
    val url: String,
    val leaseId: String,
    /** True only for an HLS root or a child capability minted by the HLS rewriter. */
    val hlsResource: Boolean = false,
)

data class WebSocketCapability(
    val sessionId: String,
    val leaseId: String,
    val expiresAtMs: Long,
)

data class DownloadFileCapability(
    val userId: String,
    val downloadId: String,
    val expiresAtMs: Long,
)

class StreamCipher(
    masterKeyBase64: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val encKey: SecretKeySpec
    private val macKey: ByteArray

    init {
        val master = Base64.getDecoder().decode(masterKeyBase64)
        fun derive(label: String) =
            MessageDigest.getInstance("SHA-256").digest(label.toByteArray() + master)
        encKey = SecretKeySpec(derive("otv-enc").copyOf(16), "AES")
        macKey = derive("otv-mac")
    }

    /** Stream format tag, kept in the clear so the client picks its engine. */
    private fun classify(url: String): Char {
        val path = url.substringBefore('?').lowercase()
        return when {
            path.endsWith(".m3u8") || path.endsWith(".m3u") -> 'h'
            path.endsWith(".ts") && "/live/" in path -> 'l'
            path.endsWith(".ts") -> 't'
            else -> 'd'
        }
    }

    /** Tokenize a nullable image URL. Image tokens cannot be used by media routes. */
    fun encryptOrNull(url: String?, userId: String, playlistId: Long? = null): String? =
        url?.let { encryptImage(it, userId, playlistId) }

    fun encryptStream(url: String, leaseId: String): String {
        require(leaseId.isNotBlank() && leaseId.length <= 128 && '\n' !in leaseId) {
            "Invalid stream capability lease"
        }
        return encryptValue(classify(url), "$leaseId\n$url", STREAM_PURPOSE)
    }

    /**
     * Keep HLS provenance inside the authenticated capability even when a child is a `.ts`, key,
     * or extensionless URI. The clear tag drives no authorization decision; GCM protects the URL
     * and lease payload, and [tryDecryptStream] rejects any tag/payload tampering.
     */
    fun encryptHlsResource(url: String, leaseId: String): String {
        require(leaseId.isNotBlank() && leaseId.length <= 128 && '\n' !in leaseId) {
            "Invalid stream capability lease"
        }
        return encryptValue('h', "$leaseId\n$url", STREAM_PURPOSE)
    }

    fun encryptImage(url: String, userId: String, playlistId: Long? = null): String {
        require(userId.isNotBlank() && userId.length <= 128) { "Invalid image capability user" }
        val expiresAtMs = clock() + IMAGE_CAPABILITY_TTL_MS
        return encryptValue(
            'i',
            "$expiresAtMs\n$userId\n${playlistId ?: "-"}\n$url",
            IMAGE_PURPOSE,
        )
    }

    fun encryptWebSocket(sessionId: String, leaseId: String): WebSocketCapabilityToken {
        require(sessionId.isNotBlank() && sessionId.length <= 128) {
            "Invalid WebSocket session"
        }
        require(leaseId.isNotBlank() && leaseId.length <= 128) {
            "Invalid WebSocket lease"
        }
        val expiresAtMs = clock() + WEB_SOCKET_CAPABILITY_TTL_MS
        return WebSocketCapabilityToken(
            encryptValue('w', "$expiresAtMs\n$sessionId\n$leaseId", WEB_SOCKET_PURPOSE),
            expiresAtMs,
        )
    }

    fun encryptDownloadFile(userId: String, downloadId: String): DownloadFileCapabilityToken {
        require(userId.isNotBlank() && userId.length <= 128) { "Invalid download owner" }
        require(downloadId.isNotBlank() && downloadId.length <= 128) { "Invalid download id" }
        val expiresAtMs = clock() + DOWNLOAD_FILE_CAPABILITY_TTL_MS
        return DownloadFileCapabilityToken(
            encryptValue('f', "$expiresAtMs\n$userId\n$downloadId", DOWNLOAD_FILE_PURPOSE),
            expiresAtMs,
        )
    }

    private fun encryptValue(tag: Char, value: String, purpose: ByteArray): String {
        val plain = value.toByteArray()
        val nonce = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(macKey, "HmacSHA256"))
            doFinal(purpose + plain).copyOf(12)
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, encKey, GCMParameterSpec(128, nonce))
            updateAAD(purpose)
        }
        val body = nonce + cipher.doFinal(plain)
        return "$tag.${Base64.getUrlEncoder().withoutPadding().encodeToString(body)}"
    }

    fun tryDecryptStream(token: String): StreamCapability? {
        if (token.length !in 3..MAX_CAPABILITY_LENGTH ||
            token[1] != '.' || token[0] !in "hltd"
        ) return null
        val payload = decryptValue(token, STREAM_PURPOSE) ?: return null
        val separator = payload.indexOf('\n')
        if (separator <= 0) return null
        val url = payload.substring(separator + 1).takeIf { it.isNotBlank() } ?: return null
        return StreamCapability(
            url = url,
            leaseId = payload.substring(0, separator),
            hlsResource = token[0] == 'h',
        )
    }

    fun tryDecryptImage(token: String): ImageCapability? {
        if (token.length !in 3..MAX_CAPABILITY_LENGTH ||
            token[1] != '.' || token[0] != 'i'
        ) return null
        val payload = decryptValue(token, IMAGE_PURPOSE) ?: return null
        val fields = payload.split('\n', limit = 4)
        if (fields.size != 4) return null
        val expiresAtMs = fields[0].toLongOrNull()?.takeIf { it > clock() } ?: return null
        val userId = fields[1].takeIf { it.isNotBlank() && it.length <= 128 } ?: return null
        val owner = fields[2]
        val playlistId = if (owner == "-") null else owner.toLongOrNull() ?: return null
        val url = fields[3].takeIf { it.isNotBlank() } ?: return null
        return ImageCapability(url, userId, playlistId, expiresAtMs)
    }

    fun tryDecryptWebSocket(token: String): WebSocketCapability? {
        val fields = decryptExpiringFields(token, 'w', WEB_SOCKET_PURPOSE) ?: return null
        if (fields.size != 3) return null
        val sessionId = fields[1].takeIf { it.isNotBlank() && it.length <= 128 } ?: return null
        val leaseId = fields[2].takeIf { it.isNotBlank() && it.length <= 128 } ?: return null
        return WebSocketCapability(sessionId, leaseId, fields[0].toLong())
    }

    fun tryDecryptDownloadFile(token: String): DownloadFileCapability? {
        val fields = decryptExpiringFields(token, 'f', DOWNLOAD_FILE_PURPOSE) ?: return null
        if (fields.size != 3) return null
        val userId = fields[1].takeIf { it.isNotBlank() && it.length <= 128 } ?: return null
        val downloadId = fields[2].takeIf { it.isNotBlank() && it.length <= 128 } ?: return null
        return DownloadFileCapability(userId, downloadId, fields[0].toLong())
    }

    private fun decryptExpiringFields(
        token: String,
        tag: Char,
        purpose: ByteArray,
    ): List<String>? {
        if (token.length !in 3..MAX_CAPABILITY_LENGTH || token[1] != '.' || token[0] != tag) {
            return null
        }
        val fields = decryptValue(token, purpose)?.split('\n') ?: return null
        fields.firstOrNull()?.toLongOrNull()?.takeIf { it > clock() } ?: return null
        return fields
    }

    private fun decryptValue(token: String, purpose: ByteArray): String? {
        return runCatching {
            val body = Base64.getUrlDecoder().decode(token.substring(2))
            require(body.size > 12)
            val nonce = body.copyOfRange(0, 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, encKey, GCMParameterSpec(128, nonce))
                updateAAD(purpose)
            }
            String(cipher.doFinal(body.copyOfRange(12, body.size)))
        }.getOrNull()
    }

    private companion object {
        const val IMAGE_CAPABILITY_TTL_MS = 24 * 60 * 60_000L
        const val WEB_SOCKET_CAPABILITY_TTL_MS = 30_000L
        const val DOWNLOAD_FILE_CAPABILITY_TTL_MS = 10 * 60_000L
        const val MAX_CAPABILITY_LENGTH = 8_192
        val STREAM_PURPOSE = "opentv-stream-v3".toByteArray()
        val IMAGE_PURPOSE = "opentv-image-v1".toByteArray()
        val WEB_SOCKET_PURPOSE = "opentv-websocket-v1".toByteArray()
        val DOWNLOAD_FILE_PURPOSE = "opentv-download-file-v1".toByteArray()
    }
}

data class WebSocketCapabilityToken(val token: String, val expiresAtMs: Long)
data class DownloadFileCapabilityToken(val token: String, val expiresAtMs: Long)
