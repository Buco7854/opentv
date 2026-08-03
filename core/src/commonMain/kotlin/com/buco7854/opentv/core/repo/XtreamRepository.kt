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
import com.buco7854.opentv.core.xtream.XtreamApiException
import com.buco7854.opentv.core.xtream.XtreamCredentials
import com.buco7854.opentv.core.xtream.XtreamEpgEntry
import kotlinx.datetime.TimeZone
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

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
    private val clock: () -> Long = ::nowMs,
) {
    companion object {
        const val EPISODES_CACHE_MS = 24L * 60 * 60 * 1000
        const val VOD_INFO_CACHE_MS = 30L * 24 * 60 * 60 * 1000
        const val EPG_CACHE_MS = 10L * 60 * 1000
        private const val MAX_EPG_CACHE_ENTRIES = 512
        private const val PANEL_EPG_TIMEOUT_MS = 4_000L
        private const val SLOW_PANEL_BACKOFF_MS = 60_000L
    }

    private val mutex = Mutex()

    /** Fetch and cache the episode list for one series (throttled to 24h). */
    suspend fun ensureEpisodes(playlistId: Long, seriesId: Long, force: Boolean = false) {
        mutex.withLock {
            val series = storage.xtreamSeries.get(playlistId, seriesId) ?: return
            val seriesKey = xtreamSeriesKey(seriesId)
            val now = clock()
            if (!force && series.episodesFetchedAtMs > 0 &&
                now - series.episodesFetchedAtMs < EPISODES_CACHE_MS
            ) return

            val cached = storage.channels.countEpisodes(playlistId, seriesKey) > 0
            val creds = storage.playlists.get(playlistId)?.credentials() ?: return
            val episodes = xtreamApi.fetchSeriesEpisodes(creds, seriesId)
            if (episodes.isEmpty()) {
                // Say so where it can be read back. Whether the panel truly has no
                // episodes or replied in a shape we could not parse is the one thing
                // an empty series page cannot tell you from the outside.
                log.log(
                    "Series episodes",
                    XtreamApiException("Panel listed no episodes for series $seriesId"),
                )
                // An empty answer is not proof that the series is empty: a panel that
                // hiccups, rate-limits, or replies in a shape we could not read looks
                // exactly like one with no episodes. Keep whatever we already had.
                //
                // Only record the attempt when we did. Stamping an empty first fetch
                // silences the next day of asking, and since the stamp is what the
                // early return above consults, the series page stays blank and its
                // retry cannot reach the panel at all. Leaving it unstamped costs one
                // panel call each time that page is opened, which is the right price
                // for the difference between "no episodes" and "we never got any".
                if (cached) storage.xtreamSeries.setEpisodesFetched(playlistId, seriesId, now)
                return
            }

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
    private fun hasPanelDetail(metadata: Metadata): Boolean =
        metadata.overview != null || metadata.castNames != null || metadata.rating != null

    suspend fun vodMetadata(channel: Channel): Metadata? {
        val streamId = channel.xtreamStreamId ?: return null
        val cacheKey = "xtreamvod:${channel.playlistId}:$streamId"
        val now = clock()
        storage.metadata.get(cacheKey)
            ?.takeIf { now - it.fetchedAtMs < VOD_INFO_CACHE_MS }
            ?.let { return it.takeIf(::hasPanelDetail) }

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
            entity.takeIf(::hasPanelDetail)
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
    private val slowPanelUntilMs = HashMap<Long, Long>()
    private val epgCacheMutex = Mutex()

    private suspend fun cachedEpg(key: String, now: Long): List<XtreamEpgEntry>? =
        epgCacheMutex.withLock {
            epgCache[key]?.takeIf { now - it.atMs < EPG_CACHE_MS }?.entries
        }

    private suspend fun storeEpg(key: String, entries: List<XtreamEpgEntry>, now: Long) {
        if (entries.isEmpty()) return
        epgCacheMutex.withLock {
            if (epgCache.size >= MAX_EPG_CACHE_ENTRIES) epgCache.clear()
            epgCache[key] = CachedEpg(entries, now)
        }
    }

    private suspend fun panelEpgBackedOff(playlistId: Long, now: Long): Boolean =
        epgCacheMutex.withLock {
            val until = slowPanelUntilMs[playlistId] ?: return@withLock false
            if (now < until) true else {
                slowPanelUntilMs.remove(playlistId)
                false
            }
        }

    private suspend fun backOffPanelEpg(playlistId: Long) {
        epgCacheMutex.withLock {
            slowPanelUntilMs[playlistId] = clock() + SLOW_PANEL_BACKOFF_MS
        }
    }

    private suspend fun fetchPanelEpg(
        playlistId: Long,
        creds: XtreamCredentials,
        streamId: Long,
    ): List<XtreamEpgEntry> {
        if (panelEpgBackedOff(playlistId, clock())) return emptyList()
        val entries = withTimeoutOrNull(PANEL_EPG_TIMEOUT_MS) {
            runCatching { xtreamApi.fetchChannelEpg(creds, streamId) }
                .getOrElse {
                    it.rethrowCancellation()
                    log.log("Channel EPG", it)
                    emptyList()
                }
        }
        if (entries != null) return entries
        backOffPanelEpg(playlistId)
        log.log("Channel EPG", XtreamApiException("Panel EPG timed out"))
        return emptyList()
    }

    suspend fun forgetPlaylist(playlistId: Long) {
        epgCacheMutex.withLock {
            epgCache.keys.removeAll { it.startsWith("$playlistId:") }
            slowPanelUntilMs.remove(playlistId)
        }
    }

    /**
     * Full guide for one channel with per-row catch-up availability. Prefers the
     * panel's per-channel table (get_simple_data_table) since it carries past
     * programmes and an archive flag; falls back to stored XMLTV.
     */
    suspend fun guideFor(channel: Channel): List<GuideEntry> {
        val now = clock()
        val streamId = channel.xtreamStreamId
        if (streamId != null) {
            val creds = storage.playlists.get(channel.playlistId)?.credentials()
            if (creds != null) {
                val key = "${channel.playlistId}:$streamId"
                val cached = cachedEpg(key, now)
                    ?: fetchPanelEpg(channel.playlistId, creds, streamId)
                        .also { storeEpg(key, it, now) }
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
        val durationMs = endMs - startMs
        if (endMs <= startMs || durationMs <= 0) return null
        // M3U catchup-source template wins when present.
        channel.catchupSource?.let { template ->
            return Catchup.fromTemplate(template, startMs, endMs, nowMs = clock())
        }
        // Xtream timeshift. This builder validates arithmetic; a caller accepting raw
        // timestamps must separately require a replayable guide row/archive window.
        val playlist = storage.playlists.get(channel.playlistId) ?: return null
        val creds = playlist.credentials() ?: return null
        val streamId = channel.xtreamStreamId
            ?: Regex("""/(\d+)\.\w{1,5}$""")
                .find(channel.url.substringBefore('?'))?.groupValues?.get(1)?.toLongOrNull()
            ?: return null
        val durationMinutesLong = (durationMs - 1) / 60_000L + 1
        if (durationMinutesLong > Int.MAX_VALUE) return null
        val durationMinutes = durationMinutesLong.toInt()
        return Xtream.catchupUrl(creds, streamId, startMs, durationMinutes, panelTimeZone(playlist))
    }

    // Panels read timeshift timestamps in server_info.timezone.
    private suspend fun panelTimeZone(playlist: Playlist): TimeZone {
        val timezone = when (val result = account.accountInfo(playlist)) {
            is AccountInfoResult.Fresh -> result.info.timezone
            // A panel timezone changes extremely rarely; its cached value is safer for
            // catch-up timestamps than silently substituting the device timezone.
            is AccountInfoResult.Stale -> result.info.timezone
            is AccountInfoResult.Unavailable -> null
        }
        return timezone
            ?.let { name -> runCatching { TimeZone.of(name) }.getOrNull() }
            ?: TimeZone.currentSystemDefault()
    }
}
