package com.buco7854.opentv.data

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
import com.buco7854.opentv.core.storage.ChannelStore
import com.buco7854.opentv.core.storage.DownloadStore
import com.buco7854.opentv.core.storage.EpgStore
import com.buco7854.opentv.core.storage.FavoriteStore
import com.buco7854.opentv.core.storage.GroupOverrideStore
import com.buco7854.opentv.core.storage.MetadataStore
import com.buco7854.opentv.core.storage.PlaylistStore
import com.buco7854.opentv.core.storage.ResumeStore
import com.buco7854.opentv.core.storage.Storage
import com.buco7854.opentv.core.storage.XtreamSeriesStore
import com.buco7854.opentv.data.db.OpenTvDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Room implementation of the core storage ports. */
class RoomStorage(private val db: OpenTvDatabase) : Storage {

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

        override suspend fun deleteForPlaylist(playlistId: Long) = db.channelDao().deleteForPlaylist(playlistId)
        override suspend fun deleteForPlaylistKind(playlistId: Long, kind: Int) =
            db.channelDao().deleteForPlaylistKind(playlistId, kind)

        override suspend fun replaceKinds(playlistId: Long, kinds: List<Int>, channels: List<Channel>) =
            db.channelDao().replaceKinds(playlistId, kinds, channels.map { it.toRow() })
        override suspend fun count(playlistId: Long, kind: Int): Int = db.channelDao().count(playlistId, kind)

        override fun observeGroups(playlistId: Long, kind: Int): Flow<List<GroupCount>> =
            db.channelDao().observeGroups(playlistId, kind)

        override fun observeInGroup(playlistId: Long, kind: Int, group: String): Flow<List<Channel>> =
            db.channelDao().observeInGroup(playlistId, kind, group).map { rows -> rows.map { it.toModel() } }

        override fun observeSeriesInGroup(playlistId: Long, group: String): Flow<List<SeriesGroup>> =
            db.channelDao().observeSeriesInGroup(playlistId, group)

        override fun observeAllSeries(playlistId: Long): Flow<List<SeriesGroup>> =
            db.channelDao().observeAllSeries(playlistId)

        override fun observeEpisodes(playlistId: Long, seriesKey: String): Flow<List<Channel>> =
            db.channelDao().observeEpisodes(playlistId, seriesKey).map { rows -> rows.map { it.toModel() } }

        override fun observeCount(playlistId: Long, kind: Int): Flow<Int> =
            db.channelDao().observeCount(playlistId, kind)

        override fun observeByUrls(playlistId: Long, kind: Int, urls: List<String>): Flow<List<Channel>> =
            db.channelDao().observeByUrls(playlistId, kind, urls).map { rows -> rows.map { it.toModel() } }

        override suspend fun search(playlistId: Long, query: String): List<Channel> =
            db.channelDao().search(playlistId, query).map { it.toModel() }

        override suspend fun searchGroups(playlistId: Long, query: String): List<GroupHit> =
            db.channelDao().searchGroups(playlistId, query)

        override suspend fun get(id: Long): Channel? = db.channelDao().get(id)?.toModel()

        override suspend fun getByUrl(playlistId: Long, url: String): Channel? =
            db.channelDao().getByUrl(playlistId, url)?.toModel()

        override suspend fun distinctLiveTvgIds(playlistId: Long): List<String> =
            db.channelDao().distinctLiveTvgIds(playlistId)

        override suspend fun countEpisodes(playlistId: Long, seriesKey: String): Int =
            db.channelDao().countEpisodes(playlistId, seriesKey)

        override suspend fun retagGroup(playlistId: Long, groupTitle: String, kind: Int) =
            db.channelDao().retagGroup(playlistId, groupTitle, kind)

        override suspend fun retagGroupAsSeries(playlistId: Long, groupTitle: String) =
            db.channelDao().retagGroupAsSeries(playlistId, groupTitle)

        override suspend fun deleteEpisodes(playlistId: Long, seriesKey: String) =
            db.channelDao().deleteEpisodes(playlistId, seriesKey)
    }

    override val xtreamSeries = object : XtreamSeriesStore {
        override suspend fun insertAll(series: List<XtreamSeries>) =
            db.xtreamSeriesDao().insertAll(series.map { it.toRow() })

        override suspend fun deleteForPlaylist(playlistId: Long) =
            db.xtreamSeriesDao().deleteForPlaylist(playlistId)

        override suspend fun replaceAll(playlistId: Long, series: List<XtreamSeries>) =
            db.xtreamSeriesDao().replaceAll(playlistId, series.map { it.toRow() })

        override suspend fun count(playlistId: Long): Int = db.xtreamSeriesDao().count(playlistId)

        override fun observeCategories(playlistId: Long): Flow<List<GroupCount>> =
            db.xtreamSeriesDao().observeCategories(playlistId)

        override fun observeInCategory(playlistId: Long, category: String): Flow<List<XtreamSeries>> =
            db.xtreamSeriesDao().observeInCategory(playlistId, category).map { rows -> rows.map { it.toModel() } }

        override fun observeAll(playlistId: Long): Flow<List<XtreamSeries>> =
            db.xtreamSeriesDao().observeAll(playlistId).map { rows -> rows.map { it.toModel() } }

        override fun observeCount(playlistId: Long): Flow<Int> = db.xtreamSeriesDao().observeCount(playlistId)

        override suspend fun get(playlistId: Long, seriesId: Long): XtreamSeries? =
            db.xtreamSeriesDao().get(playlistId, seriesId)?.toModel()

        override suspend fun search(playlistId: Long, query: String): List<XtreamSeries> =
            db.xtreamSeriesDao().search(playlistId, query).map { it.toModel() }

        override suspend fun searchCategories(playlistId: Long, query: String): List<GroupHit> =
            db.xtreamSeriesDao().searchCategories(playlistId, query)

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

        override suspend fun findByUrlWithStatus(url: String, statuses: List<Int>): Download? =
            db.downloadDao().findByUrlWithStatus(url, statuses)?.toModel()

        override suspend fun insert(download: Download): Long = db.downloadDao().insert(download.toRow())
        override suspend fun update(download: Download) = db.downloadDao().update(download.toRow())

        override suspend fun updateProgress(id: Long, downloaded: Long, total: Long, status: Int) =
            db.downloadDao().updateProgress(id, downloaded, total, status)

        override suspend fun delete(id: Long) = db.downloadDao().delete(id)
    }
}
