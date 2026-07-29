package com.buco7854.opentv.server

import com.buco7854.opentv.core.repo.AccountRepository
import com.buco7854.opentv.core.net.Urls
import com.buco7854.opentv.core.storage.Storage
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * How many concurrent reads a provider allows, answered fast enough to sit in front of
 * playback.
 *
 * The number comes from the panel's account API, which is a network request to the same
 * provider that is about to be asked for a stream. Waiting for it delays the first frame -
 * and it is asked for twice per start, once to reserve the intent and once to prepare the
 * remux. So: a known answer is reused for [LIMIT_TTL_MS], a slow one is abandoned after
 * [ACCOUNT_TIMEOUT_MS] in favour of the last known value, and a provider that keeps timing
 * out is not asked again for a while.
 *
 * Being briefly wrong here costs a stream admitted or refused against a stale budget, which
 * `ProviderConnections` still enforces; being slow here costs every viewer, every time.
 */
internal class ProviderConnectionLimits(
    private val storage: Storage,
    private val account: AccountRepository,
    private val fallback: Int,
    private val clock: ServerClock = ServerClock.SYSTEM,
) {
    private class Known(val limit: Int, val atMs: Long)

    private val known = ConcurrentHashMap<Long, Known>()
    private val slowUntilMs = ConcurrentHashMap<Long, Long>()
    private val log = LoggerFactory.getLogger(ProviderConnectionLimits::class.java)

    suspend fun forUrl(url: String): Int {
        if (!url.startsWith("http")) return Int.MAX_VALUE
        val playlist = storage.playlists.getAll().firstOrNull { candidate ->
            val base = candidate.xtreamBase
            val user = candidate.xtreamUser
            val pass = candidate.xtreamPass
            base != null && user != null && pass != null &&
                isXtreamStreamFor(url, base, user, pass)
        } ?: return fallback
        val now = clock.nowMs()
        known[playlist.id]?.takeIf { now - it.atMs < LIMIT_TTL_MS }?.let { return it.limit }
        val lastKnown = known[playlist.id]?.limit ?: fallback
        if (slowUntilMs[playlist.id]?.let { now < it } == true) return lastKnown

        val info = withTimeoutOrNull(ACCOUNT_TIMEOUT_MS) { account.accountInfo(playlist) }
        if (info == null) {
            // Either the panel is slow or it failed. Either way, stop asking for a while:
            // this runs on the path to the first frame.
            slowUntilMs[playlist.id] = now + SLOW_BACKOFF_MS
            log.debug("connection limit for playlist {}: falling back to {}", playlist.id, lastKnown)
            return lastKnown
        }
        val limit = info.maxConnections.takeIf { it > 0 } ?: fallback
        known[playlist.id] = Known(limit, now)
        slowUntilMs.remove(playlist.id)
        return limit
    }

    /**
     * Match both credentials as the exact path segments emitted by Xtream, on the exact provider
     * origin. A substring match makes `ann` claim `/live/joann/...`; ignoring the password makes
     * a stale login claim a replacement account. Both apply the wrong connection allowance.
     */
    private fun isXtreamStreamFor(url: String, base: String, user: String, pass: String): Boolean {
        val stream = Urls.parse(url) ?: return false
        val provider = Urls.parse(base) ?: return false
        if (stream.scheme != provider.scheme ||
            !stream.host.equals(provider.host, ignoreCase = true) ||
            stream.port != provider.port
        ) return false
        val basePath = provider.path.trimEnd('/')
        val encodedUser = Urls.encodePathSegment(user)
        val encodedPass = Urls.encodePathSegment(pass)
        return listOf("live", "movie", "series", "timeshift").any { kind ->
            stream.path.startsWith("$basePath/$kind/$encodedUser/$encodedPass/")
        }
    }

    private companion object {
        const val LIMIT_TTL_MS = 5 * 60_000L
        const val ACCOUNT_TIMEOUT_MS = 1_500L
        const val SLOW_BACKOFF_MS = 60_000L
    }
}
