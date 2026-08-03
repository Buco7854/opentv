package com.buco7854.opentv.hub

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import com.buco7854.opentv.diag.ErrorLog
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypt/decrypt seam so the vault is unit-testable without a device Keystore.
 */
interface TokenCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(blob: ByteArray): ByteArray
}

/**
 * Hub session tokens never enter Room: the catalog DB is read broadly across
 * the app, and a bearer belongs behind a narrower door. They live here,
 * AES/GCM-encrypted with a non-exportable Keystore key, and both stores are
 * excluded from backup and device transfer (see res/xml/data_extraction_rules).
 * Losing the key (very rare; e.g. secure-lock reset) only signs hubs out.
 */
class HubSessionVault(
    private val prefs: SharedPreferences,
    private val cipher: TokenCipher = KeystoreTokenCipher(),
) {
    @Synchronized
    fun token(hubId: Long): String? {
        val blob = prefs.getString(key(hubId), null) ?: return null
        return runCatching {
            cipher.decrypt(Base64.getDecoder().decode(blob)).decodeToString()
        }.onFailure { ErrorLog.log("Hub session decrypt", it) }.getOrNull()
    }

    /**
     * Whether a session is stored for this hub, readable or not.
     *
     * [token] cannot tell "you never signed in" apart from "the Keystore would not
     * decrypt this just now", and the two deserve different treatment: the first means
     * the server is not connected, while the second is a server you did connect whose
     * session we failed to read. Treating the second as the first makes the server
     * disappear from the app entirely, with no way back in. Callers deciding whether a
     * connection still exists ask this; callers needing to authenticate ask [token] and
     * handle its absence as a signed-out session, which offers signing in again.
     */
    @Synchronized
    fun hasStoredSession(hubId: Long): Boolean = prefs.contains(key(hubId))

    @Synchronized
    fun store(hubId: Long, token: String) {
        val blob = Base64.getEncoder().encodeToString(cipher.encrypt(token.encodeToByteArray()))
        prefs.edit().putString(key(hubId), blob).apply()
    }

    @Synchronized
    fun clear(hubId: Long) {
        prefs.edit().remove(key(hubId)).apply()
    }

    @Synchronized
    fun pruneMissingHubs(existingHubIds: Set<Long>) {
        val staleKeys = prefs.all.keys.filter { storedKey ->
            storedKey.removePrefix(KEY_PREFIX)
                .takeIf { storedKey.startsWith(KEY_PREFIX) }
                ?.toLongOrNull()
                ?.let { it !in existingHubIds } == true
        }
        if (staleKeys.isNotEmpty()) {
            prefs.edit().apply {
                staleKeys.forEach(::remove)
            }.apply()
        }
    }

    private fun key(hubId: Long) = "$KEY_PREFIX$hubId"

    companion object {
        const val PREFS_NAME = "hub_sessions"
        private const val KEY_PREFIX = "hub-token-"
    }
}

/** AES/256/GCM under a Keystore key; blob layout is [12-byte IV][ciphertext+tag]. */
class KeystoreTokenCipher : TokenCipher {

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size > IV_BYTES) { "vault blob too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES)
        cipher.init(Cipher.DECRYPT_MODE, key(), spec)
        return cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "opentv-hub-tokens"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
