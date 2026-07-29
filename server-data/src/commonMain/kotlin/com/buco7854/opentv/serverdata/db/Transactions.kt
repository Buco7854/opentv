package com.buco7854.opentv.serverdata.db

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection

data class GrantReplacement(
    val removed: Set<Long>,
    val added: Set<Long>,
)

data class MfaCompletionWrite(
    val session: AuthSessionRow,
    val loginAtMs: Long,
    val totpCredential: TotpCredentialRow? = null,
    val webAuthnCredential: WebAuthnCredentialRow? = null,
    val replacementRecoveryCodes: List<RecoveryCodeRow>? = null,
)

/** Atomically replaces grants and updates the visibility state of associated downloads. */
suspend fun ServerUserDatabase.replaceUserPlaylistGrants(
    userId: String,
    playlistIds: List<Long>,
    atMs: Long,
): GrantReplacement = useWriterConnection { connection ->
    connection.immediateTransaction {
        val before = grants().forUser(userId).toSet()
        val after = playlistIds.distinct().toSet()
        grants().replaceForUser(userId, after.toList(), atMs)
        (before - after).forEach {
            downloads().suspendForPlaylist(userId, it, true, atMs)
        }
        (after - before).forEach {
            downloads().suspendForPlaylist(userId, it, false, atMs)
        }
        GrantReplacement(before - after, after - before)
    }
}

/**
 * Commits a successful MFA ceremony as one unit. A racing or already-consumed
 * challenge rolls the complete transaction back, including recovery-code use.
 */
suspend fun ServerUserDatabase.completeMfa(
    challengeId: String,
    parentChallengeId: String? = null,
    recoveryCodeId: String? = null,
    write: MfaCompletionWrite,
): Boolean = try {
    useWriterConnection { connection ->
        connection.immediateTransaction {
            if (challenges().consume(challengeId, write.loginAtMs) != 1) {
                throw MfaCompletionConflict()
            }
            if (parentChallengeId != null &&
                challenges().consume(parentChallengeId, write.loginAtMs) != 1
            ) {
                throw MfaCompletionConflict()
            }
            if (recoveryCodeId != null &&
                credentials().consumeRecoveryCode(
                    write.session.userId,
                    recoveryCodeId,
                    write.loginAtMs,
                ) != 1
            ) {
                throw MfaCompletionConflict()
            }
            write.totpCredential?.let { credentials().upsertTotp(it) }
            write.webAuthnCredential?.let { credentials().upsertWebAuthn(it) }
            write.replacementRecoveryCodes?.let {
                credentials().replaceRecoveryCodes(write.session.userId, it)
            }
            sessions().insert(write.session)
            users().markLogin(write.session.userId, write.loginAtMs)
        }
    }
    true
} catch (_: MfaCompletionConflict) {
    false
}

private class MfaCompletionConflict : RuntimeException()
