package com.buco7854.opentv.server

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Turns a provider URL into an opaque token and back, so the browser never sees
 * the panel URL or its password. Token is `<tag>.<base64url(nonce|ciphertext|gcmTag)>`,
 * the tag being the clear stream format. Encryption is deterministic (SIV-style
 * nonce derived from plaintext) so a URL always yields the same token, letting it
 * double as a stable favorite/resume key. Goal is hiding credentials, not resisting
 * an attacker who holds the key.
 */
class StreamCipher(masterKeyBase64: String) {

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

    /** Tokenize a nullable URL (logos, posters, cast photos). */
    fun encryptOrNull(url: String?): String? = url?.let(::encrypt)

    /** Decode a token, or pass through a non-token identifier (series key, internal URL). */
    fun resolve(value: String): String = tryDecrypt(value) ?: value

    fun encrypt(url: String): String {
        val plain = url.toByteArray()
        val nonce = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(macKey, "HmacSHA256"))
            doFinal(plain).copyOf(12)
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, encKey, GCMParameterSpec(128, nonce))
        }
        val body = nonce + cipher.doFinal(plain)
        return "${classify(url)}.${Base64.getUrlEncoder().withoutPadding().encodeToString(body)}"
    }

    /** Reverses [encrypt]; null for anything that isn't one of our tokens. */
    fun tryDecrypt(token: String): String? {
        if (token.length < 3 || token[1] != '.' || token[0] !in "hltd") return null
        return runCatching {
            val body = Base64.getUrlDecoder().decode(token.substring(2))
            val nonce = body.copyOfRange(0, 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, encKey, GCMParameterSpec(128, nonce))
            }
            String(cipher.doFinal(body.copyOfRange(12, body.size)))
        }.getOrNull()
    }
}
