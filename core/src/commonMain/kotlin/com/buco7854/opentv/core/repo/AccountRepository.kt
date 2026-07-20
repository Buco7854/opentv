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
    private val mutex = Mutex()

    suspend fun accountInfo(playlist: Playlist, force: Boolean = false): AccountInfo? {
        val creds = playlist.credentials() ?: return null
        mutex.withLock {
            val cached = cache[playlist.id]
            val now = nowMs()
            if (!force && cached != null && now - cached.fetchedAtMs < CACHE_MS) return cached.info
            return try {
                val info = xtreamApi.fetchAccountInfo(creds)
                cache[playlist.id] = CachedInfo(info, now)
                info
            } catch (e: Exception) {
                e.rethrowCancellation()
                // Fall back to stale data, but still log the failure.
                log.log("Connection status (${playlist.name})", e)
                cached?.info
            }
        }
    }
}
