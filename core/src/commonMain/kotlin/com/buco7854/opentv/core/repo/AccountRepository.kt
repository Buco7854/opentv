package com.buco7854.opentv.core.repo

import com.buco7854.opentv.core.log.CoreLog
import com.buco7854.opentv.core.log.rethrowCancellation
import com.buco7854.opentv.core.model.Playlist
import com.buco7854.opentv.core.util.nowMs
import com.buco7854.opentv.core.xtream.AccountInfo
import com.buco7854.opentv.core.xtream.XtreamApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Xtream connection monitoring; cached for [CACHE_MS] (one request/min/playlist). */
class AccountRepository(
    private val xtreamApi: XtreamApi,
    private val log: CoreLog,
) {
    companion object {
        const val CACHE_MS = 60_000L
    }

    private class CachedInfo(val info: AccountInfo, val fetchedAtMs: Long)

    private val cache = HashMap<Long, CachedInfo>()
    /** Guards [cache] and [fetches] only - never held across a provider request. */
    private val state = Mutex()
    /** One refresh per playlist at a time, so unrelated playlists never queue behind it. */
    private val fetches = HashMap<Long, Mutex>()

    suspend fun invalidate(playlistId: Long) {
        state.withLock { cache.remove(playlistId) }
    }

    /**
     * Playback asks for this on its critical path, so a caller never waits on somebody
     * else's provider request: if this playlist is already being refreshed, the last known
     * answer is returned instead of queueing behind it.
     */
    suspend fun accountInfo(playlist: Playlist, force: Boolean = false): AccountInfo? {
        val creds = playlist.credentials() ?: return null
        fresh(playlist.id, force)?.let { return it }
        val fetch = state.withLock { fetches.getOrPut(playlist.id) { Mutex() } }
        val stale = state.withLock { cache[playlist.id]?.info }
        if (!fetch.tryLock()) return stale
        try {
            fresh(playlist.id, force)?.let { return it }
            return try {
                val info = xtreamApi.fetchAccountInfo(creds)
                state.withLock { cache[playlist.id] = CachedInfo(info, nowMs()) }
                info
            } catch (e: Exception) {
                e.rethrowCancellation()
                // Fall back to stale data, but still log the failure.
                log.log("Connection status (${playlist.name})", e)
                stale
            }
        } finally {
            fetch.unlock()
        }
    }

    private suspend fun fresh(playlistId: Long, force: Boolean): AccountInfo? {
        if (force) return null
        return state.withLock {
            cache[playlistId]?.takeIf { nowMs() - it.fetchedAtMs < CACHE_MS }?.info
        }
    }
}
