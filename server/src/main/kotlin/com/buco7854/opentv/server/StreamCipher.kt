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

/**
 * Turns a provider URL into an opaque token and back, so the browser never sees
 * the panel URL or its password. Token is `<tag>.<base64url(nonce|ciphertext|gcmTag)>`,
 * the tag being the clear stream format. Encryption is deterministic (SIV-style
 * nonce derived from plaintext) so a URL always yields the same token. Stream and
 * image capabilities use separate authenticated-encryption domains so a token minted
 * for one route cannot be replayed against the other. Image capabilities also carry
 * their user, optional playlist entitlement, and expiry.
 */
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

    /** Decode a token, or pass through a non-token identifier (series key, internal URL). */
    fun resolve(value: String): String = tryDecrypt(value) ?: value

    fun encrypt(url: String): String = encryptValue(classify(url), url, STREAM_PURPOSE)

    fun encryptImage(url: String, userId: String, playlistId: Long? = null): String {
        require(userId.isNotBlank() && userId.length <= 128) { "Invalid image capability user" }
        val expiresAtMs = clock() + IMAGE_CAPABILITY_TTL_MS
        return encryptValue(
            'i',
            "$expiresAtMs\n$userId\n${playlistId ?: "-"}\n$url",
            IMAGE_PURPOSE,
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

    /** Reverses [encrypt]; null for anything that isn't one of our tokens. */
    fun tryDecrypt(token: String): String? {
        if (token.length < 3 || token[1] != '.' || token[0] !in "hltd") return null
        return decryptValue(token, STREAM_PURPOSE)
    }

    fun tryDecryptImage(token: String): ImageCapability? {
        if (token.length !in 3..MAX_IMAGE_CAPABILITY_LENGTH ||
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
        const val MAX_IMAGE_CAPABILITY_LENGTH = 8_192
        val STREAM_PURPOSE = "opentv-stream-v2".toByteArray()
        val IMAGE_PURPOSE = "opentv-image-v1".toByteArray()
    }
}
