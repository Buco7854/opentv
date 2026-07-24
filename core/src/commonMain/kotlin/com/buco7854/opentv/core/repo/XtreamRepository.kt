package com.buco7854.opentv.core.repo

import com.buco7854.opentv.core.catchup.Catchup
import com.buco7854.opentv.core.log.CoreLog
import com.buco7854.opentv.core.log.rethrowCancellation
import com.buco7854.opentv.core.meta.castFromNames
import com.buco7854.opentv.core.meta.encodeCast
import com.buco7854.opentv.core.model.Channel
import com.buco7854.opentv.core.model.ChannelKind
import com.buco7854.opentv.core.model.Metadata
import com.buco7854.opentv.core.model.Playlist
import com.buco7854.opentv.core.model.XtreamSeries
import com.buco7854.opentv.core.storage.Storage
import com.buco7854.opentv.core.util.nowMs
import com.buco7854.opentv.core.xtream.Xtream
import com.buco7854.opentv.core.xtream.XtreamApi
import com.buco7854.opentv.core.xtream.XtreamEpgEntry
import kotlinx.datetime.TimeZone
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
 * On-demand Xtream data (series episodes, VOD details), fetched when the page
 * opens and cached: one request per series/day, one per movie/month.
 */
class XtreamRepository(
    private val storage: Storage,
    private val xtreamApi: XtreamApi,
    private val epg: EpgRepository,
    private val account: AccountRepository,
    private val log: CoreLog,
) {

    companion object {
        const val EPISODES_CACHE_MS = 24L * 60 * 60 * 1000
        const val VOD_INFO_CACHE_MS = 30L * 24 * 60 * 60 * 1000
    }

    private val mutex = Mutex()

    /** Fetch and cache the episode list for one series (throttled to 24h). */
    suspend fun ensureEpisodes(playlistId: Long, seriesId: Long, force: Boolean = false) {
        mutex.withLock {
            val series = storage.xtreamSeries.get(playlistId, seriesId) ?: return
            val seriesKey = xtreamSeriesKey(seriesId)
            val now = nowMs()
            val cached = storage.channels.countEpisodes(playlistId, seriesKey) > 0
            if (!force && cached && now - series.episodesFetchedAtMs < EPISODES_CACHE_MS) return

            val creds = storage.playlists.get(playlistId)?.credentials() ?: return
            val episodes = xtreamApi.fetchSeriesEpisodes(creds, seriesId)
            if (episodes.isEmpty() && cached) return // keep existing

            storage.channels.deleteEpisodes(playlistId, seriesKey)
            storage.channels.insertAll(
                episodes.mapIndexed { index, ep ->
                    Channel(
                        playlistId = playlistId,
                        name = ep.title,
                        url = com.buco7854.opentv.core.xtream.Xtream.episodeUrl(creds, ep.episodeId, ep.containerExtension),
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
            storage.xtreamSeries.setEpisodesFetched(playlistId, seriesId, now)
        }
    }

    /** Panel-provided movie details (get_vod_info), cached in the metadata store. */
    suspend fun vodMetadata(channel: Channel): Metadata? {
        val streamId = channel.xtreamStreamId ?: return null
        val cacheKey = "xtreamvod:${channel.playlistId}:$streamId"
        val now = nowMs()
        storage.metadata.get(cacheKey)
            ?.takeIf { now - it.fetchedAtMs < VOD_INFO_CACHE_MS }
            ?.let { return it.takeIf { m -> m.overview != null || m.castNames != null } }

        val creds = storage.playlists.get(channel.playlistId)?.credentials() ?: return null
        return try {
            val info = xtreamApi.fetchVodInfo(creds, streamId)
            val credits = info?.let {
                listOfNotNull(
                    it.cast?.let { c -> "Cast: $c" },
                    it.director?.let { d -> "Director: $d" },
                    it.genre?.let { g -> "Genre: $g" },
                ).joinToString(" · ").takeIf { line -> line.isNotEmpty() }
            }
            val entity = Metadata(
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
            storage.metadata.upsert(entity)
            entity.takeIf { info != null && (info.plot != null || credits != null || info.rating != null) }
        } catch (e: Exception) {
            e.rethrowCancellation()
            log.log("Movie details", e)
            null
        }
    }

    /** Series row for the detail page header. */
    suspend fun series(playlistId: Long, seriesId: Long): XtreamSeries? =
        storage.xtreamSeries.get(playlistId, seriesId)

    private class CachedEpg(val entries: List<XtreamEpgEntry>, val atMs: Long)
    private val epgCache = HashMap<String, CachedEpg>()

    /**
     * Full guide for one channel with per-row catch-up availability. Prefers the
     * panel's per-channel table (get_simple_data_table) since it carries past
     * programmes and an archive flag; falls back to stored XMLTV.
     */
    suspend fun guideFor(channel: Channel): List<GuideEntry> {
        val now = nowMs()
        val streamId = channel.xtreamStreamId
        if (streamId != null) {
            val creds = storage.playlists.get(channel.playlistId)?.credentials()
            if (creds != null) {
                val key = "${channel.playlistId}:$streamId"
                val cached = epgCache[key]?.takeIf { now - it.atMs < 10 * 60_000L }?.entries
                    ?: runCatching { xtreamApi.fetchChannelEpg(creds, streamId) }
                        .getOrElse { it.rethrowCancellation(); log.log("Channel EPG", it); emptyList() }
                        .also { if (it.isNotEmpty()) epgCache[key] = CachedEpg(it, now) }
                if (cached.isNotEmpty()) {
                    // has_archive is unreliable (often 0 on archived channels), so also
                    // treat a past programme as replayable inside the declared archive window.
                    val windowStart = if (channel.catchupDays > 0) {
                        now - channel.catchupDays * 86_400_000L
                    } else Long.MAX_VALUE
                    return cached.map {
                        val replayable = it.endMs <= now &&
                            (it.hasArchive || it.startMs >= windowStart)
                        GuideEntry(it.title, it.description, it.startMs, it.endMs, replayable)
                    }
                }
            }
        }
        // Fallback: stored XMLTV.
        val tvgId = channel.tvgId ?: return emptyList()
        val days = when {
            channel.catchupDays > 0 -> channel.catchupDays
            channel.catchupSource != null -> 7
            else -> 0
        }
        val since = if (days > 0) now - days * 86_400_000L else now
        val canReplay = channel.catchupSource != null || channel.catchupDays > 0
        return epg.guide(channel.playlistId, tvgId, since, 400).map {
            GuideEntry(it.title, it.description, it.startMs, it.endMs,
                replayable = canReplay && it.endMs <= now)
        }
    }

    /**
     * Catch-up (timeshift) URL for a past programme, or null. Sources: M3U
     * catchup-source templates, or the Xtream /timeshift/ endpoint.
     */
    suspend fun catchupUrlFor(channel: Channel, startMs: Long, endMs: Long): String? {
        // M3U catchup-source template wins when present.
        channel.catchupSource?.let { template ->
            return Catchup.fromTemplate(template, startMs, endMs)
        }
        // Xtream timeshift; guideFor already decided replayability.
        val playlist = storage.playlists.get(channel.playlistId) ?: return null
        val creds = playlist.credentials() ?: return null
        val streamId = channel.xtreamStreamId
            ?: Regex("""/(\d+)\.\w{1,5}$""")
                .find(channel.url.substringBefore('?'))?.groupValues?.get(1)?.toLongOrNull()
            ?: return null
        val durationMinutes = ((endMs - startMs + 59_999) / 60_000L).toInt()
        return Xtream.catchupUrl(creds, streamId, startMs, durationMinutes, panelTimeZone(playlist))
    }

    // Panels read timeshift timestamps in server_info.timezone.
    private suspend fun panelTimeZone(playlist: Playlist): TimeZone =
        account.accountInfo(playlist)?.timezone
            ?.let { name -> runCatching { TimeZone.of(name) }.getOrNull() }
            ?: TimeZone.currentSystemDefault()
}
