package com.buco7854.opentv.core.repo

import com.buco7854.opentv.core.log.CoreLog
import com.buco7854.opentv.core.log.rethrowCancellation
import com.buco7854.opentv.core.m3u.ContentClassifier
import com.buco7854.opentv.core.m3u.M3uParser
import com.buco7854.opentv.core.model.Channel
import com.buco7854.opentv.core.model.ChannelKind
import com.buco7854.opentv.core.model.GroupOverride
import com.buco7854.opentv.core.model.Playlist
import com.buco7854.opentv.core.net.ConditionalFetch
import com.buco7854.opentv.core.net.ConditionalFetcher
import com.buco7854.opentv.core.storage.Storage
import com.buco7854.opentv.core.util.nowMs
import com.buco7854.opentv.core.xtream.Xtream
import com.buco7854.opentv.core.xtream.XtreamApi
import com.buco7854.opentv.core.xtream.XtreamApiException
import com.buco7854.opentv.core.xtream.XtreamAuthException
import com.buco7854.opentv.core.xtream.XtreamCredentials
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The panel API is reachable but unusable (bad address / filtered agent). */
class XtreamUnreachableException(message: String) : Exception(message)

internal fun Playlist.credentials(): XtreamCredentials? {
    return XtreamCredentials(xtreamBase ?: return null, xtreamUser ?: return null, xtreamPass ?: return null)
}

class PlaylistRepository(
    private val storage: Storage,
    private val xtreamApi: XtreamApi,
    private val fetcher: ConditionalFetcher,
    private val log: CoreLog,
) {

    companion object {
        /** Auto-refresh throttle (unless forced). */
        const val MIN_REFRESH_INTERVAL_MS = 6L * 60 * 60 * 1000
        /** Auto-retry backoff after a failure. */
        const val FAILURE_RETRY_INTERVAL_MS = 5L * 60 * 1000
        /** Rate limit even on explicit refresh taps. */
        const val FORCED_MIN_INTERVAL_MS = 30_000L
    }

    private val refreshMutex = Mutex()

    /** Last attempt (success or failure) per playlist; backs failure backoff and forced-tap limiting. */
    private val lastAttemptMs = HashMap<Long, Long>()

    val playlists = storage.playlists.observeAll()

    /** Add an Xtream playlist; validates the login first so bad creds surface clearly. */
    suspend fun addFromXtream(name: String, server: String, username: String, password: String): Long {
        val base = Xtream.normalizeServer(server)
            ?: throw IllegalArgumentException("Invalid server address")
        val creds = XtreamCredentials(base, username.trim(), password.trim())
        validateXtream(creds)
        val id = storage.playlists.insert(
            Playlist(
                name = name.ifBlank { "Xtream" },
                url = null, // API-driven, no M3U URL
                epgUrl = Xtream.xmltvUrl(creds),
                xtreamBase = creds.base,
                xtreamUser = creds.user,
                xtreamPass = creds.pass,
            )
        )
        refresh(id, force = true)
        return id
    }

    /** Confirm an Xtream login works, turning failures into actionable messages. */
    private suspend fun validateXtream(creds: XtreamCredentials) {
        try {
            xtreamApi.fetchAccountInfo(creds) // throws XtreamAuthException on bad credentials
        } catch (e: XtreamAuthException) {
            throw e // surface the panel's verdict as-is
        } catch (e: Exception) {
            e.rethrowCancellation()
            // Reachable host but API errored (404/403): usually wrong URL/port or filtered User-Agent.
            throw XtreamUnreachableException(
                "Could not reach the panel API at ${creds.base} (${e.message}). " +
                    "Check the server address and port, and try a different User-Agent in Settings."
            )
        }
    }

    /** Update an Xtream playlist; re-pulls content only when creds actually changed. */
    suspend fun updateXtream(id: Long, name: String, server: String, username: String, password: String) {
        val existing = storage.playlists.get(id) ?: return
        val base = Xtream.normalizeServer(server)
            ?: throw IllegalArgumentException("Invalid server address")
        val creds = XtreamCredentials(base, username.trim(), password.trim())
        val credsChanged = existing.xtreamBase != base ||
            existing.xtreamUser != creds.user || existing.xtreamPass != creds.pass
        if (credsChanged) validateXtream(creds)
        storage.playlists.update(
            existing.copy(
                name = name.ifBlank { existing.name },
                xtreamBase = creds.base,
                xtreamUser = creds.user,
                xtreamPass = creds.pass,
                epgUrl = Xtream.xmltvUrl(creds),
                // Force next refresh to re-pull against the new account.
                lastRefreshedMs = if (credsChanged) 0 else existing.lastRefreshedMs,
                epgEtag = null,
                epgLastModified = null,
                epgLastRefreshedMs = if (credsChanged) 0 else existing.epgLastRefreshedMs,
            )
        )
        if (credsChanged) {
            lastAttemptMs.remove(id) // don't let forced-tap throttling skip this
            refresh(id, force = true)
        }
    }

    /** Update an M3U playlist; re-fetches content only when the URL changed. */
    suspend fun updateUrl(id: Long, name: String, url: String, epgUrl: String?) {
        val existing = storage.playlists.get(id) ?: return
        val trimmedUrl = url.trim()
        if (trimmedUrl.isEmpty()) throw IllegalArgumentException("Playlist URL cannot be empty")
        val creds = Xtream.detect(trimmedUrl)
        val urlChanged = existing.url != trimmedUrl
        storage.playlists.update(
            existing.copy(
                name = name.ifBlank { existing.name },
                url = trimmedUrl,
                epgUrl = epgUrl?.trim()?.takeIf { it.isNotBlank() },
                xtreamBase = creds?.base,
                xtreamUser = creds?.user,
                xtreamPass = creds?.pass,
                etag = if (urlChanged) null else existing.etag,
                lastModified = if (urlChanged) null else existing.lastModified,
                lastRefreshedMs = if (urlChanged) 0 else existing.lastRefreshedMs,
                epgEtag = null,
                epgLastModified = null,
                epgLastRefreshedMs = 0,
            )
        )
        if (urlChanged) {
            lastAttemptMs.remove(id)
            refresh(id, force = true)
        }
    }

    suspend fun addFromUrl(name: String, url: String, epgUrl: String?): Long {
        val creds = Xtream.detect(url)
        val id = storage.playlists.insert(
            Playlist(
                name = name.ifBlank { "Playlist" },
                url = url.trim(),
                epgUrl = epgUrl?.trim()?.takeIf { it.isNotBlank() },
                xtreamBase = creds?.base,
                xtreamUser = creds?.user,
                xtreamPass = creds?.pass,
            )
        )
        refresh(id, force = true)
        return id
    }

    /** Rename any playlist without touching its content or contacting the provider. */
    suspend fun rename(id: Long, name: String) {
        val existing = storage.playlists.get(id) ?: return
        val trimmed = name.trim()
        if (trimmed.isNotEmpty() && trimmed != existing.name) {
            storage.playlists.update(existing.copy(name = trimmed))
        }
    }

    /** Import a playlist from local file content (SAF pick on Android, upload on web). */
    suspend fun importFromLines(name: String, lines: Sequence<String>): Long {
        val id = storage.playlists.insert(Playlist(name = name.ifBlank { "Imported playlist" }, url = null))
        var epgFromFile: String? = null
        val count = ingest(id, lines, emptyMap()) { epgFromFile = it }
        val playlist = storage.playlists.get(id)!!
        storage.playlists.update(
            playlist.copy(
                channelCount = count,
                epgUrl = playlist.epgUrl ?: epgFromFile,
                lastRefreshedMs = nowMs(),
            )
        )
        return id
    }

    /** Replace the contents of a file-imported playlist with newly picked content. */
    suspend fun replaceFromLines(id: Long, name: String, lines: Sequence<String>) {
        val existing = storage.playlists.get(id) ?: return
        storage.channels.deleteForPlaylist(id)
        var epgFromFile: String? = null
        val overrides = storage.groupOverrides.forPlaylist(id).associate { it.groupTitle to it.kind }
        val count = ingest(id, lines, overrides) { epgFromFile = it }
        storage.playlists.update(
            existing.copy(
                name = name.ifBlank { existing.name },
                channelCount = count,
                epgUrl = epgFromFile ?: existing.epgUrl,
                lastRefreshedMs = nowMs(),
            )
        )
    }

    /** Refresh a remote playlist: throttled, single-flight, conditional GET. */
    suspend fun refresh(playlistId: Long, force: Boolean = false) {
        refreshMutex.withLock {
            val playlist = storage.playlists.get(playlistId) ?: return
            val now = nowMs()
            val lastAttempt = lastAttemptMs[playlistId] ?: 0L
            if (force) {
                if (now - lastAttempt < FORCED_MIN_INTERVAL_MS) return
            } else {
                if (now - playlist.lastRefreshedMs < MIN_REFRESH_INTERVAL_MS) return
                if (now - lastAttempt < FAILURE_RETRY_INTERVAL_MS) return
            }

            val url = playlist.url
            if (url == null) {
                // Xtream playlists refresh via the panel API; file imports have nothing to refresh.
                if (playlist.xtreamBase != null) {
                    lastAttemptMs[playlistId] = now
                    refreshXtream(playlist, now)
                }
                return
            }
            lastAttemptMs[playlistId] = now

            when (val result = fetcher.conditionalGet(url, playlist.etag, playlist.lastModified)) {
                is ConditionalFetch.NotModified -> {
                    storage.playlists.update(playlist.copy(lastRefreshedMs = now))
                }
                is ConditionalFetch.Success -> try {
                    var epgFromFile: String? = null
                    val overrides = storage.groupOverrides.forPlaylist(playlistId)
                        .associate { it.groupTitle to it.kind }
                    // Drop validators before wiping, else a mid-stream failure leaves a 304 over empty channels.
                    storage.playlists.update(playlist.copy(etag = null, lastModified = null))
                    storage.channels.deleteForPlaylist(playlistId)
                    val count = ingest(playlistId, result.body.lines(), overrides) { epgFromFile = it }
                    storage.playlists.update(
                        playlist.copy(
                            etag = result.etag,
                            lastModified = result.lastModified,
                            lastRefreshedMs = now,
                            channelCount = count,
                            epgUrl = playlist.epgUrl ?: epgFromFile,
                        )
                    )
                } finally {
                    result.body.close()
                }
            }
        }
    }

    /**
     * Full refresh via the panel API: six requests (three category + three stream lists).
     * Series episodes load lazily per-series (XtreamRepository) to avoid one request per show.
     */
    private suspend fun refreshXtream(playlist: Playlist, now: Long) {
        val creds = playlist.credentials()!!

        val liveCategories = runCatching { xtreamApi.fetchCategories(creds, "get_live_categories") }
            .getOrElse { it.rethrowCancellation(); log.log("Xtream refresh", it); emptyMap() }
        val vodCategories = runCatching { xtreamApi.fetchCategories(creds, "get_vod_categories") }
            .getOrElse { it.rethrowCancellation(); emptyMap() }
        val seriesCategories = runCatching { xtreamApi.fetchCategories(creds, "get_series_categories") }
            .getOrElse { it.rethrowCancellation(); emptyMap() }

        // Live failing is fatal (bad login / dead panel); VOD and series may legitimately be absent.
        val live = xtreamApi.fetchLiveStreams(creds)
        val vod = runCatching { xtreamApi.fetchVodStreams(creds) }
            .getOrElse { it.rethrowCancellation(); log.log("Xtream VOD list", it); emptyList() }
        val seriesList = runCatching { xtreamApi.fetchSeriesList(creds) }
            .getOrElse { it.rethrowCancellation(); log.log("Xtream series list", it); emptyList() }

        // Panels transiently return empty lists (rate limiting / account block); keep
        // existing content for any section that had entries but came back empty.
        val oldLive = storage.channels.count(playlist.id, ChannelKind.LIVE)
        val oldVod = storage.channels.count(playlist.id, ChannelKind.MOVIE)
        val oldSeries = storage.xtreamSeries.count(playlist.id)
        val keepLive = live.isEmpty() && oldLive > 0
        val keepVod = vod.isEmpty() && oldVod > 0
        val keepSeries = seriesList.isEmpty() && oldSeries > 0
        if (keepLive || keepVod || keepSeries) {
            val kept = listOfNotNull(
                "live".takeIf { keepLive }, "VOD".takeIf { keepVod }, "series".takeIf { keepSeries },
            )
            log.log(
                "Xtream refresh",
                XtreamApiException("Panel returned empty ${kept.joinToString("/")} list(s); keeping existing content"),
            )
        }

        if (!keepLive) storage.channels.deleteForPlaylistKind(playlist.id, ChannelKind.LIVE)
        if (!keepVod) storage.channels.deleteForPlaylistKind(playlist.id, ChannelKind.MOVIE)
        if (!keepSeries) {
            // Cached episodes are kind=SERIES channel rows; drop them with the catalog.
            storage.channels.deleteForPlaylistKind(playlist.id, ChannelKind.SERIES)
            storage.xtreamSeries.deleteForPlaylist(playlist.id)
        }

        var position = 0
        val batch = ArrayList<Channel>(500)
        suspend fun flush() {
            if (batch.isNotEmpty()) {
                storage.channels.insertAll(ArrayList(batch))
                batch.clear()
            }
        }
        for (stream in live) {
            batch.add(
                Channel(
                    playlistId = playlist.id,
                    name = stream.name,
                    url = Xtream.liveUrl(creds, stream.streamId),
                    logo = stream.icon,
                    groupTitle = liveCategories[stream.categoryId] ?: "Live",
                    tvgId = stream.epgChannelId,
                    kind = ChannelKind.LIVE,
                    seriesKey = null,
                    season = null,
                    episode = null,
                    position = position++,
                    xtreamStreamId = stream.streamId,
                    catchupDays = stream.archiveDays,
                )
            )
            if (batch.size >= 500) flush()
        }
        for (stream in vod) {
            batch.add(
                Channel(
                    playlistId = playlist.id,
                    name = stream.name,
                    url = Xtream.vodUrl(creds, stream.streamId, stream.containerExtension),
                    logo = stream.icon,
                    groupTitle = vodCategories[stream.categoryId] ?: "Movies",
                    tvgId = null,
                    kind = ChannelKind.MOVIE,
                    seriesKey = null,
                    season = null,
                    episode = null,
                    position = position++,
                    xtreamStreamId = stream.streamId,
                )
            )
            if (batch.size >= 500) flush()
        }
        flush()

        seriesList.chunked(500).forEach { chunk ->
            storage.xtreamSeries.insertAll(
                chunk.map { item ->
                    com.buco7854.opentv.core.model.XtreamSeries(
                        playlistId = playlist.id,
                        seriesId = item.seriesId,
                        name = item.name,
                        categoryName = seriesCategories[item.categoryId] ?: "Series",
                        cover = item.cover,
                        plot = item.plot,
                        castNames = item.cast,
                        genre = item.genre,
                        rating = item.rating,
                    )
                }
            )
        }

        storage.playlists.update(
            playlist.copy(
                lastRefreshedMs = now,
                channelCount = (if (keepLive) oldLive else live.size) +
                    (if (keepVod) oldVod else vod.size) +
                    (if (keepSeries) oldSeries else seriesList.size),
                epgUrl = playlist.epgUrl ?: Xtream.xmltvUrl(creds),
            )
        )
    }

    /** Inserts entries in batches of 500 to keep large playlists off-heap. */
    private suspend fun ingest(
        playlistId: Long,
        lines: Sequence<String>,
        overrides: Map<String, Int>,
        onEpgUrl: (String?) -> Unit,
    ): Int {
        val batch = ArrayList<Channel>(500)
        var position = 0
        M3uParser.parse(lines, onHeader = { onEpgUrl(it.epgUrl) }) { entry ->
            // User category overrides beat the heuristics.
            var kind = entry.kind
            var seriesKey = entry.seriesKey
            var season = entry.season
            var episode = entry.episode
            when (val forced = overrides[entry.groupTitle]) {
                null, kind -> {}
                ChannelKind.SERIES -> {
                    val c = ContentClassifier.asSeries(entry.name)
                    kind = ChannelKind.SERIES
                    seriesKey = c.seriesKey
                    season = c.season
                    episode = c.episode
                }
                else -> {
                    kind = forced
                    seriesKey = null
                    season = null
                    episode = null
                }
            }
            batch.add(
                Channel(
                    playlistId = playlistId,
                    name = entry.name,
                    url = entry.url,
                    logo = entry.logo,
                    groupTitle = entry.groupTitle,
                    tvgId = entry.tvgId,
                    kind = kind,
                    seriesKey = seriesKey,
                    season = season,
                    episode = episode,
                    position = position++,
                    catchupDays = if (kind == ChannelKind.LIVE) entry.catchupDays else 0,
                    catchupSource = if (kind == ChannelKind.LIVE) entry.catchupSource else null,
                )
            )
            if (batch.size >= 500) {
                storage.channels.insertAll(ArrayList(batch))
                batch.clear()
            }
        }
        if (batch.isNotEmpty()) storage.channels.insertAll(batch)
        return position
    }

    /**
     * Record a category override and retag existing rows now. Series regrouping
     * (SxxExx extraction) is approximate until the next refresh re-ingests.
     */
    suspend fun setGroupOverride(playlistId: Long, groupTitle: String, kind: Int?) {
        if (kind == null) {
            storage.groupOverrides.remove(playlistId, groupTitle)
        } else {
            storage.groupOverrides.upsert(GroupOverride(playlistId, groupTitle, kind))
            if (kind == ChannelKind.SERIES) {
                storage.channels.retagGroupAsSeries(playlistId, groupTitle)
            } else {
                storage.channels.retagGroup(playlistId, groupTitle, kind)
            }
        }
    }

    suspend fun delete(playlistId: Long) {
        storage.channels.deleteForPlaylist(playlistId)
        storage.epg.deleteForPlaylist(playlistId)
        storage.xtreamSeries.deleteForPlaylist(playlistId)
        storage.favorites.deleteForPlaylist(playlistId)
        storage.groupOverrides.deleteForPlaylist(playlistId)
        storage.playlists.delete(playlistId)
    }

    suspend fun setEpgUrl(playlistId: Long, epgUrl: String?) {
        val playlist = storage.playlists.get(playlistId) ?: return
        storage.playlists.update(
            playlist.copy(
                epgUrl = epgUrl?.trim()?.takeIf { it.isNotBlank() },
                epgEtag = null,
                epgLastModified = null,
                epgLastRefreshedMs = 0,
            )
        )
    }
}
