package com.buco7854.opentv.server

import com.buco7854.opentv.serverdata.db.PasswordCredentialRow
import com.buco7854.opentv.serverdata.db.RecoveryCodeRow
import com.buco7854.opentv.serverdata.db.ServerUserDatabase
import com.buco7854.opentv.serverdata.db.TotpCredentialRow
import java.security.MessageDigest
import java.util.UUID

/** Password, TOTP, and recovery-code persistence shared by local and account-security flows. */
internal class AuthCredentialService(
    private val db: ServerUserDatabase,
    private val config: AuthConfig,
    private val clock: () -> Long,
) {
    data class PendingTotp(
        val credential: TotpCredentialRow,
        val secret: ByteArray,
    )

    suspend fun setPassword(userId: String, password: String, now: Long) {
        storePreparedPassword(preparePassword(userId, password, now))
    }

    suspend fun requirePassword(userId: String, action: String) {
        if (db.credentials().password(userId) == null) {
            throw PasswordCredentialRequiredException(action)
        }
    }

    /** Hashes and assembles a credential without writing it. */
    fun preparePassword(userId: String, password: String, now: Long): PasswordCredentialRow {
        val (hash, salt) = AuthCrypto.passwordHash(password)
        return PasswordCredentialRow(
            userId = userId,
            hash = hash,
            salt = salt,
            memoryKb = AuthCrypto.ARGON_MEMORY_KB,
            iterations = AuthCrypto.ARGON_ITERATIONS,
            parallelism = AuthCrypto.ARGON_PARALLELISM,
            version = AuthCrypto.ARGON_VERSION,
            changedAtMs = now,
        )
    }

    suspend fun storePreparedPassword(credential: PasswordCredentialRow) =
        db.credentials().upsertPassword(credential)

    suspend fun maybeRehash(userId: String, password: String, row: PasswordCredentialRow) {
        if (row.version == AuthCrypto.ARGON_VERSION &&
            row.memoryKb == AuthCrypto.ARGON_MEMORY_KB &&
            row.iterations == AuthCrypto.ARGON_ITERATIONS &&
            row.parallelism == AuthCrypto.ARGON_PARALLELISM
        ) {
            return
        }
        setPassword(userId, password, clock())
    }

    fun verifyTotp(row: TotpCredentialRow, code: String): Long? {
        if (!code.matches(Regex("\\d{6}"))) return null
        val secret = AuthCrypto.decrypt(
            requireNotNull(config.encryptionKey),
            "totp:${row.userId}:${row.id}",
            row.encryptedSecret,
        )
        val current = clock() / 30_000L
        return (current - 1..current + 1).firstOrNull { step ->
            step > (row.lastAcceptedStep ?: Long.MIN_VALUE) &&
                constantEquals(AuthCrypto.totp(secret, step), code)
        }
    }

    suspend fun newPendingTotp(userId: String): PendingTotp {
        db.credentials().deleteUnconfirmedTotp(userId)
        val secret = AuthCrypto.randomBytes(20)
        val id = UUID.randomUUID().toString()
        val credential = TotpCredentialRow(
            id = id,
            userId = userId,
            encryptedSecret = AuthCrypto.encrypt(
                requireNotNull(config.encryptionKey),
                "totp:$userId:$id",
                secret,
            ),
            label = "Authenticator",
            confirmed = false,
            lastAcceptedStep = null,
            createdAtMs = clock(),
        )
        db.credentials().upsertTotp(credential)
        return PendingTotp(credential, secret)
    }

    suspend fun replaceRecoveryCodes(userId: String): List<String> {
        val (raw, rows) = newRecoveryCodes(userId)
        db.credentials().replaceRecoveryCodes(userId, rows)
        return raw
    }

    fun newRecoveryCodes(userId: String): Pair<List<String>, List<RecoveryCodeRow>> {
        val now = clock()
        val raw = List(10) {
            AuthCrypto.token(9).uppercase().chunked(4).joinToString("-")
        }
        return raw to raw.map {
            RecoveryCodeRow(
                id = UUID.randomUUID().toString(),
                userId = userId,
                codeHash = AuthCrypto.hashToken(it),
                createdAtMs = now,
                usedAtMs = null,
            )
        }
    }

    private fun constantEquals(left: String, right: String): Boolean =
        MessageDigest.isEqual(left.toByteArray(Charsets.UTF_8), right.toByteArray(Charsets.UTF_8))
}
