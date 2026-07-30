package com.buco7854.opentv.core.repo

import com.buco7854.opentv.core.log.CoreLog
import com.buco7854.opentv.core.log.rethrowCancellation
import com.buco7854.opentv.core.model.Playlist
import com.buco7854.opentv.core.util.nowMs
import com.buco7854.opentv.core.xtream.AccountInfo
import com.buco7854.opentv.core.xtream.XtreamApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface AccountInfoResult {
    data class Fresh(val info: AccountInfo, val fetchedAtMs: Long) : AccountInfoResult
    data class Stale(val info: AccountInfo, val fetchedAtMs: Long) : AccountInfoResult
    data class Unavailable(val cause: Throwable? = null) : AccountInfoResult
}

/** Xtream connection monitoring; cached for [CACHE_MS] (one request/min/playlist). */
class AccountRepository(
    private val xtreamApi: XtreamApi,
    private val log: CoreLog,
    private val clock: () -> Long = ::nowMs,
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
    suspend fun accountInfo(playlist: Playlist, force: Boolean = false): AccountInfoResult {
        val creds = playlist.credentials() ?: return AccountInfoResult.Unavailable()
        fresh(playlist.id, force)?.let {
            return AccountInfoResult.Fresh(it.info, it.fetchedAtMs)
        }
        val fetch = state.withLock { fetches.getOrPut(playlist.id) { Mutex() } }
        val stale = state.withLock { cache[playlist.id] }
        if (!fetch.tryLock()) {
            return stale?.let { AccountInfoResult.Stale(it.info, it.fetchedAtMs) }
                ?: AccountInfoResult.Unavailable()
        }
        try {
            fresh(playlist.id, force)?.let {
                return AccountInfoResult.Fresh(it.info, it.fetchedAtMs)
            }
            return try {
                val info = xtreamApi.fetchAccountInfo(creds)
                val fetchedAtMs = clock()
                state.withLock { cache[playlist.id] = CachedInfo(info, fetchedAtMs) }
                AccountInfoResult.Fresh(info, fetchedAtMs)
            } catch (e: Exception) {
                e.rethrowCancellation()
                log.log("Connection status (${playlist.name})", e)
                stale?.let { AccountInfoResult.Stale(it.info, it.fetchedAtMs) }
                    ?: AccountInfoResult.Unavailable(e)
            }
        } finally {
            fetch.unlock()
        }
    }

    private suspend fun fresh(playlistId: Long, force: Boolean): CachedInfo? {
        if (force) return null
        return state.withLock {
            cache[playlistId]?.takeIf { clock() - it.fetchedAtMs < CACHE_MS }
        }
    }
}
