package com.buco7854.opentv.data.repo

import com.buco7854.opentv.data.db.PlaylistEntity
import com.buco7854.opentv.data.xtream.AccountInfo
import com.buco7854.opentv.data.xtream.Xtream
import com.buco7854.opentv.data.xtream.XtreamCredentials
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Connection monitoring (active vs. max concurrent connections) via the
 * provider's Xtream API. Results are cached for [CACHE_MS] so a busy UI never
 * hammers the panel - at most one request per minute per playlist.
 */
class AccountRepository {

    companion object {
        const val CACHE_MS = 60_000L
    }

    private class CachedInfo(val info: AccountInfo, val fetchedAtMs: Long)

    private val cache = HashMap<Long, CachedInfo>()
    private val mutex = Mutex()

    fun credentialsFor(playlist: PlaylistEntity): XtreamCredentials? {
        val base = playlist.xtreamBase ?: return null
        val user = playlist.xtreamUser ?: return null
        val pass = playlist.xtreamPass ?: return null
        return XtreamCredentials(base, user, pass)
    }

    suspend fun accountInfo(playlist: PlaylistEntity, force: Boolean = false): AccountInfo? {
        val creds = credentialsFor(playlist) ?: return null
        mutex.withLock {
            val cached = cache[playlist.id]
            val now = System.currentTimeMillis()
            if (!force && cached != null && now - cached.fetchedAtMs < CACHE_MS) return cached.info
            return try {
                val info = Xtream.fetchAccountInfo(creds)
                cache[playlist.id] = CachedInfo(info, now)
                info
            } catch (_: Exception) {
                cached?.info
            }
        }
    }
}
