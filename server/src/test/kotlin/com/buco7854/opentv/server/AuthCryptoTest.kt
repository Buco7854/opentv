package com.buco7854.opentv.server

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthCryptoTest {
    @Test
    fun usernameUsesNfkcAndLocaleIndependentCaseFolding() {
        assertEquals("alice", AuthCrypto.normalizeUsername("  ＡLICE  "))
    }

    @Test
    fun passwordLengthCountsUnicodeCodePointsWithoutNormalizing() {
        AuthCrypto.validatePassword("😀😀😀😀😀😀😀😀😀😀😀😀")
        assertFailsWith<IllegalArgumentException> { AuthCrypto.validatePassword("short") }
    }

    @Test
    fun argon2idRoundTripAndWrongPassword() {
        val password = "correct horse battery staple"
        val (hash, salt) = AuthCrypto.passwordHash(password)
        assertEquals(16, salt.size)
        assertEquals(32, hash.size)
        assertTrue(
            AuthCrypto.verifyPassword(
                password, hash, salt,
                AuthCrypto.ARGON_MEMORY_KB, AuthCrypto.ARGON_ITERATIONS, AuthCrypto.ARGON_PARALLELISM,
            ),
        )
        assertFalse(
            AuthCrypto.verifyPassword(
                "incorrect password", hash, salt,
                AuthCrypto.ARGON_MEMORY_KB, AuthCrypto.ARGON_ITERATIONS, AuthCrypto.ARGON_PARALLELISM,
            ),
        )
    }

    @Test
    fun totpMatchesRfc6238Sha1VectorAtSixDigits() {
        assertEquals(
            "287082",
            AuthCrypto.totp("12345678901234567890".toByteArray(), 1),
        )
    }

    @Test
    fun encryptedTotpMaterialIsDomainSeparatedAndAuthenticated() {
        val master = ByteArray(32) { it.toByte() }
        val plaintext = ByteArray(20) { (it + 1).toByte() }
        val encrypted = AuthCrypto.encrypt(master, "totp:user:credential", plaintext)
        assertContentEquals(
            plaintext,
            AuthCrypto.decrypt(master, "totp:user:credential", encrypted),
        )
        assertFailsWith<Exception> {
            AuthCrypto.decrypt(master, "totp:other:credential", encrypted)
        }
    }
}
