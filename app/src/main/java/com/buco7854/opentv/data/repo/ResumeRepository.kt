package com.buco7854.opentv.data.repo

import com.buco7854.opentv.data.db.AppDatabase
import com.buco7854.opentv.data.db.ResumePointEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Persists VOD playback positions so progress survives the process being
 * killed. Saves are fire-and-forget on an internal scope (they must complete
 * even as the player screen is being disposed); reads are suspend.
 */
class ResumeRepository(private val db: AppDatabase) {

    companion object {
        /** Don't store trivially-short progress, or resume right before the end. */
        const val MIN_POSITION_MS = 10_000L
        const val END_GUARD_MS = 15_000L
        private const val MAX_AGE_MS = 90L * 24 * 60 * 60 * 1000
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Live fraction watched (0..1) keyed by stream URL, for progress bars on
     * movie/episode pages and episode lists. Finished items are pruned on save,
     * so only genuinely in-progress titles appear here.
     */
    val progressByUrl: Flow<Map<String, Float>> = db.resumeDao().observeAll().map { points ->
        buildMap {
            for (p in points) {
                if (p.durationMs > 0 && p.positionMs >= MIN_POSITION_MS) {
                    put(p.url, (p.positionMs.toFloat() / p.durationMs).coerceIn(0f, 1f))
                }
            }
        }
    }

    suspend fun resumePositionFor(url: String): Long? {
        val point = db.resumeDao().get(url) ?: return null
        return point.positionMs.takeIf { it >= MIN_POSITION_MS }
    }

    /** Save (or clear, near the end) the position for [url]; no-op for live/unknown. */
    fun save(url: String, positionMs: Long, durationMs: Long) {
        scope.launch {
            if (durationMs <= 0 || positionMs < MIN_POSITION_MS ||
                positionMs > durationMs - END_GUARD_MS
            ) {
                db.resumeDao().delete(url)
            } else {
                db.resumeDao().upsert(
                    ResumePointEntity(url, positionMs, durationMs, System.currentTimeMillis())
                )
            }
        }
    }

    fun clear(url: String) {
        scope.launch { db.resumeDao().delete(url) }
    }

    fun pruneOld() {
        scope.launch { db.resumeDao().prune(System.currentTimeMillis() - MAX_AGE_MS) }
    }
}
