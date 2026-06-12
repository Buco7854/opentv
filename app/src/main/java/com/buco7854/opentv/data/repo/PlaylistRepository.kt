package com.buco7854.opentv.data.repo

import android.content.ContentResolver
import android.net.Uri
import com.buco7854.opentv.data.db.AppDatabase
import com.buco7854.opentv.data.db.ChannelEntity
import com.buco7854.opentv.data.db.ChannelKind
import com.buco7854.opentv.data.db.PlaylistEntity
import com.buco7854.opentv.data.db.XtreamSeriesEntity
import com.buco7854.opentv.data.m3u.M3uParser
import com.buco7854.opentv.data.net.Http
import com.buco7854.opentv.data.xtream.Xtream
import com.buco7854.opentv.data.xtream.XtreamCredentials
import com.buco7854.opentv.diag.ErrorLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

class PlaylistRepository(private val db: AppDatabase) {

    companion object {
        /** Don't re-download a playlist more often than this unless the user forces it. */
        const val MIN_REFRESH_INTERVAL_MS = 6L * 60 * 60 * 1000
        /** After a failed attempt, don't auto-retry sooner than this. */
        const val FAILURE_RETRY_INTERVAL_MS = 5L * 60 * 1000
        /** Even explicit refresh taps are rate-limited to protect the provider. */
        const val FORCED_MIN_INTERVAL_MS = 30_000L
    }

    private val refreshMutex = Mutex()

    /** Last attempt (success OR failure) per playlist, in-memory. Successful
     *  refreshes persist lastRefreshedMs; this map adds failure backoff and
     *  forced-tap rate limiting without a schema change. */
    private val lastAttemptMs = HashMap<Long, Long>()

    val playlists = db.playlistDao().observeAll()

    /**
     * Add a playlist by Xtream login (server + username + password). The login
     * is validated against player_api.php first, so bad credentials surface as
     * a clear error instead of an empty playlist.
     */
    suspend fun addFromXtream(name: String, server: String, username: String, password: String): Long {
        val base = Xtream.normalizeServer(server)
            ?: throw IllegalArgumentException("Invalid server address")
        val creds = XtreamCredentials(base, username.trim(), password.trim())
        Xtream.fetchAccountInfo(creds) // throws "Login rejected" on bad credentials
        val id = db.playlistDao().insert(
            PlaylistEntity(
                name = name.ifBlank { "Xtream" },
                url = null, // native Xtream playlists are API-driven, no M3U URL
                epgUrl = Xtream.xmltvUrl(creds),
                xtreamBase = creds.base,
                xtreamUser = creds.user,
                xtreamPass = creds.pass,
            )
        )
        refresh(id, force = true)
        return id
    }

    suspend fun addFromUrl(name: String, url: String, epgUrl: String?): Long {
        val creds = Xtream.detect(url)
        val id = db.playlistDao().insert(
            PlaylistEntity(
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

    suspend fun addFromFile(name: String, uri: Uri, resolver: ContentResolver): Long =
        withContext(Dispatchers.IO) {
            val id = db.playlistDao().insert(PlaylistEntity(name = name.ifBlank { "Imported playlist" }, url = null))
            var epgFromFile: String? = null
            val count = resolver.openInputStream(uri)?.use { stream ->
                ingest(id, BufferedReader(InputStreamReader(stream))) { epgFromFile = it }
            } ?: 0
            val playlist = db.playlistDao().get(id)!!
            db.playlistDao().update(
                playlist.copy(
                    channelCount = count,
                    epgUrl = playlist.epgUrl ?: epgFromFile,
                    lastRefreshedMs = System.currentTimeMillis(),
                )
            )
            id
        }

    /**
     * Refresh a remote playlist. Frugal by design: throttled to
     * [MIN_REFRESH_INTERVAL_MS], serialized behind a mutex so concurrent calls
     * collapse into one request, and a conditional GET so an unchanged file
     * costs a 304 with no body.
     */
    suspend fun refresh(playlistId: Long, force: Boolean = false): Unit = withContext(Dispatchers.IO) {
        refreshMutex.withLock {
            val playlist = db.playlistDao().get(playlistId) ?: return@withLock
            val now = System.currentTimeMillis()
            val lastAttempt = lastAttemptMs[playlistId] ?: 0L
            if (force) {
                if (now - lastAttempt < FORCED_MIN_INTERVAL_MS) return@withLock
            } else {
                if (now - playlist.lastRefreshedMs < MIN_REFRESH_INTERVAL_MS) return@withLock
                if (now - lastAttempt < FAILURE_RETRY_INTERVAL_MS) return@withLock
            }

            val url = playlist.url
            if (url == null) {
                // Native Xtream playlists refresh through the panel API; plain
                // local-file imports have nothing to refresh.
                if (playlist.xtreamBase != null) {
                    lastAttemptMs[playlistId] = now
                    refreshXtream(playlist, now)
                }
                return@withLock
            }
            lastAttemptMs[playlistId] = now

            when (val result = Http.conditionalGet(url, playlist.etag, playlist.lastModified)) {
                is Http.FetchResult.NotModified -> {
                    db.playlistDao().update(playlist.copy(lastRefreshedMs = now))
                }
                is Http.FetchResult.Success -> result.response.use { response ->
                    var epgFromFile: String? = null
                    val reader = BufferedReader(InputStreamReader(Http.bodyStream(response)))
                    // Drop the validators BEFORE wiping: if ingest dies mid-stream,
                    // the next refresh must re-download rather than get a 304
                    // against a now-empty channel table.
                    db.playlistDao().update(playlist.copy(etag = null, lastModified = null))
                    db.channelDao().deleteForPlaylist(playlistId)
                    val count = ingest(playlistId, reader) { epgFromFile = it }
                    db.playlistDao().update(
                        playlist.copy(
                            etag = result.etag,
                            lastModified = result.lastModified,
                            lastRefreshedMs = now,
                            channelCount = count,
                            epgUrl = playlist.epgUrl ?: epgFromFile,
                        )
                    )
                }
            }
        }
    }

    /**
     * Full refresh through the panel API: exactly six requests (three category
     * lists, three stream lists). Series episodes are NOT fetched here - they
     * load lazily per series via XtreamRepository, or this would cost one
     * request per show in the catalog.
     */
    private suspend fun refreshXtream(playlist: PlaylistEntity, now: Long) {
        val creds = XtreamCredentials(playlist.xtreamBase!!, playlist.xtreamUser!!, playlist.xtreamPass!!)

        val liveCategories = runCatching { Xtream.fetchCategories(creds, "get_live_categories") }
            .getOrElse { ErrorLog.log("Xtream refresh", it); emptyMap() }
        val vodCategories = runCatching { Xtream.fetchCategories(creds, "get_vod_categories") }
            .getOrElse { emptyMap() }
        val seriesCategories = runCatching { Xtream.fetchCategories(creds, "get_series_categories") }
            .getOrElse { emptyMap() }

        // Live failing is fatal (bad login / dead panel); VOD or series may
        // legitimately be absent from some subscriptions.
        val live = Xtream.fetchLiveStreams(creds)
        val vod = runCatching { Xtream.fetchVodStreams(creds) }
            .getOrElse { ErrorLog.log("Xtream VOD list", it); emptyList() }
        val seriesList = runCatching { Xtream.fetchSeriesList(creds) }
            .getOrElse { ErrorLog.log("Xtream series list", it); emptyList() }

        db.channelDao().deleteForPlaylist(playlist.id)
        db.xtreamSeriesDao().deleteForPlaylist(playlist.id)

        var position = 0
        val batch = ArrayList<ChannelEntity>(500)
        fun flush() {
            if (batch.isNotEmpty()) {
                db.channelDao().insertAll(ArrayList(batch))
                batch.clear()
            }
        }
        for (stream in live) {
            batch.add(
                ChannelEntity(
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
                ChannelEntity(
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
            db.xtreamSeriesDao().insertAll(
                chunk.map { item ->
                    XtreamSeriesEntity(
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

        db.playlistDao().update(
            playlist.copy(
                lastRefreshedMs = now,
                channelCount = live.size + vod.size + seriesList.size,
                epgUrl = playlist.epgUrl ?: Xtream.xmltvUrl(creds),
            )
        )
    }

    /**
     * Streams entries straight from the reader into Room in batches of 500, so a
     * 50k-entry playlist is never held in memory in full. Must be called on
     * Dispatchers.IO (the DAO insert is blocking).
     */
    private fun ingest(
        playlistId: Long,
        reader: BufferedReader,
        onEpgUrl: (String?) -> Unit,
    ): Int {
        val batch = ArrayList<ChannelEntity>(500)
        var position = 0
        M3uParser.parse(reader, onHeader = { onEpgUrl(it.epgUrl) }) { entry ->
            batch.add(
                ChannelEntity(
                    playlistId = playlistId,
                    name = entry.name,
                    url = entry.url,
                    logo = entry.logo,
                    groupTitle = entry.groupTitle,
                    tvgId = entry.tvgId,
                    kind = entry.kind,
                    seriesKey = entry.seriesKey,
                    season = entry.season,
                    episode = entry.episode,
                    position = position++,
                    catchupDays = entry.catchupDays,
                )
            )
            if (batch.size >= 500) {
                db.channelDao().insertAll(ArrayList(batch))
                batch.clear()
            }
        }
        if (batch.isNotEmpty()) db.channelDao().insertAll(batch)
        return position
    }

    suspend fun delete(playlistId: Long) = withContext(Dispatchers.IO) {
        db.channelDao().deleteForPlaylist(playlistId)
        db.epgDao().deleteForPlaylist(playlistId)
        db.xtreamSeriesDao().deleteForPlaylist(playlistId)
        db.playlistDao().delete(playlistId)
    }

    suspend fun setEpgUrl(playlistId: Long, epgUrl: String?) {
        val playlist = db.playlistDao().get(playlistId) ?: return
        db.playlistDao().update(
            playlist.copy(epgUrl = epgUrl?.trim()?.takeIf { it.isNotBlank() }, epgEtag = null, epgLastModified = null, epgLastRefreshedMs = 0)
        )
    }
}
