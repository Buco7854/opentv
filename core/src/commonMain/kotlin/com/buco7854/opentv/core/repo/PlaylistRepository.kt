package com.buco7854.opentv.core.repo

import com.buco7854.opentv.core.log.CoreLog
import com.buco7854.opentv.core.log.rethrowCancellation
import com.buco7854.opentv.core.m3u.ContentClassifier
import com.buco7854.opentv.core.m3u.M3uEntry
import com.buco7854.opentv.core.m3u.M3uParser
import com.buco7854.opentv.core.model.Channel
import com.buco7854.opentv.core.model.ChannelKind
import com.buco7854.opentv.core.model.GroupOverride
import com.buco7854.opentv.core.model.Playlist
import com.buco7854.opentv.core.model.XtreamSeries
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
    private val account: AccountRepository? = null,
) {
    class PreparedRefresh internal constructor(
        internal val source: Playlist,
        internal val atMs: Long,
        internal val payload: Payload,
    )

    internal sealed interface Payload {
        data class M3u(
            val channels: List<Channel>,
            val epgUrl: String?,
            val etag: String?,
            val lastModified: String?,
        ) : Payload

        data class Xtream(
            val channels: List<Channel>,
            val series: List<XtreamSeries>,
            val liveCount: Int,
            val vodCount: Int,
            val seriesCount: Int,
        ) : Payload
    }

    companion object {
        /** Auto-refresh throttle (unless forced). */
        const val MIN_REFRESH_INTERVAL_MS = 6L * 60 * 60 * 1000
        /** Auto-retry backoff after a failure. */
        const val FAILURE_RETRY_INTERVAL_MS = 5L * 60 * 1000
        /** Rate limit even on explicit refresh taps. */
        const val FORCED_MIN_INTERVAL_MS = 30_000L
    }

    // Refreshing playlist A must not make playlist B wait: each playlist gets its own lock
    // scoped by this map, guarded only long enough to look it up or create it. The same
    // lock also protects `lastAttemptMs`, since without it two playlists refreshing at
    // once could corrupt that shared, plain HashMap.
    private val locksMutex = Mutex()
    private val playlistLocks = HashMap<Long, Mutex>()
    private val lastAttemptMs = HashMap<Long, Long>()

    private suspend fun playlistLock(playlistId: Long): Mutex =
        locksMutex.withLock { playlistLocks.getOrPut(playlistId) { Mutex() } }

    private suspend fun lastAttemptAt(playlistId: Long): Long =
        locksMutex.withLock { lastAttemptMs[playlistId] ?: 0L }

    private suspend fun markAttempt(playlistId: Long, atMs: Long) {
        locksMutex.withLock { lastAttemptMs[playlistId] = atMs }
    }

    private suspend fun clearAttempt(playlistId: Long) {
        locksMutex.withLock { lastAttemptMs.remove(playlistId) }
    }

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
            clearAttempt(id)
            account?.invalidate(id)
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
            clearAttempt(id)
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

    /** Network and parsing phase. Never holds the server's catalog gate while fetching. */
    suspend fun prepareRefresh(playlistId: Long, force: Boolean = false): PreparedRefresh? =
        playlistLock(playlistId).withLock {
            val playlist = storage.playlists.get(playlistId) ?: return null
            val now = nowMs()
            val lastAttempt = lastAttemptAt(playlistId)
            if (force) {
                if (now - lastAttempt < FORCED_MIN_INTERVAL_MS) return null
            } else {
                if (now - playlist.lastRefreshedMs < MIN_REFRESH_INTERVAL_MS) return null
                if (now - lastAttempt < FAILURE_RETRY_INTERVAL_MS) return null
            }

            val url = playlist.url
            if (url == null) {
                // Xtream playlists refresh via the panel API; file imports have nothing to refresh.
                if (playlist.xtreamBase == null) return null
                markAttempt(playlistId, now)
                return PreparedRefresh(playlist, now, prepareXtream(playlist))
            }
            markAttempt(playlistId, now)

            when (val result = fetcher.conditionalGet(url, playlist.etag, playlist.lastModified)) {
                is ConditionalFetch.NotModified -> {
                    val current = storage.playlists.get(playlistId)
                    if (current != null && current.sameSourceAs(playlist)) {
                        storage.playlists.update(current.copy(lastRefreshedMs = now))
                    }
                    null
                }
                is ConditionalFetch.Success -> try {
                    result.body.readLines { lines ->
                        var epgFromFile: String? = null
                        val rows = parseChannels(playlistId, lines) { epgFromFile = it }
                        PreparedRefresh(
                            playlist, now,
                            Payload.M3u(rows, epgFromFile, result.etag, result.lastModified),
                        )
                    }
                } finally {
                    result.body.close()
                }
            }
        }

    /** Apply only the prepared catalog mutation; callers may hold a catalog gate here. */
    suspend fun applyRefresh(prepared: PreparedRefresh): Boolean {
        val playlist = storage.playlists.get(prepared.source.id) ?: return false
        if (!playlist.sameSourceAs(prepared.source)) return false
        when (val payload = prepared.payload) {
            is Payload.M3u -> {
                val overrides = storage.groupOverrides.forPlaylist(playlist.id)
                    .associate { it.groupTitle to it.kind }
                val rows = payload.channels.map { applyOverride(it, overrides) }
                storage.channels.replaceKinds(
                    playlist.id,
                    listOf(ChannelKind.LIVE, ChannelKind.MOVIE, ChannelKind.SERIES),
                    rows,
                )
                storage.playlists.update(
                    playlist.copy(
                        etag = payload.etag,
                        lastModified = payload.lastModified,
                        lastRefreshedMs = prepared.atMs,
                        channelCount = rows.size,
                        epgUrl = playlist.epgUrl ?: payload.epgUrl,
                    )
                )
            }
            is Payload.Xtream -> applyXtream(playlist, prepared.atMs, payload)
        }
        return true
    }

    suspend fun refresh(playlistId: Long, force: Boolean = false): Boolean =
        prepareRefresh(playlistId, force)?.let { applyRefresh(it) } ?: false

    private fun Playlist.sameSourceAs(other: Playlist): Boolean =
        url == other.url && xtreamBase == other.xtreamBase &&
            xtreamUser == other.xtreamUser && xtreamPass == other.xtreamPass

    /**
     * Full refresh via the panel API: six requests (three category + three stream lists).
     * Series episodes load lazily per-series (XtreamRepository) to avoid one request per show.
     */
    private suspend fun prepareXtream(playlist: Playlist): Payload.Xtream {
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

        // Build rows without consulting the current catalog or category corrections.
        var position = 0
        val channelRows = ArrayList<Channel>(live.size + vod.size)
        for (stream in live) {
            channelRows.add(
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
        }
        for (stream in vod) {
            channelRows.add(
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
        }
        val seriesRows = seriesList.map { item ->
            XtreamSeries(
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
        return Payload.Xtream(channelRows, seriesRows, live.size, vod.size, seriesList.size)
    }

    private suspend fun applyXtream(playlist: Playlist, now: Long, prepared: Payload.Xtream) {
        // Panels transiently return empty lists (rate limiting / account block); keep
        // existing content for any section that had entries but came back empty.
        val oldLive = storage.channels.count(playlist.id, ChannelKind.LIVE)
        val oldVod = storage.channels.count(playlist.id, ChannelKind.MOVIE)
        val oldSeries = storage.xtreamSeries.count(playlist.id)
        val keepLive = prepared.liveCount == 0 && oldLive > 0
        val keepVod = prepared.vodCount == 0 && oldVod > 0
        val keepSeries = prepared.seriesCount == 0 && oldSeries > 0
        if (keepLive || keepVod || keepSeries) {
            val kept = listOfNotNull(
                "live".takeIf { keepLive }, "VOD".takeIf { keepVod }, "series".takeIf { keepSeries },
            )
            log.log(
                "Xtream refresh",
                XtreamApiException("Panel returned empty ${kept.joinToString("/")} list(s); keeping existing content"),
            )
        }

        // The prepared rows replace each table atomically, so browse observers never see
        // the playlist momentarily empty during a refresh.
        // A kept kind's source list is empty (that is what "keep" means), so it is neither
        // deleted nor re-inserted. Cached episodes (kind=SERIES) drop with the catalog.
        val replaceKinds = buildList {
            if (!keepLive) add(ChannelKind.LIVE)
            if (!keepVod) add(ChannelKind.MOVIE)
            if (!keepSeries) add(ChannelKind.SERIES)
        }
        val overrides = storage.groupOverrides.forPlaylist(playlist.id)
            .associate { it.groupTitle to it.kind }
        val corrected = if (overrides.isEmpty()) prepared.channels else prepared.channels.map { row ->
            when (val forced = overrides[row.groupTitle]) {
                null -> row
                row.kind -> row
                else -> row.copy(kind = forced)
            }
        }
        storage.channels.replaceKinds(playlist.id, replaceKinds, corrected)

        if (!keepSeries) {
            storage.xtreamSeries.replaceAll(playlist.id, prepared.series)
        }

        storage.playlists.update(
            playlist.copy(
                lastRefreshedMs = now,
                channelCount = (if (keepLive) oldLive else prepared.liveCount) +
                    (if (keepVod) oldVod else prepared.vodCount) +
                    (if (keepSeries) oldSeries else prepared.seriesCount),
                epgUrl = playlist.epgUrl ?: Xtream.xmltvUrl(playlist.credentials()!!),
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
            batch.add(applyOverride(entry.toChannel(playlistId, position++), overrides))
            if (batch.size >= 500) {
                storage.channels.insertAll(ArrayList(batch))
                batch.clear()
            }
        }
        if (batch.isNotEmpty()) storage.channels.insertAll(batch)
        return position
    }

    private suspend fun parseChannels(
        playlistId: Long,
        lines: Sequence<String>,
        onEpgUrl: (String?) -> Unit,
    ): List<Channel> {
        val rows = ArrayList<Channel>()
        M3uParser.parse(lines, onHeader = { onEpgUrl(it.epgUrl) }) { entry ->
            rows.add(entry.toChannel(playlistId, rows.size))
        }
        return rows
    }

    private fun M3uEntry.toChannel(playlistId: Long, position: Int) =
        Channel(
            playlistId = playlistId,
            name = name,
            url = url,
            logo = logo,
            groupTitle = groupTitle,
            tvgId = tvgId,
            kind = kind,
            seriesKey = seriesKey,
            season = season,
            episode = episode,
            position = position,
            catchupDays = catchupDays,
            catchupSource = catchupSource,
        )

    private fun applyOverride(row: Channel, overrides: Map<String, Int>): Channel =
        when (val forced = overrides[row.groupTitle]) {
            null, row.kind -> row.copy(
                catchupDays = if (row.kind == ChannelKind.LIVE) row.catchupDays else 0,
                catchupSource = if (row.kind == ChannelKind.LIVE) row.catchupSource else null,
            )
            ChannelKind.SERIES -> {
                val classified = ContentClassifier.asSeries(row.name)
                row.copy(
                    kind = ChannelKind.SERIES,
                    seriesKey = classified.seriesKey,
                    season = classified.season,
                    episode = classified.episode,
                    catchupDays = 0,
                    catchupSource = null,
                )
            }
            else -> row.copy(
                kind = forced,
                seriesKey = null,
                season = null,
                episode = null,
                catchupDays = if (forced == ChannelKind.LIVE) row.catchupDays else 0,
                catchupSource = if (forced == ChannelKind.LIVE) row.catchupSource else null,
            )
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
                val regrouped = storage.channels.inGroup(playlistId, groupTitle).map { channel ->
                    val classification = ContentClassifier.asSeries(channel.name)
                    channel.copy(
                        kind = ChannelKind.SERIES,
                        seriesKey = classification.seriesKey,
                        season = classification.season,
                        episode = classification.episode,
                    )
                }
                storage.channels.updateAll(regrouped)
            } else {
                storage.channels.retagGroup(playlistId, groupTitle, kind)
            }
        }
    }

    suspend fun delete(playlistId: Long) {
        storage.resume.deleteForPlaylist(playlistId)
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
