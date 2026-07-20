package com.buco7854.opentv.core.repo

import com.buco7854.opentv.core.model.ResumePoint
import com.buco7854.opentv.core.storage.ResumeStore
import com.buco7854.opentv.core.util.nowMs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Persists VOD playback positions. Saves are fire-and-forget on an internal
 * scope so they complete even while the player screen is being disposed.
 */
class ResumeRepository(private val store: ResumeStore) {

    companion object {
        /** Don't store trivially-short progress, or resume right before the end. */
        const val MIN_POSITION_MS = 10_000L
        const val END_GUARD_MS = 15_000L
        private const val MAX_AGE_MS = 90L * 24 * 60 * 60 * 1000
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Fraction watched (0..1) keyed by stream URL, for progress bars; finished items are pruned on save. */
    val progressByUrl: Flow<Map<String, Float>> = store.observeAll().map { points ->
        buildMap {
            for (p in points) {
                if (p.durationMs > 0 && p.positionMs >= MIN_POSITION_MS) {
                    put(p.url, (p.positionMs.toFloat() / p.durationMs).coerceIn(0f, 1f))
                }
            }
        }
    }

    suspend fun resumePositionFor(url: String): Long? {
        val point = store.get(url) ?: return null
        return point.positionMs.takeIf { it >= MIN_POSITION_MS }
    }

    /** Save (or clear, near the end) the position for [url]; no-op for live/unknown. */
    fun save(url: String, positionMs: Long, durationMs: Long) {
        scope.launch {
            if (durationMs <= 0 || positionMs < MIN_POSITION_MS ||
                positionMs > durationMs - END_GUARD_MS
            ) {
                store.delete(url)
            } else {
                store.upsert(ResumePoint(url, positionMs, durationMs, nowMs()))
            }
        }
    }

    fun clear(url: String) {
        scope.launch { store.delete(url) }
    }

    /** Wipes all saved progress for a playlist's channels. */
    suspend fun clearForPlaylist(playlistId: Long) = store.deleteForPlaylist(playlistId)

    fun pruneOld() {
        scope.launch { store.prune(nowMs() - MAX_AGE_MS) }
    }
}
