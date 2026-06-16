package com.buco7854.opentv.data.repo

import com.buco7854.opentv.data.db.AppDatabase
import com.buco7854.opentv.data.db.ProgrammeEntity
import com.buco7854.opentv.data.epg.XmltvParser
import com.buco7854.opentv.data.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class EpgRepository(private val db: AppDatabase) {

    companion object {
        const val MIN_REFRESH_INTERVAL_MS = 12L * 60 * 60 * 1000
        const val FAILURE_RETRY_INTERVAL_MS = 5L * 60 * 1000
        const val FORCED_MIN_INTERVAL_MS = 30_000L
        /** Keep over a week of past guide data so catch-up has programmes to
         *  offer across a typical archive (XMLTV path; Xtream uses the live API). */
        const val WINDOW_BACK_MS = 8L * 24 * 60 * 60 * 1000
        const val WINDOW_AHEAD_MS = 48L * 60 * 60 * 1000
    }

    private val refreshMutex = Mutex()
    private val lastAttemptMs = HashMap<Long, Long>()

    /**
     * Download and ingest the XMLTV guide. Same frugality rules as playlists:
     * throttled, single-flight, conditional GET.
     */
    suspend fun refresh(playlistId: Long, force: Boolean = false): Unit = withContext(Dispatchers.IO) {
        refreshMutex.withLock {
            val playlist = db.playlistDao().get(playlistId) ?: return@withLock
            val epgUrl = playlist.epgUrl ?: return@withLock
            val now = System.currentTimeMillis()
            val lastAttempt = lastAttemptMs[playlistId] ?: 0L
            if (force) {
                if (now - lastAttempt < FORCED_MIN_INTERVAL_MS) return@withLock
            } else {
                if (now - playlist.epgLastRefreshedMs < MIN_REFRESH_INTERVAL_MS) return@withLock
                if (now - lastAttempt < FAILURE_RETRY_INTERVAL_MS) return@withLock
            }
            lastAttemptMs[playlistId] = now

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
                    // Same 304-trap guard as playlists: invalidate validators
                    // before wiping so a failed ingest forces a re-download.
                    db.playlistDao().update(playlist.copy(epgEtag = null, epgLastModified = null))
                    db.epgDao().deleteForPlaylist(playlistId)
                    XmltvParser.parse(
                        input = Http.bodyStream(response),
                        playlistId = playlistId,
                        wantedChannelIds = wantedIds,
                        windowStartMs = now - WINDOW_BACK_MS,
                        windowEndMs = now + WINDOW_AHEAD_MS,
                    ) { batch -> db.epgDao().insertAll(batch) }
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
            db.epgDao().guideSince(playlistId, tvgId, System.currentTimeMillis(), limit)
        }

    /** Guide including past programmes (for catch-up), from [sinceMs] onwards. */
    suspend fun guide(playlistId: Long, tvgId: String, sinceMs: Long, limit: Int = 300): List<ProgrammeEntity> =
        withContext(Dispatchers.IO) {
            db.epgDao().guideSince(playlistId, tvgId, sinceMs, limit)
        }
}
