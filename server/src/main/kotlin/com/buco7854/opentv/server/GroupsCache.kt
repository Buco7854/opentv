package com.buco7854.opentv.server

import com.buco7854.opentv.contract.GroupCountDto
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Caches each playlist/kind's category list and refreshes it on a fixed interval, so a
 * client that already saw a category list gets it back instantly instead of waiting on
 * a fresh read every time. A key is loaded synchronously the first time it is requested,
 * then kept warm by the periodic refresh; a refresh that fails leaves the last known-good
 * value in place rather than clearing it.
 */
class GroupsCache(
    scope: CoroutineScope,
    private val refreshIntervalMs: Long = DEFAULT_REFRESH_INTERVAL_MS,
    private val load: suspend (playlistId: Long, kind: Int) -> List<GroupCountDto>,
) {
    private data class Key(val playlistId: Long, val kind: Int)

    private val entries = ConcurrentHashMap<Key, List<GroupCountDto>>()

    init {
        scope.launch {
            while (true) {
                delay(refreshIntervalMs)
                refreshAll()
            }
        }
    }

    suspend fun get(playlistId: Long, kind: Int): List<GroupCountDto> {
        val key = Key(playlistId, kind)
        entries[key]?.let { return it }
        return load(playlistId, kind).also { entries[key] = it }
    }

    /** Drops a playlist's cached lists so its next request reloads them from scratch. */
    fun invalidate(playlistId: Long) {
        entries.keys.removeAll { it.playlistId == playlistId }
    }

    private suspend fun refreshAll() {
        entries.keys.toList().forEach { key ->
            runCatching { load(key.playlistId, key.kind) }
                .onSuccess { entries[key] = it }
        }
    }

    private companion object {
        const val DEFAULT_REFRESH_INTERVAL_MS = 30_000L
    }
}
