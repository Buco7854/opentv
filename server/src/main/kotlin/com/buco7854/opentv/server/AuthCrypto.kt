package com.buco7854.opentv.server

import org.bouncycastle.crypto.digests.SHA1Digest
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.KeyParameter
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Base64
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object AuthCrypto {
    const val ARGON_MEMORY_KB = 65_536
    const val ARGON_ITERATIONS = 3
    const val ARGON_PARALLELISM = 1
    const val ARGON_VERSION = 1
    private const val ARGON_OUTPUT_BYTES = 32
    private val random = SecureRandom()
    private val urlEncoder = Base64.getUrlEncoder().withoutPadding()

    fun normalizeUsername(value: String): String {
        val trimmed = value.trim()
        require(trimmed.codePointCount(0, trimmed.length) in 3..64) {
            "Username must be between 3 and 64 characters"
        }
        val normalized = Normalizer.normalize(trimmed, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
        require(normalized.codePointCount(0, normalized.length) in 3..64) {
            "Normalized username must be between 3 and 64 characters"
        }
        require(normalized.toByteArray(Charsets.UTF_8).size <= 256) { "Username is too large" }
        return normalized
    }

    fun validatePassword(password: String) {
        val points = password.codePointCount(0, password.length)
        require(points in 12..128) { "Password must be between 12 and 128 characters" }
        require(password.toByteArray(Charsets.UTF_8).size <= 1024) { "Password is too large" }
    }

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)
    fun token(bytes: Int = 32): String = urlEncoder.encodeToString(randomBytes(bytes))
    fun hashToken(token: String): ByteArray = sha256(token.toByteArray(Charsets.UTF_8))
    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    fun passwordHash(
        password: String,
        salt: ByteArray = randomBytes(16),
        memoryKb: Int = ARGON_MEMORY_KB,
        iterations: Int = ARGON_ITERATIONS,
        parallelism: Int = ARGON_PARALLELISM,
    ): Pair<ByteArray, ByteArray> {
        validatePassword(password)
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(salt)
            .withMemoryAsKB(memoryKb)
            .withIterations(iterations)
            .withParallelism(parallelism)
            .build()
        val out = ByteArray(ARGON_OUTPUT_BYTES)
        Argon2BytesGenerator().apply { init(params) }.generateBytes(password.toCharArray(), out)
        return out to salt
    }

    fun verifyPassword(
        password: String,
        expected: ByteArray,
        salt: ByteArray,
        memoryKb: Int,
        iterations: Int,
        parallelism: Int,
    ): Boolean {
        val actual = runCatching {
            passwordHash(password, salt, memoryKb, iterations, parallelism).first
        }.getOrElse { ByteArray(expected.size) }
        return MessageDigest.isEqual(expected, actual)
    }

    fun deriveKey(master: ByteArray, label: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(master, "HmacSHA256"))
        return mac.doFinal("opentv-auth:$label".toByteArray(Charsets.UTF_8))
    }

    fun encrypt(master: ByteArray, label: String, plaintext: ByteArray): ByteArray {
        val nonce = randomBytes(12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(deriveKey(master, label), "AES"),
            GCMParameterSpec(128, nonce),
        )
        cipher.updateAAD(label.toByteArray(Charsets.UTF_8))
        return nonce + cipher.doFinal(plaintext)
    }

    fun decrypt(master: ByteArray, label: String, ciphertext: ByteArray): ByteArray {
        require(ciphertext.size > 28) { "Invalid encrypted value" }
        val nonce = ciphertext.copyOfRange(0, 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(deriveKey(master, label), "AES"),
            GCMParameterSpec(128, nonce),
        )
        cipher.updateAAD(label.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(ciphertext.copyOfRange(12, ciphertext.size))
    }

    fun base32(bytes: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val out = StringBuilder((bytes.size * 8 + 4) / 5)
        var buffer = 0
        var bits = 0
        for (byte in bytes) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                out.append(alphabet[(buffer shr bits) and 31])
            }
        }
        if (bits > 0) out.append(alphabet[(buffer shl (5 - bits)) and 31])
        return out.toString()
    }

    fun decodeBase32(value: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val clean = value.uppercase().filterNot(Char::isWhitespace).trimEnd('=')
        val out = ArrayList<Byte>()
        var buffer = 0
        var bits = 0
        for (char in clean) {
            val index = alphabet.indexOf(char)
            require(index >= 0) { "Invalid base32" }
            buffer = (buffer shl 5) or index
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out += ((buffer shr bits) and 0xff).toByte()
            }
        }
        return out.toByteArray()
    }

    fun totp(secret: ByteArray, step: Long): String {
        val mac = HMac(SHA1Digest())
        mac.init(KeyParameter(secret))
        val input = ByteBuffer.allocate(8).putLong(step).array()
        val digest = ByteArray(mac.macSize)
        mac.update(input, 0, input.size)
        mac.doFinal(digest, 0)
        val offset = digest.last().toInt() and 0xf
        val binary = ((digest[offset].toInt() and 0x7f) shl 24) or
            ((digest[offset + 1].toInt() and 0xff) shl 16) or
            ((digest[offset + 2].toInt() and 0xff) shl 8) or
            (digest[offset + 3].toInt() and 0xff)
        return (binary % 1_000_000).toString().padStart(6, '0')
    }

    fun totpUri(secret: ByteArray, username: String): String =
        "otpauth://totp/OpenTV:${URLEncoder.encode(username, Charsets.UTF_8)}" +
            "?secret=${base32(secret)}&issuer=OpenTV&algorithm=SHA1&digits=6&period=30"
}
