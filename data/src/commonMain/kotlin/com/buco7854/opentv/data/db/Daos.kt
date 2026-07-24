package com.buco7854.opentv.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.buco7854.opentv.core.model.GroupCount
import com.buco7854.opentv.core.model.GroupHit
import com.buco7854.opentv.core.model.SeriesGroup
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
        "SELECT seriesKey, COUNT(*) as count, MIN(logo) as logo, MIN(groupTitle) as groupTitle " +
            "FROM channels WHERE playlistId = :playlistId AND kind = 2 AND groupTitle = :group " +
            "GROUP BY seriesKey ORDER BY seriesKey"
    )
    fun observeSeriesInGroup(playlistId: Long, group: String): Flow<List<SeriesGroup>>

    @Query(
        "SELECT * FROM channels WHERE playlistId = :playlistId AND kind = 2 AND seriesKey = :seriesKey " +
            "ORDER BY season, episode, position"
    )
    fun observeEpisodes(playlistId: Long, seriesKey: String): Flow<List<ChannelRow>>

    @Query(
        "SELECT * FROM channels WHERE playlistId = :playlistId " +
            "AND name LIKE '%' || :query || '%' ESCAPE '\\' " +
            "ORDER BY kind, name LIMIT 400"
    )
    suspend fun search(playlistId: Long, query: String): List<ChannelRow>

    @Query(
        "SELECT groupTitle, kind, COUNT(*) as count FROM channels WHERE playlistId = :playlistId " +
            "AND groupTitle LIKE '%' || :query || '%' ESCAPE '\\' " +
            "GROUP BY groupTitle, kind ORDER BY count DESC LIMIT 30"
    )
    suspend fun searchGroups(playlistId: Long, query: String): List<GroupHit>

    @Query("SELECT COUNT(*) FROM channels WHERE playlistId = :playlistId AND kind = :kind")
    fun observeCount(playlistId: Long, kind: Int): Flow<Int>

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

    @Query("SELECT * FROM xtream_series WHERE playlistId = :playlistId AND seriesId = :seriesId")
    suspend fun get(playlistId: Long, seriesId: Long): XtreamSeriesRow?

    @Query("SELECT COUNT(*) FROM xtream_series WHERE playlistId = :playlistId")
    fun observeCount(playlistId: Long): Flow<Int>

    @Query("SELECT * FROM xtream_series WHERE playlistId = :playlistId ORDER BY name")
    fun observeAll(playlistId: Long): Flow<List<XtreamSeriesRow>>

    @Query(
        "SELECT * FROM xtream_series WHERE playlistId = :playlistId " +
            "AND name LIKE '%' || :query || '%' ESCAPE '\\' ORDER BY name LIMIT 100"
    )
    suspend fun search(playlistId: Long, query: String): List<XtreamSeriesRow>

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

    @Query("SELECT * FROM downloads WHERE url = :url AND status IN (:statuses) LIMIT 1")
    suspend fun findByUrlWithStatus(url: String, statuses: List<Int>): DownloadRow?

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

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: Long)
}
