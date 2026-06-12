package com.buco7854.opentv.data.repo

import com.buco7854.opentv.data.db.AppDatabase
import com.buco7854.opentv.data.db.ProgrammeEntity
import com.buco7854.opentv.data.epg.XmltvParser
import com.buco7854.opentv.data.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class EpgRepository(private val db: AppDatabase) {

    companion object {
        const val MIN_REFRESH_INTERVAL_MS = 12L * 60 * 60 * 1000
        const val WINDOW_BACK_MS = 3L * 60 * 60 * 1000
        const val WINDOW_AHEAD_MS = 48L * 60 * 60 * 1000
    }

    private val refreshMutex = Mutex()

    /**
     * Download and ingest the XMLTV guide. Same frugality rules as playlists:
     * throttled, single-flight, conditional GET.
     */
    suspend fun refresh(playlistId: Long, force: Boolean = false): Unit = withContext(Dispatchers.IO) {
        refreshMutex.withLock {
            val playlist = db.playlistDao().get(playlistId) ?: return@withLock
            val epgUrl = playlist.epgUrl ?: return@withLock
            val now = System.currentTimeMillis()
            if (!force && now - playlist.epgLastRefreshedMs < MIN_REFRESH_INTERVAL_MS) return@withLock

            // Only live channels need guide data; everything else in the XMLTV file is skipped.
            val wantedIds = db.channelDao().distinctLiveTvgIds(playlistId).toHashSet()
            if (wantedIds.isEmpty()) {
                db.playlistDao().update(playlist.copy(epgLastRefreshedMs = now))
                return@withLock
            }

            when (val result = Http.conditionalGet(epgUrl, playlist.epgEtag, playlist.epgLastModified)) {
                is Http.FetchResult.NotModified -> {
                    db.playlistDao().update(playlist.copy(epgLastRefreshedMs = now))
                }
                is Http.FetchResult.Success -> result.response.use { response ->
                    db.epgDao().deleteForPlaylist(playlistId)
                    XmltvParser.parse(
                        input = Http.bodyStream(response, epgUrl),
                        playlistId = playlistId,
                        wantedChannelIds = wantedIds,
                        windowStartMs = now - WINDOW_BACK_MS,
                        windowEndMs = now + WINDOW_AHEAD_MS,
                    ) { batch -> runBlocking { db.epgDao().insertAll(batch) } }
                    db.playlistDao().update(
                        playlist.copy(
                            epgEtag = result.etag,
                            epgLastModified = result.lastModified,
                            epgLastRefreshedMs = now,
                        )
                    )
                }
            }
        }
    }

    suspend fun nowAiring(playlistId: Long): Map<String, ProgrammeEntity> = withContext(Dispatchers.IO) {
        db.epgDao().nowAiring(playlistId, System.currentTimeMillis()).associateBy { it.tvgId }
    }

    suspend fun upcoming(playlistId: Long, tvgId: String, limit: Int = 16): List<ProgrammeEntity> =
        withContext(Dispatchers.IO) {
            db.epgDao().upcoming(playlistId, tvgId, System.currentTimeMillis(), limit)
        }
}
