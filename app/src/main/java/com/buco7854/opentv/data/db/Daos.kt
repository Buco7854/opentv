package com.buco7854.opentv.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

data class GroupCount(val groupTitle: String, val count: Int)
data class SeriesGroup(val seriesKey: String, val count: Int, val logo: String?, val groupTitle: String)

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY id")
    fun observeAll(): Flow<List<PlaylistEntity>>

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

    @Query("SELECT COUNT(*) FROM channels WHERE playlistId = :playlistId AND kind = :kind")
    fun observeCount(playlistId: Long, kind: Int): Flow<Int>

    @Query("SELECT * FROM channels WHERE id = :id")
    suspend fun get(id: Long): ChannelEntity?

    @Query("SELECT DISTINCT tvgId FROM channels WHERE playlistId = :playlistId AND kind = 0 AND tvgId IS NOT NULL")
    suspend fun distinctLiveTvgIds(playlistId: Long): List<String>
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

    @Query(
        "SELECT * FROM programmes WHERE playlistId = :playlistId AND tvgId = :tvgId " +
            "AND endMs > :now ORDER BY startMs LIMIT :limit"
    )
    suspend fun upcoming(playlistId: Long, tvgId: String, now: Long, limit: Int): List<ProgrammeEntity>

    @Query("SELECT COUNT(*) FROM programmes WHERE playlistId = :playlistId")
    suspend fun count(playlistId: Long): Int
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdMs DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun get(id: Long): DownloadEntity?

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
