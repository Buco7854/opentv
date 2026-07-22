package com.buco7854.opentv.server

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** The URL authority groups a provider's connections; local files touch no provider. */
fun providerKeyOf(url: String): String =
    if (!url.startsWith("http")) "local"
    else runCatching { java.net.URI(url).authority ?: url }.getOrDefault(url)

/**
 * Shared budget of concurrent connections to one provider, since downloads and playback
 * draw from the same pool (an Xtream panel counts them together against max_connections).
 *
 * An interactive stream may evict the least-recently-used connection of that provider - a
 * stale stream or a background download - to fit within the cap; a download only takes a
 * free slot and never evicts. So a viewer always gets in (bumping a download if need be),
 * a one-connection provider never has two reads at once, and one that allows more lets
 * that many run together across both features.
 */
class ProviderConnections {

    enum class Kind { STREAM, DOWNLOAD }

    private class Holder(
        val id: String,
        val key: String,
        val kind: Kind,
        @Volatile var lastMs: Long,
        val evict: () -> Unit,
    )

    private val holders = ConcurrentHashMap<String, Holder>()
    private val lock = Any()
    private val onFreed = CopyOnWriteArrayList<() -> Unit>()

    /** Register work to retry when a slot frees (a download waiting for room). */
    fun onSlotFreed(listener: () -> Unit) { onFreed.add(listener) }

    private fun peers(key: String, exceptId: String) =
        holders.values.filter { it.key == key && it.id != exceptId }

    /**
     * Reserve a slot for an interactive stream [id] on provider [key], evicting the
     * least-recently-used connection(s) there until it fits under [limit]. Re-registering
     * the same id just refreshes it (no self-eviction). Evictions run outside the lock so
     * an evicted holder can take its own locks without deadlocking.
     */
    fun openStream(id: String, key: String, limit: Int, evict: () -> Unit) {
        val victims = synchronized(lock) {
            holders.remove(id)
            val out = ArrayList<Holder>()
            val live = peers(key, id).toMutableList()
            while (live.size >= limit.coerceAtLeast(1)) {
                val lru = live.minByOrNull { it.lastMs } ?: break
                live.remove(lru); holders.remove(lru.id); out.add(lru)
            }
            holders[id] = Holder(id, key, Kind.STREAM, System.currentTimeMillis(), evict)
            out
        }
        victims.forEach { runCatching { it.evict() } }
    }

    /** Reserve a slot for a background download only if the provider has room. */
    fun tryOpenDownload(id: String, key: String, limit: Int, evict: () -> Unit): Boolean =
        synchronized(lock) {
            if (peers(key, id).size >= limit.coerceAtLeast(1)) false
            else {
                holders[id] = Holder(id, key, Kind.DOWNLOAD, System.currentTimeMillis(), evict)
                true
            }
        }

    /** Keep a live connection from looking idle to the LRU eviction. */
    fun touch(id: String) { holders[id]?.lastMs = System.currentTimeMillis() }

    /** True while [id] holds a live slot (used to tell an active stream from a prepared-but-idle one). */
    fun isOpen(id: String): Boolean = holders.containsKey(id)

    /** Release a slot; wakes anything waiting for the provider to free up. */
    fun close(id: String) {
        val freed = synchronized(lock) { holders.remove(id) != null }
        if (freed) onFreed.forEach { runCatching { it() } }
    }
}
