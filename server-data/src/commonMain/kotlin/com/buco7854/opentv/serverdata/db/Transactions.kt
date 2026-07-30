package com.buco7854.opentv.serverdata.db

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import com.buco7854.opentv.data.withCatalogSearchIndexesRebuilt

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
suspend fun OpenTvServerDatabase.replaceUserPlaylistGrants(
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
suspend fun OpenTvServerDatabase.completeMfa(
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

/**
 * Persists one logical reconciliation without monopolizing SQLite's only writer.
 *
 * The caller owns application-level isolation and crash repair. Each DAO call below is its own
 * Room transaction so unrelated session, login and download writes can run between chunks.
 */
suspend fun OpenTvServerDatabase.writeContentIdentityReconciliation(
    inserts: List<ContentIdentityRow>,
    updates: List<ContentIdentityRow>,
    playlistId: Long,
    retireMissingBeforeMs: Long?,
) {
    inserts.chunked(CONTENT_IDENTITY_WRITE_CHUNK_SIZE).forEach {
        content().insertAll(it)
    }
    updates.chunked(CONTENT_IDENTITY_WRITE_CHUNK_SIZE).forEach {
        content().updateAll(it)
    }
    retireMissingBeforeMs?.let { content().retireNotSeen(playlistId, it) }
}

/**
 * Removes the complete shared catalog slice for one playlist. The final playlist delete
 * cascades server grants, defaults, identities and their dependent per-user state inside the
 * same writer transaction.
 */
suspend fun OpenTvServerDatabase.deleteCatalogPlaylist(playlistId: Long) =
    withCatalogSearchIndexesRebuilt {
        resumeDao().deleteForPlaylist(playlistId)
        channelDao().deleteForPlaylist(playlistId)
        epgDao().deleteForPlaylist(playlistId)
        xtreamSeriesDao().deleteForPlaylist(playlistId)
        favoriteDao().deleteForPlaylist(playlistId)
        groupOverrideDao().deleteForPlaylist(playlistId)
        playlistDao().delete(playlistId)
    }

const val CONTENT_IDENTITY_WRITE_CHUNK_SIZE = 2_000
