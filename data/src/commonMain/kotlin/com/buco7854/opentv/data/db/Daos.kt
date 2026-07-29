package com.buco7854.opentv.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.RoomRawQuery
import androidx.room.Transaction
import androidx.room.Update
import com.buco7854.opentv.core.model.GroupCount
import com.buco7854.opentv.core.model.SeriesGroup
import com.buco7854.opentv.core.storage.ChannelListing
import com.buco7854.opentv.core.storage.ListingPage
import com.buco7854.opentv.core.storage.XtreamSeriesListing
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY id")
    fun observeAll(): Flow<List<PlaylistRow>>

    @Query("SELECT * FROM playlists")
    suspend fun getAll(): List<PlaylistRow>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun get(id: Long): PlaylistRow?

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun observe(id: Long): Flow<PlaylistRow?>

    @Insert
    suspend fun insert(p: PlaylistRow): Long

    @Update
    suspend fun update(p: PlaylistRow)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ChannelDao {
    @Insert
    suspend fun insertAll(channels: List<ChannelRow>)

    @Query("DELETE FROM channels WHERE playlistId = :playlistId")
    suspend fun deleteForPlaylist(playlistId: Long)

    @Query("DELETE FROM channels WHERE playlistId = :playlistId AND kind = :kind")
    suspend fun deleteForPlaylistKind(playlistId: Long, kind: Int)

    /** Delete [kinds] then insert [rows] in one transaction, so observers see one atomic swap. */
    @Transaction
    suspend fun replaceKinds(playlistId: Long, kinds: List<Int>, rows: List<ChannelRow>) {
        kinds.forEach { deleteForPlaylistKind(playlistId, it) }
        insertAll(rows)
    }

    @Query("SELECT COUNT(*) FROM channels WHERE playlistId = :playlistId AND kind = :kind")
    suspend fun count(playlistId: Long, kind: Int): Int

    @Query(
        "SELECT groupTitle, COUNT(*) as count FROM channels " +
            "WHERE playlistId = :playlistId AND kind = :kind " +
            "GROUP BY groupTitle ORDER BY MIN(position)"
    )
    fun observeGroups(playlistId: Long, kind: Int): Flow<List<GroupCount>>

    @Query(
        "SELECT * FROM channels WHERE playlistId = :playlistId AND kind = :kind " +
            "AND groupTitle = :group ORDER BY position"
    )
    fun observeInGroup(playlistId: Long, kind: Int, group: String): Flow<List<ChannelRow>>

    @Query(
        "SELECT id, playlistId, name, url, logo, groupTitle, tvgId, kind, seriesKey, season, " +
            "episode, xtreamStreamId, catchupDays, catchupSource, durationSecs, airDate " +
            "FROM channels WHERE playlistId = :playlistId AND kind = :kind " +
            "AND groupTitle = :group AND (:filter = '' OR name LIKE '%' || :filter || '%' ESCAPE '\\') " +
            "ORDER BY position, id LIMIT :limit OFFSET :offset"
    )
    suspend fun inGroupPage(
        playlistId: Long,
        kind: Int,
        group: String,
        filter: String,
        limit: Int,
        offset: Int,
    ): List<ChannelListing>

    @Query(
        "SELECT COUNT(*) FROM channels WHERE playlistId = :playlistId AND kind = :kind " +
            "AND groupTitle = :group AND (:filter = '' OR name LIKE '%' || :filter || '%' ESCAPE '\\')"
    )
    suspend fun countInGroup(playlistId: Long, kind: Int, group: String, filter: String): Int

    @Transaction
    suspend fun pageInGroup(
        playlistId: Long,
        kind: Int,
        group: String,
        filter: String,
        limit: Int,
        offset: Int,
    ): ListingPage<ChannelListing> = ListingPage(
        inGroupPage(playlistId, kind, group, filter, limit, offset),
        countInGroup(playlistId, kind, group, filter),
    )

    @Query(
        "SELECT seriesKey, COUNT(*) as count, MIN(logo) as logo, MIN(groupTitle) as groupTitle " +
            "FROM channels WHERE playlistId = :playlistId AND kind = 2 AND groupTitle = :group " +
            "GROUP BY seriesKey ORDER BY seriesKey"
    )
    fun observeSeriesInGroup(playlistId: Long, group: String): Flow<List<SeriesGroup>>

    @Query(
        "SELECT seriesKey, COUNT(*) as count, MIN(logo) as logo, MIN(groupTitle) as groupTitle " +
            "FROM channels WHERE playlistId = :playlistId AND kind = 2 AND groupTitle = :group " +
            "AND seriesKey IS NOT NULL AND seriesKey NOT LIKE 'xs:%' " +
            "AND (:filter = '' OR seriesKey LIKE '%' || :filter || '%' ESCAPE '\\') " +
            "GROUP BY seriesKey ORDER BY seriesKey LIMIT :limit OFFSET :offset"
    )
    suspend fun seriesInGroupPage(
        playlistId: Long,
        group: String,
        filter: String,
        limit: Int,
        offset: Int,
    ): List<SeriesGroup>

    @Query(
        "SELECT COUNT(DISTINCT seriesKey) FROM channels " +
            "WHERE playlistId = :playlistId AND kind = 2 AND groupTitle = :group " +
            "AND seriesKey IS NOT NULL AND seriesKey NOT LIKE 'xs:%' " +
            "AND (:filter = '' OR seriesKey LIKE '%' || :filter || '%' ESCAPE '\\')"
    )
    suspend fun countSeriesInGroup(playlistId: Long, group: String, filter: String): Int

    @Transaction
    suspend fun pageSeriesInGroup(
        playlistId: Long,
        group: String,
        filter: String,
        limit: Int,
        offset: Int,
    ): ListingPage<SeriesGroup> = ListingPage(
        seriesInGroupPage(playlistId, group, filter, limit, offset),
        countSeriesInGroup(playlistId, group, filter),
    )

    @Query(
        "SELECT * FROM channels WHERE playlistId = :playlistId AND kind = 2 AND seriesKey = :seriesKey " +
            "ORDER BY season, episode, position"
    )
    fun observeEpisodes(playlistId: Long, seriesKey: String): Flow<List<ChannelRow>>

    @Query(
        "SELECT id, playlistId, name, url, logo, groupTitle, tvgId, kind, seriesKey, season, " +
            "episode, xtreamStreamId, catchupDays, catchupSource, durationSecs, airDate " +
            "FROM channels WHERE playlistId = :playlistId AND kind = 2 AND seriesKey = :seriesKey " +
            "AND (:season IS NULL OR season = :season) " +
            "ORDER BY season, episode, position, id LIMIT :limit OFFSET :offset"
    )
    suspend fun episodesPage(
        playlistId: Long,
        seriesKey: String,
        season: Int?,
        limit: Int,
        offset: Int,
    ): List<ChannelListing>

    @Query(
        "SELECT COUNT(*) FROM channels WHERE playlistId = :playlistId AND kind = 2 " +
            "AND seriesKey = :seriesKey AND (:season IS NULL OR season = :season)"
    )
    suspend fun countEpisodes(playlistId: Long, seriesKey: String, season: Int?): Int

    @Transaction
    suspend fun pageEpisodes(
        playlistId: Long,
        seriesKey: String,
        season: Int?,
        limit: Int,
        offset: Int,
    ): ListingPage<ChannelListing> = ListingPage(
        episodesPage(playlistId, seriesKey, season, limit, offset),
        countEpisodes(playlistId, seriesKey, season),
    )

    @Query(
        "SELECT DISTINCT season FROM channels WHERE playlistId = :playlistId AND kind = 2 " +
            "AND seriesKey = :seriesKey AND season IS NOT NULL ORDER BY season"
    )
    suspend fun episodeSeasons(playlistId: Long, seriesKey: String): List<Int>

    @Query(
        "SELECT * FROM channels " +
            "WHERE playlistId = :playlistId AND kind = :kind " +
            "AND searchName >= :query AND searchName < :upperBound " +
            "ORDER BY searchName, position, id LIMIT :limit"
    )
    suspend fun searchPrefixKind(
        playlistId: Long,
        kind: Int,
        query: String,
        upperBound: String,
        limit: Int,
    ): List<ChannelRow>

    /** FTS5 is a callback-managed sidecar because Room 2.x cannot declare FTS5 entities. */
    @RawQuery
    suspend fun searchIndexed(query: RoomRawQuery): List<ChannelRow>

    @Query("SELECT COUNT(*) FROM channels WHERE playlistId = :playlistId AND kind = :kind")
    fun observeCount(playlistId: Long, kind: Int): Flow<Int>

    @Query("SELECT * FROM channels WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<ChannelRow>

    @Query("SELECT * FROM channels WHERE id = :id")
    suspend fun get(id: Long): ChannelRow?

    @Query("SELECT * FROM channels WHERE playlistId = :playlistId AND url = :url LIMIT 1")
    suspend fun getByUrl(playlistId: Long, url: String): ChannelRow?

    @Query("SELECT DISTINCT tvgId FROM channels WHERE playlistId = :playlistId AND kind = 0 AND tvgId IS NOT NULL")
    suspend fun distinctLiveTvgIds(playlistId: Long): List<String>

    @Query("SELECT COUNT(*) FROM channels WHERE playlistId = :playlistId AND seriesKey = :seriesKey")
    suspend fun countEpisodes(playlistId: Long, seriesKey: String): Int

    @Query(
        "SELECT * FROM channels WHERE playlistId = :playlistId AND kind = :kind " +
            "AND url IN (:urls) ORDER BY name"
    )
    fun observeByUrls(playlistId: Long, kind: Int, urls: List<String>): Flow<List<ChannelRow>>

    @Query(
        "SELECT seriesKey, COUNT(*) as count, MIN(logo) as logo, MIN(groupTitle) as groupTitle " +
            "FROM channels WHERE playlistId = :playlistId AND kind = 2 " +
            "GROUP BY seriesKey ORDER BY seriesKey"
    )
    fun observeAllSeries(playlistId: Long): Flow<List<SeriesGroup>>

    @Query(
        "UPDATE channels SET kind = :kind, seriesKey = NULL, season = NULL, episode = NULL " +
            "WHERE playlistId = :playlistId AND groupTitle = :groupTitle"
    )
    suspend fun retagGroup(playlistId: Long, groupTitle: String, kind: Int)

    @Query("SELECT * FROM channels WHERE playlistId = :playlistId AND groupTitle = :groupTitle")
    suspend fun inGroup(playlistId: Long, groupTitle: String): List<ChannelRow>

    @Update
    suspend fun updateAll(rows: List<ChannelRow>)

    @Query("DELETE FROM channels WHERE playlistId = :playlistId AND seriesKey = :seriesKey")
    suspend fun deleteEpisodes(playlistId: Long, seriesKey: String)
}

@Dao
interface XtreamSeriesDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(series: List<XtreamSeriesRow>)

    @Query("DELETE FROM xtream_series WHERE playlistId = :playlistId")
    suspend fun deleteForPlaylist(playlistId: Long)

    /** Delete then re-insert the catalog in one transaction, for one atomic swap. */
    @Transaction
    suspend fun replaceAll(playlistId: Long, rows: List<XtreamSeriesRow>) {
        deleteForPlaylist(playlistId)
        insertAll(rows)
    }

    @Query("SELECT COUNT(*) FROM xtream_series WHERE playlistId = :playlistId")
    suspend fun count(playlistId: Long): Int

    @Query(
        "SELECT categoryName as groupTitle, COUNT(*) as count FROM xtream_series " +
            "WHERE playlistId = :playlistId GROUP BY categoryName ORDER BY categoryName"
    )
    fun observeCategories(playlistId: Long): Flow<List<GroupCount>>

    @Query(
        "SELECT * FROM xtream_series WHERE playlistId = :playlistId " +
            "AND categoryName = :category ORDER BY name"
    )
    fun observeInCategory(playlistId: Long, category: String): Flow<List<XtreamSeriesRow>>

    @Query(
        "SELECT playlistId, seriesId, name, cover, genre, rating FROM xtream_series " +
            "WHERE playlistId = :playlistId AND categoryName = :category " +
            "AND (:filter = '' OR name LIKE '%' || :filter || '%' ESCAPE '\\') " +
            "ORDER BY name, seriesId LIMIT :limit OFFSET :offset"
    )
    suspend fun inCategoryPage(
        playlistId: Long,
        category: String,
        filter: String,
        limit: Int,
        offset: Int,
    ): List<XtreamSeriesListing>

    @Query(
        "SELECT COUNT(*) FROM xtream_series WHERE playlistId = :playlistId " +
            "AND categoryName = :category " +
            "AND (:filter = '' OR name LIKE '%' || :filter || '%' ESCAPE '\\')"
    )
    suspend fun countInCategory(playlistId: Long, category: String, filter: String): Int

    @Transaction
    suspend fun pageInCategory(
        playlistId: Long,
        category: String,
        filter: String,
        limit: Int,
        offset: Int,
    ): ListingPage<XtreamSeriesListing> = ListingPage(
        inCategoryPage(playlistId, category, filter, limit, offset),
        countInCategory(playlistId, category, filter),
    )

    @Query("SELECT * FROM xtream_series WHERE playlistId = :playlistId AND seriesId = :seriesId")
    suspend fun get(playlistId: Long, seriesId: Long): XtreamSeriesRow?

    @Query("SELECT COUNT(*) FROM xtream_series WHERE playlistId = :playlistId")
    fun observeCount(playlistId: Long): Flow<Int>

    @Query("SELECT * FROM xtream_series WHERE playlistId = :playlistId ORDER BY name")
    fun observeAll(playlistId: Long): Flow<List<XtreamSeriesRow>>

    @Query(
        "SELECT * FROM xtream_series WHERE playlistId = :playlistId " +
            "AND searchName >= :query AND searchName < :upperBound " +
            "ORDER BY searchName, seriesId LIMIT :limit"
    )
    suspend fun searchPrefix(
        playlistId: Long,
        query: String,
        upperBound: String,
        limit: Int,
    ): List<XtreamSeriesRow>

    /** See [ChannelDao.searchIndexed]. */
    @RawQuery
    suspend fun searchIndexed(query: RoomRawQuery): List<XtreamSeriesRow>

    @Query(
        "UPDATE xtream_series SET episodesFetchedAtMs = :fetchedAtMs " +
            "WHERE playlistId = :playlistId AND seriesId = :seriesId"
    )
    suspend fun setEpisodesFetched(playlistId: Long, seriesId: Long, fetchedAtMs: Long)
}

@Dao
interface EpgDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(programmes: List<ProgrammeRow>)

    @Query("DELETE FROM programmes WHERE playlistId = :playlistId")
    suspend fun deleteForPlaylist(playlistId: Long)

    @Query("DELETE FROM programmes WHERE playlistId = :playlistId AND startMs >= :fromMs")
    suspend fun deleteFrom(playlistId: Long, fromMs: Long)

    @Query("DELETE FROM programmes WHERE playlistId = :playlistId AND endMs <= :beforeMs")
    suspend fun prune(playlistId: Long, beforeMs: Long)

    @Query(
        "SELECT * FROM programmes WHERE playlistId = :playlistId " +
            "AND startMs <= :now AND endMs > :now"
    )
    suspend fun nowAiring(playlistId: Long, now: Long): List<ProgrammeRow>

    @Query(
        "SELECT * FROM programmes WHERE playlistId = :playlistId AND tvgId = :tvgId " +
            "AND endMs > :fromMs ORDER BY startMs LIMIT :limit"
    )
    suspend fun guideSince(playlistId: Long, tvgId: String, fromMs: Long, limit: Int): List<ProgrammeRow>

    @Query("SELECT DISTINCT tvgId FROM programmes WHERE playlistId = :playlistId")
    fun observeGuideIds(playlistId: Long): Flow<List<String>>
}

@Dao
interface GroupOverrideDao {
    @Query("SELECT * FROM group_overrides WHERE playlistId = :playlistId")
    suspend fun forPlaylist(playlistId: Long): List<GroupOverrideRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(override: GroupOverrideRow)

    @Query("DELETE FROM group_overrides WHERE playlistId = :playlistId AND groupTitle = :groupTitle")
    suspend fun remove(playlistId: Long, groupTitle: String)

    @Query("DELETE FROM group_overrides WHERE playlistId = :playlistId")
    suspend fun deleteForPlaylist(playlistId: Long)
}

@Dao
interface ResumeDao {
    @Query("SELECT * FROM resume_points WHERE url = :url")
    suspend fun get(url: String): ResumePointRow?

    @Query("SELECT * FROM resume_points")
    fun observeAll(): Flow<List<ResumePointRow>>

    @Query("SELECT * FROM resume_points")
    suspend fun getAll(): List<ResumePointRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(point: ResumePointRow)

    @Query("DELETE FROM resume_points WHERE url = :url")
    suspend fun delete(url: String)

    // Resume points are keyed by URL; a playlist's belong to its channels.
    @Query("DELETE FROM resume_points WHERE url IN (SELECT url FROM channels WHERE playlistId = :playlistId)")
    suspend fun deleteForPlaylist(playlistId: Long)

    @Query("DELETE FROM resume_points WHERE updatedMs < :before")
    suspend fun prune(before: Long)
}

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites WHERE playlistId = :playlistId")
    fun observeAll(playlistId: Long): Flow<List<FavoriteRow>>

    @Query("SELECT * FROM favorites WHERE playlistId = :playlistId")
    suspend fun getAll(playlistId: Long): List<FavoriteRow>

    @Query("SELECT * FROM favorites WHERE playlistId = :playlistId AND `key` = :key")
    suspend fun get(playlistId: Long, key: String): FavoriteRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(favorite: FavoriteRow)

    @Query("DELETE FROM favorites WHERE playlistId = :playlistId AND `key` = :key")
    suspend fun remove(playlistId: Long, key: String)

    @Query("DELETE FROM favorites WHERE playlistId = :playlistId")
    suspend fun deleteForPlaylist(playlistId: Long)
}

@Dao
interface MetadataDao {
    @Query("SELECT * FROM metadata WHERE cacheKey = :cacheKey")
    suspend fun get(cacheKey: String): MetadataRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(metadata: MetadataRow)
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdMs DESC")
    fun observeAll(): Flow<List<DownloadRow>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun get(id: Long): DownloadRow?

    @Query("SELECT * FROM downloads WHERE status = :status")
    suspend fun getByStatus(status: Int): List<DownloadRow>

    @Query("SELECT * FROM downloads WHERE status IN (:statuses)")
    suspend fun getByStatuses(statuses: List<Int>): List<DownloadRow>

    @Query("SELECT * FROM downloads WHERE url = :url AND status IN (:statuses) LIMIT 1")
    suspend fun findByUrlWithStatus(url: String, statuses: List<Int>): DownloadRow?

    @Query(
        "SELECT * FROM downloads WHERE hubSourceId = :hubSourceId AND contentId = :contentId " +
            "AND status IN (:statuses) LIMIT 1"
    )
    suspend fun findByHubContentWithStatus(
        hubSourceId: Long,
        contentId: String,
        statuses: List<Int>,
    ): DownloadRow?

    @Insert
    suspend fun insert(d: DownloadRow): Long

    @Update
    suspend fun update(d: DownloadRow)

    @Query(
        "UPDATE downloads SET downloadedBytes = :downloaded, totalBytes = :total, status = :status " +
            "WHERE id = :id AND status IN (:expectedStatuses)"
    )
    suspend fun updateProgressIfStatus(
        id: Long,
        downloaded: Long,
        total: Long,
        expectedStatuses: List<Int>,
        status: Int,
    ): Int

    @Query(
        "UPDATE downloads SET status = :status, error = :error " +
            "WHERE id = :id AND status IN (:expectedStatuses)"
    )
    suspend fun updateStatusIfStatus(
        id: Long,
        expectedStatuses: List<Int>,
        status: Int,
        error: String?,
    ): Int

    @Query(
        "UPDATE downloads SET url = :url, error = NULL " +
            "WHERE id = :id AND status IN (:expectedStatuses)"
    )
    suspend fun updateUrlIfStatus(
        id: Long,
        url: String,
        expectedStatuses: List<Int>,
    ): Int

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface HubSourceDao {
    @Query("SELECT * FROM hub_sources ORDER BY addedMs")
    fun observeAll(): Flow<List<HubSourceRow>>

    @Query("SELECT * FROM hub_sources ORDER BY addedMs")
    suspend fun getAll(): List<HubSourceRow>

    @Query("SELECT * FROM hub_sources WHERE id = :id")
    suspend fun get(id: Long): HubSourceRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(source: HubSourceRow): Long

    @Query("DELETE FROM hub_sources WHERE id = :id")
    suspend fun delete(id: Long)

    @Query(
        "UPDATE hub_sources SET userId = :userId, username = :username, role = :role, " +
            "lastSeenMs = :seenMs WHERE id = :id"
    )
    suspend fun updateIdentity(id: Long, userId: String?, username: String?, role: String?, seenMs: Long)

    @Query(
        "UPDATE hub_sources SET userId = NULL, username = NULL, role = NULL, " +
            "lastSeenMs = NULL WHERE id = :id"
    )
    suspend fun clearIdentity(id: Long)
}
