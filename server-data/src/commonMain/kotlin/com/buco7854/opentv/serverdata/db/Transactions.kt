package com.buco7854.opentv.serverdata.db

import androidx.room.immediateTransaction
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import com.buco7854.opentv.data.withCatalogSearchIndexesRebuilt
import kotlinx.coroutines.yield

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
    seriesLocators: List<ContentSeriesLocatorRow> = emptyList(),
) {
    inserts.chunked(CONTENT_IDENTITY_WRITE_CHUNK_SIZE).forEach {
        content().insertAll(it)
    }
    updates.chunked(CONTENT_IDENTITY_WRITE_CHUNK_SIZE).forEach {
        content().updateAll(it)
    }
    writeContentSeriesLocators(seriesLocators)
    retireMissingBeforeMs?.let { content().retireNotSeen(playlistId, it) }
}

suspend fun OpenTvServerDatabase.writeContentSeriesLocators(
    rows: List<ContentSeriesLocatorRow>,
) {
    rows.distinctBy(ContentSeriesLocatorRow::contentId)
        .chunked(CONTENT_SERIES_LOCATOR_WRITE_CHUNK_SIZE)
        .forEach { chunk ->
            useWriterConnection { connection ->
                connection.immediateTransaction {
                    val values = chunk.joinToString { "(?, ?, ?, ?)" }
                    connection.usePrepared(
                        "INSERT INTO content_series_locators(" +
                            "contentId, playlistId, sourceKind, sourceKey) VALUES $values " +
                            "ON CONFLICT(contentId) DO UPDATE SET " +
                            "playlistId=excluded.playlistId, sourceKind=excluded.sourceKind, " +
                            "sourceKey=excluded.sourceKey",
                    ) { statement ->
                        chunk.forEachIndexed { index, row ->
                            val offset = index * 4
                            statement.bindText(offset + 1, row.contentId)
                            statement.bindLong(offset + 2, row.playlistId)
                            statement.bindText(offset + 3, row.sourceKind)
                            statement.bindText(offset + 4, row.sourceKey)
                        }
                        statement.step()
                    }
                }
            }
        }
}

/**
 * Browsing normally finds locators written by reconciliation. The indexed existence read keeps
 * that path read-only while repairing identities created between refreshes (and focused callers
 * that deliberately create an identity without running startup repair).
 */
suspend fun OpenTvServerDatabase.ensureContentSeriesLocators(
    rows: List<ContentSeriesLocatorRow>,
) {
    val distinct = rows.distinctBy(ContentSeriesLocatorRow::contentId)
    if (distinct.isEmpty()) return
    val existing = distinct.chunked(CONTENT_SERIES_LOCATOR_WRITE_CHUNK_SIZE)
        .flatMap { chunk ->
            useReaderConnection { connection ->
                val placeholders = chunk.joinToString { "?" }
                connection.usePrepared(
                    "SELECT contentId FROM content_series_locators " +
                        "WHERE contentId IN ($placeholders)",
                ) { statement ->
                    chunk.forEachIndexed { index, row ->
                        statement.bindText(index + 1, row.contentId)
                    }
                    buildList {
                        while (statement.step()) add(statement.getText(0))
                    }
                }
            }
        }
        .toHashSet()
    writeContentSeriesLocators(distinct.filterNot { it.contentId in existing })
}

/**
 * Resolves sparse series favorites with index probes from the favorite/identity rows into the
 * matching Xtream row or M3U episode group. The number of catalog queries is independent of the
 * number of playlists represented by the account.
 */
suspend fun OpenTvServerDatabase.favoriteSeriesListings(
    userId: String,
    playlistId: Long? = null,
): List<FavoriteSeriesListingRow> = useReaderConnection { connection ->
    val playlistClause = if (playlistId == null) "" else "AND identities.playlistId = ?"
    connection.usePrepared(
        """
        SELECT favorites.contentId,
               identities.playlistId,
               favorites.addedAtMs,
               CASE WHEN locators.sourceKind = 'xtream'
                    THEN panel.name ELSE locators.sourceKey END AS seriesKey,
               -- Zero for a panel series means "not counted": its episodes live with the
               -- provider until something fetches them, so there is nothing here to count.
               -- The HAVING below drops an M3U series with no episodes, so a listed row
               -- never carries a genuine zero and a reader may treat one as unknown.
               CASE WHEN locators.sourceKind = 'xtream'
                    THEN 0 ELSE COUNT(episodes.id) END AS episodeCount,
               CASE WHEN locators.sourceKind = 'xtream'
                    THEN panel.cover ELSE MIN(episodes.logo) END AS logo,
               CASE WHEN locators.sourceKind = 'xtream'
                    THEN panel.categoryName ELSE MIN(episodes.groupTitle) END AS groupTitle,
               CASE WHEN locators.sourceKind = 'xtream'
                    THEN locators.sourceKey ELSE NULL END AS xtreamSeriesId
        FROM user_favorites AS favorites
        JOIN content_identities AS identities
          ON identities.contentId = favorites.contentId
        JOIN content_series_locators AS locators
          ON locators.contentId = favorites.contentId
        LEFT JOIN xtream_series AS panel
          ON locators.sourceKind = 'xtream'
         AND panel.playlistId = locators.playlistId
         AND panel.seriesId = CAST(locators.sourceKey AS INTEGER)
        LEFT JOIN channels AS episodes
          ON locators.sourceKind = 'm3u'
         AND episodes.playlistId = locators.playlistId
         AND episodes.kind = 2
         AND episodes.seriesKey = locators.sourceKey
        WHERE favorites.userId = ?
          AND identities.kind = 2
          AND identities.retired = 0
          $playlistClause
        GROUP BY favorites.contentId, identities.playlistId, favorites.addedAtMs,
                 locators.sourceKind, locators.sourceKey, panel.seriesId
        HAVING (locators.sourceKind = 'xtream' AND panel.seriesId IS NOT NULL)
            OR (locators.sourceKind = 'm3u' AND COUNT(episodes.id) > 0)
        ORDER BY seriesKey COLLATE NOCASE, favorites.contentId
        """.trimIndent(),
    ) { statement ->
        statement.bindText(1, userId)
        playlistId?.let { statement.bindLong(2, it) }
        buildList {
            while (statement.step()) {
                add(
                    FavoriteSeriesListingRow(
                        contentId = statement.getText(0),
                        playlistId = statement.getLong(1),
                        addedAtMs = statement.getLong(2),
                        seriesKey = statement.getText(3),
                        count = statement.getLong(4).toInt(),
                        logo = statement.getTextOrNull(5),
                        groupTitle = statement.getText(6),
                        xtreamSeriesId = statement.getTextOrNull(7),
                    ),
                )
            }
        }
    }
}

private fun androidx.sqlite.SQLiteStatement.getTextOrNull(index: Int): String? =
    if (isNull(index)) null else getText(index)

/**
 * Removes the complete shared catalog slice for one playlist. The final playlist delete
 * cascades server grants, defaults, identities and their dependent per-user state inside the
 * same writer transaction.
 */
suspend fun OpenTvServerDatabase.deleteCatalogPlaylist(playlistId: Long) =
    run {
        deleteGuideForPlaylistInChunks(playlistId)
        withCatalogSearchIndexesRebuilt {
            resumeDao().deleteForPlaylist(playlistId)
            channelDao().deleteForPlaylist(playlistId)
            epgDao().deleteForPlaylist(playlistId)
            xtreamSeriesDao().deleteForPlaylist(playlistId)
            favoriteDao().deleteForPlaylist(playlistId)
            groupOverrideDao().deleteForPlaylist(playlistId)
            playlistDao().delete(playlistId)
        }
    }

suspend fun OpenTvServerDatabase.deleteGuideFromInChunks(playlistId: Long, fromMs: Long) =
    deleteGuideInChunks { guideMaintenance().deleteFromChunk(playlistId, fromMs, GUIDE_WRITE_CHUNK_SIZE) }

suspend fun OpenTvServerDatabase.pruneGuideInChunks(playlistId: Long, beforeMs: Long) =
    deleteGuideInChunks { guideMaintenance().pruneChunk(playlistId, beforeMs, GUIDE_WRITE_CHUNK_SIZE) }

suspend fun OpenTvServerDatabase.deleteGuideForPlaylistInChunks(playlistId: Long) =
    deleteGuideInChunks {
        guideMaintenance().deleteForPlaylistChunk(playlistId, GUIDE_WRITE_CHUNK_SIZE)
    }

private suspend fun deleteGuideInChunks(delete: suspend () -> Int) {
    while (delete() == GUIDE_WRITE_CHUNK_SIZE) yield()
}

const val CONTENT_IDENTITY_WRITE_CHUNK_SIZE = 2_000
const val CONTENT_SERIES_LOCATOR_WRITE_CHUNK_SIZE = 500
const val GUIDE_WRITE_CHUNK_SIZE = 10_000
