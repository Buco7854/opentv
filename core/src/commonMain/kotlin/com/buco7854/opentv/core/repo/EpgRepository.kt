package com.buco7854.opentv.core.repo

import com.buco7854.opentv.core.epg.XmltvParser
import com.buco7854.opentv.core.model.Programme
import com.buco7854.opentv.core.net.ConditionalFetch
import com.buco7854.opentv.core.net.ConditionalFetcher
import com.buco7854.opentv.core.storage.Storage
import com.buco7854.opentv.core.util.nowMs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class EpgRepository(
    private val storage: Storage,
    private val fetcher: ConditionalFetcher,
) {

    companion object {
        const val MIN_REFRESH_INTERVAL_MS = 12L * 60 * 60 * 1000
        const val FAILURE_RETRY_INTERVAL_MS = 5L * 60 * 1000
        const val FORCED_MIN_INTERVAL_MS = 30_000L
        /** Keep >1 week of past guide data so catch-up has programmes to offer. */
        const val WINDOW_BACK_MS = 8L * 24 * 60 * 60 * 1000
        const val WINDOW_AHEAD_MS = 48L * 60 * 60 * 1000
    }

    private val refreshMutex = Mutex()
    private val lastAttemptMs = HashMap<Long, Long>()

    /** Download and ingest the XMLTV guide: throttled, single-flight, conditional GET. */
    suspend fun refresh(playlistId: Long, force: Boolean = false) {
        refreshMutex.withLock {
            val playlist = storage.playlists.get(playlistId) ?: return
            val epgUrl = playlist.epgUrl ?: return
            val now = nowMs()
            val lastAttempt = lastAttemptMs[playlistId] ?: 0L
            if (force) {
                if (now - lastAttempt < FORCED_MIN_INTERVAL_MS) return
            } else {
                if (now - playlist.epgLastRefreshedMs < MIN_REFRESH_INTERVAL_MS) return
                if (now - lastAttempt < FAILURE_RETRY_INTERVAL_MS) return
            }
            lastAttemptMs[playlistId] = now

            // Only live channels need guide data.
            val wantedIds = storage.channels.distinctLiveTvgIds(playlistId).toHashSet()
            if (wantedIds.isEmpty()) {
                storage.playlists.update(playlist.copy(epgLastRefreshedMs = now))
                return
            }

            when (val result = fetcher.conditionalGet(epgUrl, playlist.epgEtag, playlist.epgLastModified)) {
                is ConditionalFetch.NotModified -> {
                    storage.playlists.update(playlist.copy(epgLastRefreshedMs = now))
                }
                is ConditionalFetch.Success -> try {
                    // Invalidate validators before mutating so a failed ingest forces a re-download.
                    storage.playlists.update(playlist.copy(epgEtag = null, epgLastModified = null))
                    // Replace future rows; keep past rows so catch-up history survives short files.
                    storage.epg.deleteFrom(playlistId, now)
                    XmltvParser.parse(
                        source = result.body.chars(),
                        wantedChannelIds = wantedIds,
                        windowStartMs = now - WINDOW_BACK_MS,
                        windowEndMs = now + WINDOW_AHEAD_MS,
                    ) { batch ->
                        storage.epg.insertAll(
                            batch.map {
                                Programme(
                                    playlistId = playlistId,
                                    tvgId = it.channel,
                                    title = it.title,
                                    description = it.description,
                                    startMs = it.startMs,
                                    endMs = it.endMs,
                                )
                            }
                        )
                    }
                    storage.epg.prune(playlistId, now - WINDOW_BACK_MS)
                    storage.playlists.update(
                        playlist.copy(
                            epgEtag = result.etag,
                            epgLastModified = result.lastModified,
                            epgLastRefreshedMs = now,
                        )
                    )
                } finally {
                    result.body.close()
                }
            }
        }
    }

    suspend fun nowAiring(playlistId: Long): Map<String, Programme> =
        storage.epg.nowAiring(playlistId, nowMs()).associateBy { it.tvgId }

    suspend fun upcoming(playlistId: Long, tvgId: String, limit: Int = 16): List<Programme> =
        storage.epg.guideSince(playlistId, tvgId, nowMs(), limit)

    /** Guide including past programmes (for catch-up), from [sinceMs] onwards. */
    suspend fun guide(playlistId: Long, tvgId: String, sinceMs: Long, limit: Int = 300): List<Programme> =
        storage.epg.guideSince(playlistId, tvgId, sinceMs, limit)

    /** tvg ids with programme data (Channel.hasGuide); flows as ingests land. */
    fun observeGuideIds(playlistId: Long): Flow<Set<String>> =
        storage.epg.observeGuideIds(playlistId).map { it.toSet() }
}
