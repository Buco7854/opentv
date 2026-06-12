package com.buco7854.opentv.data.repo

import android.content.ContentResolver
import android.net.Uri
import com.buco7854.opentv.data.db.AppDatabase
import com.buco7854.opentv.data.db.ChannelEntity
import com.buco7854.opentv.data.db.PlaylistEntity
import com.buco7854.opentv.data.m3u.M3uParser
import com.buco7854.opentv.data.net.Http
import com.buco7854.opentv.data.xtream.Xtream
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
            val url = playlist.url ?: return@withLock // local imports have nothing to refresh
            val now = System.currentTimeMillis()
            val lastAttempt = lastAttemptMs[playlistId] ?: 0L
            if (force) {
                if (now - lastAttempt < FORCED_MIN_INTERVAL_MS) return@withLock
            } else {
                if (now - playlist.lastRefreshedMs < MIN_REFRESH_INTERVAL_MS) return@withLock
                if (now - lastAttempt < FAILURE_RETRY_INTERVAL_MS) return@withLock
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
        db.playlistDao().delete(playlistId)
    }

    suspend fun setEpgUrl(playlistId: Long, epgUrl: String?) {
        val playlist = db.playlistDao().get(playlistId) ?: return
        db.playlistDao().update(
            playlist.copy(epgUrl = epgUrl?.trim()?.takeIf { it.isNotBlank() }, epgEtag = null, epgLastModified = null, epgLastRefreshedMs = 0)
        )
    }
}
