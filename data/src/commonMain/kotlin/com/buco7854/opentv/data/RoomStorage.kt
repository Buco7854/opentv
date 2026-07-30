package com.buco7854.opentv.data

import androidx.room.PooledConnection
import androidx.room.RoomDatabase
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import com.buco7854.opentv.core.model.Channel
import com.buco7854.opentv.core.model.ChannelKind
import com.buco7854.opentv.core.model.Download
import com.buco7854.opentv.core.model.Favorite
import com.buco7854.opentv.core.model.GroupCount
import com.buco7854.opentv.core.model.GroupOverride
import com.buco7854.opentv.core.model.HubSource
import com.buco7854.opentv.core.model.Metadata
import com.buco7854.opentv.core.model.Playlist
import com.buco7854.opentv.core.model.Programme
import com.buco7854.opentv.core.model.ResumePoint
import com.buco7854.opentv.core.model.SeriesGroup
import com.buco7854.opentv.core.model.XtreamSeries
import com.buco7854.opentv.core.storage.ChannelStore
import com.buco7854.opentv.core.storage.DownloadStore
import com.buco7854.opentv.core.storage.EpgStore
import com.buco7854.opentv.core.storage.FavoriteStore
import com.buco7854.opentv.core.storage.GroupOverrideStore
import com.buco7854.opentv.core.storage.HubSourceStore
import com.buco7854.opentv.core.storage.ChannelListing
import com.buco7854.opentv.core.storage.ListingPage
import com.buco7854.opentv.core.storage.MetadataStore
import com.buco7854.opentv.core.storage.PlaylistStore
import com.buco7854.opentv.core.storage.ResumeStore
import com.buco7854.opentv.core.storage.SEARCH_RESULTS_PER_KIND
import com.buco7854.opentv.core.storage.Storage
import com.buco7854.opentv.core.storage.XtreamSeriesStore
import com.buco7854.opentv.core.storage.XtreamSeriesListing
import com.buco7854.opentv.data.db.CatalogDaos
import com.buco7854.opentv.data.db.channelIndexedSearchQuery
import com.buco7854.opentv.data.db.searchIndexQuery
import com.buco7854.opentv.data.db.seriesIndexedSearchQuery
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Room implementation of the core storage ports. Database lifecycle remains explicit. */
class RoomStorage(
    private val db: CatalogDaos,
    private val closeDatabase: () -> Unit,
) : Storage {
    private val roomDatabase = db as? RoomDatabase

    override fun close() = closeDatabase()

    override val playlists = object : PlaylistStore {
        override fun observeAll(): Flow<List<Playlist>> =
            db.playlistDao().observeAll().map { rows -> rows.map { it.toModel() } }

        override suspend fun getAll(): List<Playlist> = db.playlistDao().getAll().map { it.toModel() }
        override suspend fun get(id: Long): Playlist? = db.playlistDao().get(id)?.toModel()
        override fun observe(id: Long): Flow<Playlist?> = db.playlistDao().observe(id).map { it?.toModel() }
        override suspend fun insert(playlist: Playlist): Long = db.playlistDao().insert(playlist.toRow())
        override suspend fun update(playlist: Playlist) = db.playlistDao().update(playlist.toRow())
        override suspend fun delete(id: Long) = db.playlistDao().delete(id)
    }

    override val channels = object : ChannelStore {
        override suspend fun insertAll(channels: List<Channel>) =
            db.channelDao().insertAll(channels.map { it.toRow() })

        override suspend fun deleteForPlaylist(playlistId: Long) {
            val database = roomDatabase
            if (database == null) {
                db.channelDao().deleteForPlaylist(playlistId)
            } else {
                database.withChannelSearchIndexesRebuilt {
                    db.channelDao().deleteForPlaylist(playlistId)
                }
            }
        }

        override suspend fun deleteForPlaylistKind(playlistId: Long, kind: Int) {
            val database = roomDatabase
            if (database == null) {
                db.channelDao().deleteForPlaylistKind(playlistId, kind)
            } else {
                database.withChannelSearchIndexesRebuilt {
                    db.channelDao().deleteForPlaylistKind(playlistId, kind)
                }
            }
        }

        override suspend fun replaceKinds(playlistId: Long, kinds: List<Int>, channels: List<Channel>) {
            val rows = channels.map { it.toRow() }
            val database = roomDatabase
            if (database == null) {
                db.channelDao().replaceKinds(playlistId, kinds, rows)
            } else {
                database.withChannelSearchIndexesRebuilt {
                    db.channelDao().replaceKinds(playlistId, kinds, rows)
                }
            }
        }
        override suspend fun count(playlistId: Long, kind: Int): Int = db.channelDao().count(playlistId, kind)

        override fun observeGroups(playlistId: Long, kind: Int): Flow<List<GroupCount>> =
            db.channelDao().observeGroups(playlistId, kind)

        override fun observeInGroup(playlistId: Long, kind: Int, group: String): Flow<List<Channel>> =
            db.channelDao().observeInGroup(playlistId, kind, group).map { rows -> rows.map { it.toModel() } }

        override suspend fun pageInGroup(
            playlistId: Long,
            kind: Int,
            group: String,
            filter: String,
            limit: Int,
            offset: Int,
        ): ListingPage<ChannelListing> =
            db.channelDao().pageInGroup(playlistId, kind, group, likeTerm(filter), limit, offset)

        override fun observeSeriesInGroup(playlistId: Long, group: String): Flow<List<SeriesGroup>> =
            db.channelDao().observeSeriesInGroup(playlistId, group)

        override suspend fun pageSeriesInGroup(
            playlistId: Long,
            group: String,
            filter: String,
            limit: Int,
            offset: Int,
        ): ListingPage<SeriesGroup> =
            db.channelDao().pageSeriesInGroup(playlistId, group, likeTerm(filter), limit, offset)

        override fun observeAllSeries(playlistId: Long): Flow<List<SeriesGroup>> =
            db.channelDao().observeAllSeries(playlistId)

        override fun observeEpisodes(playlistId: Long, seriesKey: String): Flow<List<Channel>> =
            db.channelDao().observeEpisodes(playlistId, seriesKey).map { rows -> rows.map { it.toModel() } }

        override suspend fun pageEpisodes(
            playlistId: Long,
            seriesKey: String,
            season: Int?,
            limit: Int,
            offset: Int,
        ): ListingPage<ChannelListing> =
            db.channelDao().pageEpisodes(playlistId, seriesKey, season, limit, offset)

        override suspend fun episodeSeasons(playlistId: Long, seriesKey: String): List<Int> =
            db.channelDao().episodeSeasons(playlistId, seriesKey)

        override fun observeCount(playlistId: Long, kind: Int): Flow<Int> =
            db.channelDao().observeCount(playlistId, kind)

        override fun observeByUrls(playlistId: Long, kind: Int, urls: List<String>): Flow<List<Channel>> =
            db.channelDao().observeByUrls(playlistId, kind, urls).map { rows -> rows.map { it.toModel() } }

        override suspend fun search(
            playlistId: Long,
            query: String,
            limitPerKind: Int,
        ): List<Channel> {
            val boundedLimit = limitPerKind.coerceIn(0, SEARCH_RESULTS_PER_KIND)
            if (boundedLimit == 0) return emptyList()
            val request = searchIndexQuery(query) ?: return emptyList()
            return listOf(ChannelKind.LIVE, ChannelKind.MOVIE, ChannelKind.SERIES)
                .flatMap { kind ->
                    val rows = db.channelDao().searchPrefixKind(
                        playlistId = playlistId,
                        kind = kind,
                        query = request.normalizedQuery,
                        upperBound = request.normalizedQuery + SEARCH_PREFIX_CEILING,
                        limit = boundedLimit,
                    ).toMutableList()
                    if (rows.size < boundedLimit) {
                        rows += db.channelDao().searchIndexed(
                            channelIndexedSearchQuery(
                                playlistId = playlistId,
                                kind = kind,
                                query = request,
                                wordBoundary = true,
                                limit = boundedLimit - rows.size,
                            )
                        )
                    }
                    if (request.substringMatchExpression != null && rows.size < boundedLimit) {
                        rows += db.channelDao().searchIndexed(
                            channelIndexedSearchQuery(
                                playlistId = playlistId,
                                kind = kind,
                                query = request,
                                wordBoundary = false,
                                limit = boundedLimit - rows.size,
                            )
                        )
                    }
                    rows
                }
                .map { it.toModel() }
        }

        override suspend fun get(id: Long): Channel? = db.channelDao().get(id)?.toModel()

        // Chunked: SQLite binds a bounded number of variables per statement.
        override suspend fun getMany(ids: List<Long>): List<Channel> =
            ids.distinct().chunked(MAX_BOUND_VARIABLES)
                .flatMap { db.channelDao().byIds(it) }
                .map { it.toModel() }

        override suspend fun getByUrl(playlistId: Long, url: String): Channel? =
            db.channelDao().getByUrl(playlistId, url)?.toModel()

        override suspend fun distinctLiveTvgIds(playlistId: Long): List<String> =
            db.channelDao().distinctLiveTvgIds(playlistId)

        override suspend fun countEpisodes(playlistId: Long, seriesKey: String): Int =
            db.channelDao().countEpisodes(playlistId, seriesKey)

        override suspend fun retagGroup(playlistId: Long, groupTitle: String, kind: Int) =
            db.channelDao().retagGroup(playlistId, groupTitle, kind)

        override suspend fun inGroup(playlistId: Long, groupTitle: String): List<Channel> =
            db.channelDao().inGroup(playlistId, groupTitle).map { it.toModel() }

        override suspend fun updateAll(channels: List<Channel>) =
            db.channelDao().updateAll(channels.map { it.toRow() })

        override suspend fun deleteEpisodes(playlistId: Long, seriesKey: String) =
            db.channelDao().deleteEpisodes(playlistId, seriesKey)
    }

    override val xtreamSeries = object : XtreamSeriesStore {
        override suspend fun insertAll(series: List<XtreamSeries>) =
            db.xtreamSeriesDao().insertAll(series.map { it.toRow() })

        override suspend fun deleteForPlaylist(playlistId: Long) {
            val database = roomDatabase
            if (database == null) {
                db.xtreamSeriesDao().deleteForPlaylist(playlistId)
            } else {
                database.withSeriesSearchIndexesRebuilt {
                    db.xtreamSeriesDao().deleteForPlaylist(playlistId)
                }
            }
        }

        override suspend fun replaceAll(playlistId: Long, series: List<XtreamSeries>) {
            val rows = series.map { it.toRow() }
            val database = roomDatabase
            if (database == null) {
                db.xtreamSeriesDao().replaceAll(playlistId, rows)
            } else {
                database.withSeriesSearchIndexesRebuilt {
                    db.xtreamSeriesDao().replaceAll(playlistId, rows)
                }
            }
        }

        override suspend fun count(playlistId: Long): Int = db.xtreamSeriesDao().count(playlistId)

        override fun observeCategories(playlistId: Long): Flow<List<GroupCount>> =
            db.xtreamSeriesDao().observeCategories(playlistId)

        override fun observeInCategory(playlistId: Long, category: String): Flow<List<XtreamSeries>> =
            db.xtreamSeriesDao().observeInCategory(playlistId, category).map { rows -> rows.map { it.toModel() } }

        override suspend fun pageInCategory(
            playlistId: Long,
            category: String,
            filter: String,
            limit: Int,
            offset: Int,
        ): ListingPage<XtreamSeriesListing> =
            db.xtreamSeriesDao().pageInCategory(
                playlistId, category, likeTerm(filter), limit, offset,
            )

        override fun observeAll(playlistId: Long): Flow<List<XtreamSeries>> =
            db.xtreamSeriesDao().observeAll(playlistId).map { rows -> rows.map { it.toModel() } }

        override fun observeCount(playlistId: Long): Flow<Int> = db.xtreamSeriesDao().observeCount(playlistId)

        override suspend fun get(playlistId: Long, seriesId: Long): XtreamSeries? =
            db.xtreamSeriesDao().get(playlistId, seriesId)?.toModel()

        override suspend fun search(
            playlistId: Long,
            query: String,
            limit: Int,
        ): List<XtreamSeries> {
            val boundedLimit = limit.coerceIn(0, SEARCH_RESULTS_PER_KIND)
            if (boundedLimit == 0) return emptyList()
            val request = searchIndexQuery(query) ?: return emptyList()
            val rows = db.xtreamSeriesDao().searchPrefix(
                playlistId = playlistId,
                query = request.normalizedQuery,
                upperBound = request.normalizedQuery + SEARCH_PREFIX_CEILING,
                limit = boundedLimit,
            ).toMutableList()
            if (rows.size < boundedLimit) {
                rows += db.xtreamSeriesDao().searchIndexed(
                    seriesIndexedSearchQuery(
                        playlistId = playlistId,
                        query = request,
                        wordBoundary = true,
                        limit = boundedLimit - rows.size,
                    )
                )
            }
            if (request.substringMatchExpression != null && rows.size < boundedLimit) {
                rows += db.xtreamSeriesDao().searchIndexed(
                    seriesIndexedSearchQuery(
                        playlistId = playlistId,
                        query = request,
                        wordBoundary = false,
                        limit = boundedLimit - rows.size,
                    )
                )
            }
            return rows.map { it.toModel() }
        }

        override suspend fun setEpisodesFetched(playlistId: Long, seriesId: Long, fetchedAtMs: Long) =
            db.xtreamSeriesDao().setEpisodesFetched(playlistId, seriesId, fetchedAtMs)
    }

    override val epg = object : EpgStore {
        override suspend fun insertAll(programmes: List<Programme>) =
            db.epgDao().insertAll(programmes.map { it.toRow() })

        override suspend fun deleteForPlaylist(playlistId: Long) = db.epgDao().deleteForPlaylist(playlistId)

        override suspend fun deleteFrom(playlistId: Long, fromMs: Long) =
            db.epgDao().deleteFrom(playlistId, fromMs)

        override suspend fun prune(playlistId: Long, beforeMs: Long) =
            db.epgDao().prune(playlistId, beforeMs)

        override suspend fun nowAiring(playlistId: Long, now: Long): List<Programme> =
            db.epgDao().nowAiring(playlistId, now).map { it.toModel() }

        override suspend fun guideSince(playlistId: Long, tvgId: String, fromMs: Long, limit: Int): List<Programme> =
            db.epgDao().guideSince(playlistId, tvgId, fromMs, limit).map { it.toModel() }

        override fun observeGuideIds(playlistId: Long): Flow<List<String>> =
            db.epgDao().observeGuideIds(playlistId)
    }

    override val groupOverrides = object : GroupOverrideStore {
        override suspend fun forPlaylist(playlistId: Long): List<GroupOverride> =
            db.groupOverrideDao().forPlaylist(playlistId).map { it.toModel() }

        override suspend fun upsert(override: GroupOverride) = db.groupOverrideDao().upsert(override.toRow())

        override suspend fun remove(playlistId: Long, groupTitle: String) =
            db.groupOverrideDao().remove(playlistId, groupTitle)

        override suspend fun deleteForPlaylist(playlistId: Long) =
            db.groupOverrideDao().deleteForPlaylist(playlistId)
    }

    override val favorites = object : FavoriteStore {
        override fun observeAll(playlistId: Long): Flow<List<Favorite>> =
            db.favoriteDao().observeAll(playlistId).map { rows -> rows.map { it.toModel() } }

        override suspend fun getAll(playlistId: Long): List<Favorite> =
            db.favoriteDao().getAll(playlistId).map { it.toModel() }

        override suspend fun get(playlistId: Long, key: String): Favorite? =
            db.favoriteDao().get(playlistId, key)?.toModel()

        override suspend fun add(favorite: Favorite) = db.favoriteDao().add(favorite.toRow())
        override suspend fun remove(playlistId: Long, key: String) = db.favoriteDao().remove(playlistId, key)
        override suspend fun deleteForPlaylist(playlistId: Long) = db.favoriteDao().deleteForPlaylist(playlistId)
    }

    override val resume = object : ResumeStore {
        override suspend fun get(url: String): ResumePoint? = db.resumeDao().get(url)?.toModel()

        override fun observeAll(): Flow<List<ResumePoint>> =
            db.resumeDao().observeAll().map { rows -> rows.map { it.toModel() } }

        override suspend fun getAll(): List<ResumePoint> = db.resumeDao().getAll().map { it.toModel() }
        override suspend fun upsert(point: ResumePoint) = db.resumeDao().upsert(point.toRow())
        override suspend fun delete(url: String) = db.resumeDao().delete(url)
        override suspend fun deleteForPlaylist(playlistId: Long) = db.resumeDao().deleteForPlaylist(playlistId)
        override suspend fun prune(before: Long) = db.resumeDao().prune(before)
    }

    override val metadata = object : MetadataStore {
        override suspend fun get(cacheKey: String): Metadata? = db.metadataDao().get(cacheKey)?.toModel()
        override suspend fun upsert(metadata: Metadata) = db.metadataDao().upsert(metadata.toRow())
    }

    override val downloads = object : DownloadStore {
        override fun observeAll(): Flow<List<Download>> =
            db.downloadDao().observeAll().map { rows -> rows.map { it.toModel() } }

        override suspend fun get(id: Long): Download? = db.downloadDao().get(id)?.toModel()

        override suspend fun getByStatus(status: Int): List<Download> =
            db.downloadDao().getByStatus(status).map { it.toModel() }

        override suspend fun getByStatuses(statuses: List<Int>): List<Download> =
            db.downloadDao().getByStatuses(statuses).map { it.toModel() }

        override suspend fun findByUrlWithStatus(url: String, statuses: List<Int>): Download? =
            db.downloadDao().findByUrlWithStatus(url, statuses)?.toModel()

        override suspend fun findByHubContentWithStatus(
            hubSourceId: Long,
            contentId: String,
            statuses: List<Int>,
        ): Download? =
            db.downloadDao().findByHubContentWithStatus(hubSourceId, contentId, statuses)?.toModel()

        override suspend fun insert(download: Download): Long = db.downloadDao().insert(download.toRow())
        override suspend fun update(download: Download) = db.downloadDao().update(download.toRow())

        override suspend fun updateProgressIfStatus(
            id: Long,
            downloaded: Long,
            total: Long,
            expectedStatuses: List<Int>,
            status: Int,
        ): Boolean = db.downloadDao().updateProgressIfStatus(
            id, downloaded, total, expectedStatuses, status
        ) > 0

        override suspend fun updateStatusIfStatus(
            id: Long,
            expectedStatuses: List<Int>,
            status: Int,
            error: String?,
        ): Boolean = db.downloadDao().updateStatusIfStatus(
            id, expectedStatuses, status, error
        ) > 0

        override suspend fun updateUrlIfStatus(
            id: Long,
            url: String,
            expectedStatuses: List<Int>,
        ): Boolean = db.downloadDao().updateUrlIfStatus(id, url, expectedStatuses) > 0

        override suspend fun delete(id: Long) = db.downloadDao().delete(id)
    }

    override val hubSources = object : HubSourceStore {
        override fun observeAll(): Flow<List<HubSource>> =
            db.hubSourceDao().observeAll().map { rows -> rows.map { it.toModel() } }

        override suspend fun getAll(): List<HubSource> = db.hubSourceDao().getAll().map { it.toModel() }
        override suspend fun get(id: Long): HubSource? = db.hubSourceDao().get(id)?.toModel()
        override suspend fun upsert(source: HubSource): Long = db.hubSourceDao().upsert(source.toRow())
        override suspend fun delete(id: Long) = db.hubSourceDao().delete(id)

        override suspend fun updateIdentity(
            id: Long,
            userId: String?,
            username: String?,
            role: String?,
            seenMs: Long,
        ) = db.hubSourceDao().updateIdentity(id, userId, username, role, seenMs)

        override suspend fun clearIdentity(id: Long) = db.hubSourceDao().clearIdentity(id)
    }
}

/**
 * Rebuilds both catalog FTS5 indexes around [block].
 *
 * The base-table mutation, index rebuild and trigger restoration share the caller's Room writer
 * transaction, so readers see either the complete old or complete new catalog. This is public
 * only for the server database's merged catalog/account deletion transaction.
 */
suspend fun <R> RoomDatabase.withCatalogSearchIndexesRebuilt(
    block: suspend () -> R,
): R = withChannelSearchIndexesRebuilt {
    withSeriesSearchIndexesRebuilt {
        block()
    }
}

/**
 * FTS5 external-content delete triggers become disproportionately expensive during a wholesale
 * replacement. Drop them transactionally around the base-table mutation, then let FTS5 rebuild
 * its index in one set-based operation.
 */
private suspend fun <R> RoomDatabase.withChannelSearchIndexesRebuilt(
    block: suspend () -> R,
): R = withSearchIndexesRebuilt(
    CHANNEL_SEARCH_TRIGGER_NAMES,
    CHANNEL_SEARCH_TABLES,
    block,
)

private suspend fun <R> RoomDatabase.withSeriesSearchIndexesRebuilt(
    block: suspend () -> R,
): R = withSearchIndexesRebuilt(
    SERIES_SEARCH_TRIGGER_NAMES,
    SERIES_SEARCH_TABLES,
    block,
)

private suspend fun <R> RoomDatabase.withSearchIndexesRebuilt(
    triggerNames: List<String>,
    searchTables: List<String>,
    block: suspend () -> R,
): R = useWriterConnection { connection ->
    connection.immediateTransaction {
        val triggerSql = connection.readTriggerSql(triggerNames)
        triggerNames.forEach { name ->
            connection.execute("DROP TRIGGER ${name.quotedIdentifier()}")
        }
        val result = block()
        searchTables.forEach { table ->
            connection.execute("INSERT INTO $table($table) VALUES('rebuild')")
        }
        triggerSql.forEach { connection.execute(it) }
        result
    }
}

private suspend fun PooledConnection.readTriggerSql(names: List<String>): List<String> {
    val sqlByName = usePrepared(
        "SELECT name, sql FROM sqlite_master WHERE type = 'trigger' " +
            "AND name IN (${names.joinToString { "?" }})",
    ) { statement ->
        names.forEachIndexed { index, name -> statement.bindText(index + 1, name) }
        buildMap {
            while (statement.step()) {
                put(statement.getText(0), statement.getText(1))
            }
        }
    }
    check(sqlByName.keys == names.toSet()) {
        "Catalog search triggers are missing: ${names.toSet() - sqlByName.keys}"
    }
    return names.map(sqlByName::getValue)
}

private suspend fun PooledConnection.execute(sql: String) =
    usePrepared(sql) { statement -> statement.step() }

private fun String.quotedIdentifier(): String = "\"${replace("\"", "\"\"")}\""

private val CHANNEL_SEARCH_TABLES = listOf("channels_fts", "channels_words_fts")
private val CHANNEL_SEARCH_TRIGGER_NAMES = listOf(
    "opentv_channels_fts_ai",
    "opentv_channels_fts_ad",
    "opentv_channels_fts_au",
    "opentv_channels_words_fts_ai",
    "opentv_channels_words_fts_ad",
    "opentv_channels_words_fts_au",
)
private val SERIES_SEARCH_TABLES = listOf("xtream_series_fts", "xtream_series_words_fts")
private val SERIES_SEARCH_TRIGGER_NAMES = listOf(
    "opentv_xtream_series_fts_ai",
    "opentv_xtream_series_fts_ad",
    "opentv_xtream_series_fts_au",
    "opentv_xtream_series_words_fts_ai",
    "opentv_xtream_series_words_fts_ad",
    "opentv_xtream_series_words_fts_au",
)

/** SQLite's bound-variable ceiling, with room to spare for the rest of a statement. */
private const val MAX_BOUND_VARIABLES = 500

/** The greatest valid Unicode scalar keeps non-BMP titles inside a BINARY prefix range. */
private const val SEARCH_PREFIX_CEILING = "\uDBFF\uDFFF"

/**
 * A caller's filter is data, not pattern: LIKE reads %, _ and the escape character itself as
 * syntax, so "100%" would otherwise match every row. Search moved to indexed prefix and FTS
 * matching, but the in-category listing filter is still a LIKE, and it still needs this.
 */
private fun likeTerm(query: String): String =
    query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
