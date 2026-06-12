package com.buco7854.opentv.data.repo

import com.buco7854.opentv.data.db.AppDatabase
import com.buco7854.opentv.data.db.ChannelEntity
import com.buco7854.opentv.data.db.ChannelKind
import com.buco7854.opentv.data.db.MetadataEntity
import com.buco7854.opentv.data.db.PlaylistEntity
import com.buco7854.opentv.data.db.XtreamSeriesEntity
import com.buco7854.opentv.data.meta.castFromNames
import com.buco7854.opentv.data.meta.encodeCast
import com.buco7854.opentv.data.xtream.Xtream
import com.buco7854.opentv.data.xtream.XtreamCredentials
import com.buco7854.opentv.diag.ErrorLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** seriesKey used for episodes cached from the panel, unique per series. */
fun xtreamSeriesKey(seriesId: Long) = "xs:$seriesId"

/** Stable favorites key for an Xtream catalog series. */
fun xtreamFavoriteKey(seriesId: Long) = "x:$seriesId"

/**
 * On-demand Xtream data: series episodes and VOD details. Both are fetched
 * only when the user opens the corresponding page, and cached - one request
 * per series per day, one per movie per month, at most.
 */
class XtreamRepository(private val db: AppDatabase) {

    companion object {
        const val EPISODES_CACHE_MS = 24L * 60 * 60 * 1000
        const val VOD_INFO_CACHE_MS = 30L * 24 * 60 * 60 * 1000
    }

    private val mutex = Mutex()

    private fun credentialsOf(playlist: PlaylistEntity?): XtreamCredentials? {
        val base = playlist?.xtreamBase ?: return null
        val user = playlist.xtreamUser ?: return null
        val pass = playlist.xtreamPass ?: return null
        return XtreamCredentials(base, user, pass)
    }

    val seriesDao get() = db.xtreamSeriesDao()

    /** Fetch and cache the episode list for one series (throttled to 24h). */
    suspend fun ensureEpisodes(playlistId: Long, seriesId: Long, force: Boolean = false): Unit =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val series = db.xtreamSeriesDao().get(playlistId, seriesId) ?: return@withLock
                val seriesKey = xtreamSeriesKey(seriesId)
                val now = System.currentTimeMillis()
                val cached = db.channelDao().countEpisodes(playlistId, seriesKey) > 0
                if (!force && cached && now - series.episodesFetchedAtMs < EPISODES_CACHE_MS) return@withLock

                val creds = credentialsOf(db.playlistDao().get(playlistId)) ?: return@withLock
                val episodes = Xtream.fetchSeriesEpisodes(creds, seriesId)
                if (episodes.isEmpty() && cached) return@withLock // keep what we have

                db.channelDao().deleteEpisodes(playlistId, seriesKey)
                db.channelDao().insertAll(
                    episodes.mapIndexed { index, ep ->
                        ChannelEntity(
                            playlistId = playlistId,
                            name = ep.title,
                            url = Xtream.episodeUrl(creds, ep.episodeId, ep.containerExtension),
                            logo = series.cover,
                            groupTitle = series.categoryName,
                            tvgId = null,
                            kind = ChannelKind.SERIES,
                            seriesKey = seriesKey,
                            season = ep.season,
                            episode = ep.episodeNum,
                            position = index,
                        )
                    }
                )
                db.xtreamSeriesDao().setEpisodesFetched(playlistId, seriesId, now)
            }
        }

    /** Panel-provided movie details (get_vod_info), cached in the metadata table. */
    suspend fun vodMetadata(channel: ChannelEntity): MetadataEntity? = withContext(Dispatchers.IO) {
        val streamId = channel.xtreamStreamId ?: return@withContext null
        val cacheKey = "xtreamvod:${channel.playlistId}:$streamId"
        val now = System.currentTimeMillis()
        db.metadataDao().get(cacheKey)
            ?.takeIf { now - it.fetchedAtMs < VOD_INFO_CACHE_MS }
            ?.let { return@withContext it.takeIf { m -> m.overview != null || m.castNames != null } }

        val creds = credentialsOf(db.playlistDao().get(channel.playlistId)) ?: return@withContext null
        try {
            val info = Xtream.fetchVodInfo(creds, streamId)
            val credits = info?.let {
                listOfNotNull(
                    it.cast?.let { c -> "Cast: $c" },
                    it.director?.let { d -> "Director: $d" },
                    it.genre?.let { g -> "Genre: $g" },
                ).joinToString(" · ").takeIf { line -> line.isNotEmpty() }
            }
            val entity = MetadataEntity(
                cacheKey = cacheKey,
                title = channel.name,
                year = null,
                overview = info?.plot,
                rating = info?.rating,
                castNames = credits,
                castJson = castFromNames(info?.cast).takeIf { it.isNotEmpty() }?.let { encodeCast(it) },
                posterUrl = info?.image ?: channel.logo,
                fetchedAtMs = now,
            )
            db.metadataDao().upsert(entity)
            entity.takeIf { info != null && (info.plot != null || credits != null || info.rating != null) }
        } catch (e: Exception) {
            ErrorLog.log("Movie details", e)
            null
        }
    }

    /** Series row for the detail page header. */
    suspend fun series(playlistId: Long, seriesId: Long): XtreamSeriesEntity? =
        db.xtreamSeriesDao().get(playlistId, seriesId)

    /**
     * Catch-up (timeshift) URL for a past programme on an archived channel, or
     * null when the channel has no archive or no resolvable stream id.
     */
    suspend fun catchupUrlFor(channel: ChannelEntity, startMs: Long, endMs: Long): String? =
        withContext(Dispatchers.IO) {
            if (channel.catchupDays <= 0) return@withContext null
            val creds = credentialsOf(db.playlistDao().get(channel.playlistId)) ?: return@withContext null
            // M3U-sourced channels carry the stream id in the URL's last segment.
            val streamId = channel.xtreamStreamId
                ?: Regex("""/(\d+)\.\w{1,5}$""")
                    .find(channel.url.substringBefore('?'))?.groupValues?.get(1)?.toLongOrNull()
                ?: return@withContext null
            val durationMinutes = ((endMs - startMs) / 60_000L).toInt()
            Xtream.catchupUrl(creds, streamId, startMs, durationMinutes)
        }
}
