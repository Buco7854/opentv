package com.buco7854.opentv.core.storage

import com.buco7854.opentv.core.model.Channel
import com.buco7854.opentv.core.model.Download
import com.buco7854.opentv.core.model.Favorite
import com.buco7854.opentv.core.model.GroupCount
import com.buco7854.opentv.core.model.GroupHit
import com.buco7854.opentv.core.model.GroupOverride
import com.buco7854.opentv.core.model.Metadata
import com.buco7854.opentv.core.model.Playlist
import com.buco7854.opentv.core.model.Programme
import com.buco7854.opentv.core.model.ResumePoint
import com.buco7854.opentv.core.model.SeriesGroup
import com.buco7854.opentv.core.model.XtreamSeries
import kotlinx.coroutines.flow.Flow

/** Storage ports: repositories and UIs talk only to these; :data provides the Room/SQLite adapter. */
interface Storage {
    val playlists: PlaylistStore
    val channels: ChannelStore
    val xtreamSeries: XtreamSeriesStore
    val epg: EpgStore
    val groupOverrides: GroupOverrideStore
    val favorites: FavoriteStore
    val resume: ResumeStore
    val metadata: MetadataStore
    val downloads: DownloadStore

    /** Releases platform storage resources. Android keeps this open for its process lifetime. */
    fun close() = Unit
}

interface PlaylistStore {
    fun observeAll(): Flow<List<Playlist>>
    suspend fun getAll(): List<Playlist>
    suspend fun get(id: Long): Playlist?
    fun observe(id: Long): Flow<Playlist?>
    suspend fun insert(playlist: Playlist): Long
    suspend fun update(playlist: Playlist)
    suspend fun delete(id: Long)
}

interface ChannelStore {
    suspend fun insertAll(channels: List<Channel>)
    suspend fun deleteForPlaylist(playlistId: Long)
    suspend fun deleteForPlaylistKind(playlistId: Long, kind: Int)

    /** Replace every row of [kinds] with [channels] atomically, so observers never see the
     *  playlist mid-wipe. The Room adapter runs it in one transaction; this default does not. */
    suspend fun replaceKinds(playlistId: Long, kinds: List<Int>, channels: List<Channel>) {
        kinds.forEach { deleteForPlaylistKind(playlistId, it) }
        insertAll(channels)
    }
    suspend fun count(playlistId: Long, kind: Int): Int
    fun observeGroups(playlistId: Long, kind: Int): Flow<List<GroupCount>>
    fun observeInGroup(playlistId: Long, kind: Int, group: String): Flow<List<Channel>>
    fun observeSeriesInGroup(playlistId: Long, group: String): Flow<List<SeriesGroup>>
    fun observeAllSeries(playlistId: Long): Flow<List<SeriesGroup>>
    fun observeEpisodes(playlistId: Long, seriesKey: String): Flow<List<Channel>>
    fun observeCount(playlistId: Long, kind: Int): Flow<Int>
    fun observeByUrls(playlistId: Long, kind: Int, urls: List<String>): Flow<List<Channel>>
    /** [query] must have %, _ and \ pre-escaped (LIKE semantics). */
    suspend fun search(playlistId: Long, query: String): List<Channel>
    suspend fun searchGroups(playlistId: Long, query: String): List<GroupHit>
    suspend fun get(id: Long): Channel?
    suspend fun getByUrl(playlistId: Long, url: String): Channel?
    suspend fun distinctLiveTvgIds(playlistId: Long): List<String>
    suspend fun countEpisodes(playlistId: Long, seriesKey: String): Int
    /** Immediate best-effort retag of a corrected category (refined at next refresh). */
    suspend fun retagGroup(playlistId: Long, groupTitle: String, kind: Int)
    suspend fun retagGroupAsSeries(playlistId: Long, groupTitle: String)
    suspend fun deleteEpisodes(playlistId: Long, seriesKey: String)
}

interface XtreamSeriesStore {
    suspend fun insertAll(series: List<XtreamSeries>)
    suspend fun deleteForPlaylist(playlistId: Long)
    suspend fun count(playlistId: Long): Int

    /** Replace the whole catalog atomically (see [ChannelStore.replaceKinds]). */
    suspend fun replaceAll(playlistId: Long, series: List<XtreamSeries>) {
        deleteForPlaylist(playlistId)
        insertAll(series)
    }
    fun observeCategories(playlistId: Long): Flow<List<GroupCount>>
    fun observeInCategory(playlistId: Long, category: String): Flow<List<XtreamSeries>>
    fun observeAll(playlistId: Long): Flow<List<XtreamSeries>>
    fun observeCount(playlistId: Long): Flow<Int>
    suspend fun get(playlistId: Long, seriesId: Long): XtreamSeries?
    suspend fun search(playlistId: Long, query: String): List<XtreamSeries>
    suspend fun searchCategories(playlistId: Long, query: String): List<GroupHit>
    suspend fun setEpisodesFetched(playlistId: Long, seriesId: Long, fetchedAtMs: Long)
}

interface EpgStore {
    /** Upserts on (playlistId, tvgId, startMs). */
    suspend fun insertAll(programmes: List<Programme>)
    suspend fun deleteForPlaylist(playlistId: Long)
    /** Deletes programmes starting at/after [fromMs]. */
    suspend fun deleteFrom(playlistId: Long, fromMs: Long)
    /** Deletes programmes that ended at/before [beforeMs]. */
    suspend fun prune(playlistId: Long, beforeMs: Long)
    /** One row per channel: whatever is airing right now across the playlist. */
    suspend fun nowAiring(playlistId: Long, now: Long): List<Programme>
    /** Guide entries ending after [fromMs] - set fromMs into the past for catch-up. */
    suspend fun guideSince(playlistId: Long, tvgId: String, fromMs: Long, limit: Int): List<Programme>
    /** tvg ids that have programme data. */
    fun observeGuideIds(playlistId: Long): Flow<List<String>>
}

interface GroupOverrideStore {
    suspend fun forPlaylist(playlistId: Long): List<GroupOverride>
    suspend fun upsert(override: GroupOverride)
    suspend fun remove(playlistId: Long, groupTitle: String)
    suspend fun deleteForPlaylist(playlistId: Long)
}

interface FavoriteStore {
    fun observeAll(playlistId: Long): Flow<List<Favorite>>
    suspend fun getAll(playlistId: Long): List<Favorite>
    suspend fun get(playlistId: Long, key: String): Favorite?
    suspend fun add(favorite: Favorite)
    suspend fun remove(playlistId: Long, key: String)
    suspend fun deleteForPlaylist(playlistId: Long)
}

interface ResumeStore {
    suspend fun get(url: String): ResumePoint?
    fun observeAll(): Flow<List<ResumePoint>>
    suspend fun getAll(): List<ResumePoint>
    suspend fun upsert(point: ResumePoint)
    suspend fun delete(url: String)
    /** Deletes resume points for every channel of the given playlist. */
    suspend fun deleteForPlaylist(playlistId: Long)
    suspend fun prune(before: Long)
}

interface MetadataStore {
    suspend fun get(cacheKey: String): Metadata?
    suspend fun upsert(metadata: Metadata)
}

interface DownloadStore {
    fun observeAll(): Flow<List<Download>>
    suspend fun get(id: Long): Download?
    suspend fun getByStatus(status: Int): List<Download>
    suspend fun findByUrlWithStatus(url: String, statuses: List<Int>): Download?
    suspend fun insert(download: Download): Long
    suspend fun update(download: Download)
    /**
     * Updates progress only while the row is in one of [expectedStatuses].
     * Returns false when pause/delete/retry won the race.
     */
    suspend fun updateProgressIfStatus(
        id: Long,
        downloaded: Long,
        total: Long,
        expectedStatuses: List<Int>,
        status: Int,
    ): Boolean
    /** Changes state/error only if another actor has not already paused or deleted the row. */
    suspend fun updateStatusIfStatus(
        id: Long,
        expectedStatuses: List<Int>,
        status: Int,
        error: String? = null,
    ): Boolean
    suspend fun delete(id: Long)
}
