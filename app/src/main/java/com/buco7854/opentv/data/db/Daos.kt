package com.buco7854.opentv.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

data class GroupCount(val groupTitle: String, val count: Int)
data class SeriesGroup(val seriesKey: String, val count: Int, val logo: String?, val groupTitle: String)
data class GroupHit(val groupTitle: String, val kind: Int, val count: Int)

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY id")
    fun observeAll(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists")
    suspend fun getAll(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun get(id: Long): PlaylistEntity?

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun observe(id: Long): Flow<PlaylistEntity?>

    @Insert
    suspend fun insert(p: PlaylistEntity): Long

    @Update
    suspend fun update(p: PlaylistEntity)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ChannelDao {
    /** Blocking on purpose: called in batches from streaming parsers already on Dispatchers.IO. */
    @Insert
    fun insertAll(channels: List<ChannelEntity>)

    @Query("DELETE FROM channels WHERE playlistId = :playlistId")
    suspend fun deleteForPlaylist(playlistId: Long)

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
    fun observeInGroup(playlistId: Long, kind: Int, group: String): Flow<List<ChannelEntity>>

    @Query(
        "SELECT seriesKey, COUNT(*) as count, MIN(logo) as logo, MIN(groupTitle) as groupTitle " +
            "FROM channels WHERE playlistId = :playlistId AND kind = 2 AND groupTitle = :group " +
            "GROUP BY seriesKey ORDER BY seriesKey"
    )
    fun observeSeriesInGroup(playlistId: Long, group: String): Flow<List<SeriesGroup>>

    @Query(
        "SELECT * FROM channels WHERE playlistId = :playlistId AND kind = 2 AND seriesKey = :seriesKey " +
            "ORDER BY season, episode, position"
    )
    fun observeEpisodes(playlistId: Long, seriesKey: String): Flow<List<ChannelEntity>>

    /** Caller must escape %, _ and \ in [query] (see SearchViewModel). */
    @Query(
        "SELECT * FROM channels WHERE playlistId = :playlistId " +
            "AND name LIKE '%' || :query || '%' ESCAPE '\\' " +
            "ORDER BY kind, name LIMIT 400"
    )
    suspend fun search(playlistId: Long, query: String): List<ChannelEntity>

    @Query(
        "SELECT groupTitle, kind, COUNT(*) as count FROM channels WHERE playlistId = :playlistId " +
            "AND groupTitle LIKE '%' || :query || '%' ESCAPE '\\' " +
            "GROUP BY groupTitle, kind ORDER BY count DESC LIMIT 30"
    )
    suspend fun searchGroups(playlistId: Long, query: String): List<GroupHit>

    @Query("SELECT COUNT(*) FROM channels WHERE playlistId = :playlistId AND kind = :kind")
    fun observeCount(playlistId: Long, kind: Int): Flow<Int>

    @Query("SELECT * FROM channels WHERE id = :id")
    suspend fun get(id: Long): ChannelEntity?

    @Query("SELECT * FROM channels WHERE playlistId = :playlistId AND url = :url LIMIT 1")
    suspend fun getByUrl(playlistId: Long, url: String): ChannelEntity?

    @Query("SELECT DISTINCT tvgId FROM channels WHERE playlistId = :playlistId AND kind = 0 AND tvgId IS NOT NULL")
    suspend fun distinctLiveTvgIds(playlistId: Long): List<String>

    @Query("SELECT COUNT(*) FROM channels WHERE playlistId = :playlistId AND seriesKey = :seriesKey")
    suspend fun countEpisodes(playlistId: Long, seriesKey: String): Int

    /** Favorites view: channels of one kind matched by their stable urls. */
    @Query(
        "SELECT * FROM channels WHERE playlistId = :playlistId AND kind = :kind " +
            "AND url IN (:urls) ORDER BY name"
    )
    fun observeByUrls(playlistId: Long, kind: Int, urls: List<String>): Flow<List<ChannelEntity>>

    @Query(
        "SELECT seriesKey, COUNT(*) as count, MIN(logo) as logo, MIN(groupTitle) as groupTitle " +
            "FROM channels WHERE playlistId = :playlistId AND kind = 2 " +
            "GROUP BY seriesKey ORDER BY seriesKey"
    )
    fun observeAllSeries(playlistId: Long): Flow<List<SeriesGroup>>

    /** Immediate best-effort retag of a corrected category (refined at next refresh). */
    @Query(
        "UPDATE channels SET kind = :kind, seriesKey = NULL, season = NULL, episode = NULL " +
            "WHERE playlistId = :playlistId AND groupTitle = :groupTitle"
    )
    suspend fun retagGroup(playlistId: Long, groupTitle: String, kind: Int)

    @Query(
        "UPDATE channels SET kind = 2, seriesKey = name WHERE playlistId = :playlistId " +
            "AND groupTitle = :groupTitle"
    )
    suspend fun retagGroupAsSeries(playlistId: Long, groupTitle: String)

    @Query("DELETE FROM channels WHERE playlistId = :playlistId AND seriesKey = :seriesKey")
    suspend fun deleteEpisodes(playlistId: Long, seriesKey: String)
}

@Dao
interface XtreamSeriesDao {
    /** Blocking on purpose: called in batches during refresh on Dispatchers.IO. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(series: List<XtreamSeriesEntity>)

    @Query("DELETE FROM xtream_series WHERE playlistId = :playlistId")
    suspend fun deleteForPlaylist(playlistId: Long)

    @Query(
        "SELECT categoryName as groupTitle, COUNT(*) as count FROM xtream_series " +
            "WHERE playlistId = :playlistId GROUP BY categoryName ORDER BY categoryName"
    )
    fun observeCategories(playlistId: Long): Flow<List<GroupCount>>

    @Query(
        "SELECT * FROM xtream_series WHERE playlistId = :playlistId " +
            "AND categoryName = :category ORDER BY name"
    )
    fun observeInCategory(playlistId: Long, category: String): Flow<List<XtreamSeriesEntity>>

    @Query("SELECT * FROM xtream_series WHERE playlistId = :playlistId AND seriesId = :seriesId")
    suspend fun get(playlistId: Long, seriesId: Long): XtreamSeriesEntity?

    @Query("SELECT COUNT(*) FROM xtream_series WHERE playlistId = :playlistId")
    fun observeCount(playlistId: Long): Flow<Int>

    @Query("SELECT * FROM xtream_series WHERE playlistId = :playlistId ORDER BY name")
    fun observeAll(playlistId: Long): Flow<List<XtreamSeriesEntity>>

    @Query(
        "SELECT * FROM xtream_series WHERE playlistId = :playlistId " +
            "AND name LIKE '%' || :query || '%' ESCAPE '\\' ORDER BY name LIMIT 100"
    )
    suspend fun search(playlistId: Long, query: String): List<XtreamSeriesEntity>

    @Query(
        "UPDATE xtream_series SET episodesFetchedAtMs = :fetchedAtMs " +
            "WHERE playlistId = :playlistId AND seriesId = :seriesId"
    )
    suspend fun setEpisodesFetched(playlistId: Long, seriesId: Long, fetchedAtMs: Long)

    @Query(
        "SELECT categoryName as groupTitle, 2 as kind, COUNT(*) as count FROM xtream_series " +
            "WHERE playlistId = :playlistId AND categoryName LIKE '%' || :query || '%' ESCAPE '\\' " +
            "GROUP BY categoryName ORDER BY count DESC LIMIT 20"
    )
    suspend fun searchCategories(playlistId: Long, query: String): List<GroupHit>
}

@Dao
interface EpgDao {
    /** Blocking on purpose: called in batches from streaming parsers already on Dispatchers.IO. */
    @Insert
    fun insertAll(programmes: List<ProgrammeEntity>)

    @Query("DELETE FROM programmes WHERE playlistId = :playlistId")
    suspend fun deleteForPlaylist(playlistId: Long)

    /** One row per channel: whatever is airing right now across the playlist. */
    @Query(
        "SELECT * FROM programmes WHERE playlistId = :playlistId " +
            "AND startMs <= :now AND endMs > :now"
    )
    suspend fun nowAiring(playlistId: Long, now: Long): List<ProgrammeEntity>

    /** Guide entries ending after [fromMs] - set fromMs into the past for catch-up. */
    @Query(
        "SELECT * FROM programmes WHERE playlistId = :playlistId AND tvgId = :tvgId " +
            "AND endMs > :fromMs ORDER BY startMs LIMIT :limit"
    )
    suspend fun guideSince(playlistId: Long, tvgId: String, fromMs: Long, limit: Int): List<ProgrammeEntity>

    @Query("SELECT COUNT(*) FROM programmes WHERE playlistId = :playlistId")
    suspend fun count(playlistId: Long): Int
}

@Dao
interface GroupOverrideDao {
    @Query("SELECT * FROM group_overrides WHERE playlistId = :playlistId")
    suspend fun forPlaylist(playlistId: Long): List<GroupOverrideEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(override: GroupOverrideEntity)

    @Query("DELETE FROM group_overrides WHERE playlistId = :playlistId AND groupTitle = :groupTitle")
    suspend fun remove(playlistId: Long, groupTitle: String)

    @Query("DELETE FROM group_overrides WHERE playlistId = :playlistId")
    suspend fun deleteForPlaylist(playlistId: Long)
}

@Dao
interface ResumeDao {
    @Query("SELECT * FROM resume_points WHERE url = :url")
    suspend fun get(url: String): ResumePointEntity?

    /** All saved positions, observed live so progress bars update on return. */
    @Query("SELECT * FROM resume_points")
    fun observeAll(): Flow<List<ResumePointEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(point: ResumePointEntity)

    @Query("DELETE FROM resume_points WHERE url = :url")
    suspend fun delete(url: String)

    @Query("DELETE FROM resume_points WHERE updatedMs < :before")
    suspend fun prune(before: Long)
}

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites WHERE playlistId = :playlistId")
    fun observeAll(playlistId: Long): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites WHERE playlistId = :playlistId AND `key` = :key")
    suspend fun get(playlistId: Long, key: String): FavoriteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE playlistId = :playlistId AND `key` = :key")
    suspend fun remove(playlistId: Long, key: String)

    @Query("DELETE FROM favorites WHERE playlistId = :playlistId")
    suspend fun deleteForPlaylist(playlistId: Long)
}

@Dao
interface MetadataDao {
    @Query("SELECT * FROM metadata WHERE cacheKey = :cacheKey")
    suspend fun get(cacheKey: String): MetadataEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(metadata: MetadataEntity)
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdMs DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun get(id: Long): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE status = :status")
    suspend fun getByStatus(status: Int): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE url = :url AND status IN (:statuses) LIMIT 1")
    suspend fun findByUrlWithStatus(url: String, statuses: List<Int>): DownloadEntity?

    @Insert
    suspend fun insert(d: DownloadEntity): Long

    @Update
    suspend fun update(d: DownloadEntity)

    @Query("UPDATE downloads SET downloadedBytes = :downloaded, totalBytes = :total, status = :status WHERE id = :id")
    suspend fun updateProgress(id: Long, downloaded: Long, total: Long, status: Int)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: Long)
}
