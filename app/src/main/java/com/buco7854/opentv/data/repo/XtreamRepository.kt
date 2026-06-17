package com.buco7854.opentv.data.repo

import com.buco7854.opentv.data.db.AppDatabase
import com.buco7854.opentv.data.db.ChannelEntity
import com.buco7854.opentv.data.db.ChannelKind
import com.buco7854.opentv.data.db.MetadataEntity
import com.buco7854.opentv.data.db.PlaylistEntity
import com.buco7854.opentv.data.db.XtreamSeriesEntity
import com.buco7854.opentv.data.catchup.Catchup
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

/** One guide row, with whether it can be replayed via catch-up. */
data class GuideEntry(
    val title: String,
    val description: String?,
    val startMs: Long,
    val endMs: Long,
    val replayable: Boolean,
)

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
                            logo = ep.image ?: series.cover,
                            groupTitle = series.categoryName,
                            tvgId = null,
                            kind = ChannelKind.SERIES,
                            seriesKey = seriesKey,
                            season = ep.season,
                            episode = ep.episodeNum,
                            position = index,
                            description = ep.plot,
                            durationSecs = ep.durationSecs,
                            airDate = ep.airDate,
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

    private class CachedEpg(val entries: List<com.buco7854.opentv.data.xtream.XtreamEpgEntry>, val atMs: Long)
    private val epgCache = HashMap<String, CachedEpg>()

    /**
     * Full guide for one channel, with per-row catch-up availability.
     *
     * For Xtream channels we prefer the panel's per-channel table
     * (get_simple_data_table): it includes past programmes and an explicit
     * archive flag, so every replayable programme shows the button - the bulk
     * xmltv.php often omits the past entirely. Falls back to the stored XMLTV
     * (M3U, or when the panel call fails), where replay is offered on past
     * programmes whenever the channel declares catch-up.
     */
    suspend fun guideFor(channel: ChannelEntity): List<GuideEntry> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val streamId = channel.xtreamStreamId
        if (streamId != null) {
            val creds = credentialsOf(db.playlistDao().get(channel.playlistId))
            if (creds != null) {
                val key = "${channel.playlistId}:$streamId"
                val cached = epgCache[key]?.takeIf { now - it.atMs < 10 * 60_000L }?.entries
                    ?: runCatching { Xtream.fetchChannelEpg(creds, streamId) }
                        .getOrElse { ErrorLog.log("Channel EPG", it); emptyList() }
                        .also { if (it.isNotEmpty()) epgCache[key] = CachedEpg(it, now) }
                if (cached.isNotEmpty()) {
                    // Panels are inconsistent about the per-programme has_archive
                    // flag (often 0 even when the channel is fully archived), so a
                    // past programme is replayable if the flag is set OR it falls
                    // within the channel's declared archive window.
                    val windowStart = if (channel.catchupDays > 0) {
                        now - channel.catchupDays * 86_400_000L
                    } else Long.MAX_VALUE
                    return@withContext cached.map {
                        val replayable = it.endMs <= now &&
                            (it.hasArchive || it.startMs >= windowStart)
                        GuideEntry(it.title, it.description, it.startMs, it.endMs, replayable)
                    }
                }
            }
        }
        // Fallback: stored XMLTV.
        val tvgId = channel.tvgId ?: return@withContext emptyList()
        val days = when {
            channel.catchupDays > 0 -> channel.catchupDays
            channel.catchupSource != null -> 7
            else -> 0
        }
        val since = if (days > 0) now - days * 86_400_000L else now
        val canReplay = channel.hasCatchup()
        db.epgDao().guideSince(channel.playlistId, tvgId, since, 400).map {
            GuideEntry(it.title, it.description, it.startMs, it.endMs,
                replayable = canReplay && it.endMs <= now)
        }
    }

    /**
     * Catch-up (timeshift) URL for a past programme, or null when this channel
     * has no usable catch-up. Handles two sources:
     *  - M3U `catchup-source` templates (placeholders resolved per programme),
     *  - Xtream panels (the /timeshift/ endpoint from the live stream id).
     */
    suspend fun catchupUrlFor(channel: ChannelEntity, startMs: Long, endMs: Long): String? =
        withContext(Dispatchers.IO) {
            // 1. M3U catchup-source template wins when present.
            channel.catchupSource?.let { template ->
                return@withContext Catchup.fromTemplate(template, startMs, endMs)
            }
            // 2. Xtream timeshift endpoint from credentials + stream id.
            if (channel.catchupDays <= 0) return@withContext null
            val creds = credentialsOf(db.playlistDao().get(channel.playlistId)) ?: return@withContext null
            val streamId = channel.xtreamStreamId
                ?: Regex("""/(\d+)\.\w{1,5}$""")
                    .find(channel.url.substringBefore('?'))?.groupValues?.get(1)?.toLongOrNull()
                ?: return@withContext null
            val durationMinutes = ((endMs - startMs) / 60_000L).toInt()
            Catchup.xtreamTimeshift(creds.base, creds.user, creds.pass, streamId, startMs, durationMinutes)
        }
}

/** True when this channel can offer catch-up replay at all. */
fun ChannelEntity.hasCatchup(): Boolean = catchupSource != null || catchupDays > 0
