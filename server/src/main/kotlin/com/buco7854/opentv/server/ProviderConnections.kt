package com.buco7854.opentv.server

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** The URL authority groups a provider's connections; local files touch no provider. */
fun providerKeyOf(url: String): String =
    if (!url.startsWith("http")) "local"
    else runCatching { java.net.URI(url).authority ?: url }.getOrDefault(url)

/**
 * One shared budget of concurrent connections to a provider. Live playback, VOD remuxing and
 * downloads all draw on it, because an Xtream panel counts them together against its
 * max_connections - the panel can't tell a live stream from a movie, so neither do we.
 *
 * A connection is identified by its content ([shareKey]): two viewers of the same VOD share
 * one (the remux reads it once), while each live viewer needs its own. A new stream is refused
 * once the provider's distinct streams fill the cap - refusing the newcomer rather than cutting
 * off whoever is already watching - though it may still evict background downloads to make room.
 * A download only takes a free slot and never evicts. Evictions run outside the lock so an
 * evicted holder can take its own locks without deadlocking.
 */
class ProviderConnections(
    private val clock: ServerClock = ServerClock.SYSTEM,
) {

    enum class Kind { STREAM, DOWNLOAD }

    private class Holder(
        val id: String,
        val key: String,
        val shareKey: String,
        val kind: Kind,
        @Volatile var lastMs: Long,
        val evict: () -> Unit,
    )

    private val holders = ConcurrentHashMap<String, Holder>()
    private val lock = Any()
    private val onFreed = CopyOnWriteArrayList<() -> Unit>()

    /** Register work to retry when a slot frees (a download waiting for room). */
    fun onSlotFreed(listener: () -> Unit) { onFreed.add(listener) }

    /** Distinct streams reading from [key], ignoring content [exceptShareKey] (which a caller
     *  would share rather than add to). Used to decide whether a new read fits. */
    fun distinctStreams(key: String, exceptShareKey: String?): Int =
        holders.values.filter { it.key == key && it.kind == Kind.STREAM && it.shareKey != exceptShareKey }
            .map { it.shareKey }.distinct().size

    /** Evict least-recently-used downloads (under the lock) until content [shareKey] fits under
     *  [cap] distinct connections on [key], then register [holder]. Returns the evicted holders. */
    private fun reserve(id: String, key: String, shareKey: String, cap: Int, kind: Kind, evict: () -> Unit): List<Holder> {
        holders.remove(id)
        val victims = ArrayList<Holder>()
        while (true) {
            val current = holders.values.filter { it.key == key }
            if ((current.map { it.shareKey } + shareKey).distinct().size <= cap) break
            val lru = current.filter { it.kind == Kind.DOWNLOAD }.minByOrNull { it.lastMs } ?: break
            holders.remove(lru.id); victims.add(lru)
        }
        holders[id] = Holder(id, key, shareKey, kind, clock.nowMs(), evict)
        return victims
    }

    /**
     * Reserve a slot for an interactive stream [id] of content [shareKey] on provider [key],
     * evicting downloads to fit. Re-registering the same id just refreshes it. A stream never
     * evicts another stream, so this always succeeds - the caller [distinctStreams]-checks first
     * when it must refuse (VOD remux); live uses [tryOpenStream] to refuse instead.
     */
    fun openStream(id: String, key: String, shareKey: String, limit: Int, evict: () -> Unit) {
        val victims = synchronized(lock) { reserve(id, key, shareKey, limit.coerceAtLeast(1), Kind.STREAM, evict) }
        victims.forEach { runCatching { it.evict() } }
    }

    /** Reserve a stream slot, but refuse (false) when the provider's distinct streams already
     *  fill [limit] and this content isn't one of them - so a live stream can't cut a viewer. */
    fun tryOpenStream(id: String, key: String, shareKey: String, limit: Int, evict: () -> Unit): Boolean {
        val victims = synchronized(lock) {
            val cap = limit.coerceAtLeast(1)
            val streamKeys = holders.values.filter { it.key == key && it.kind == Kind.STREAM && it.id != id }
                .map { it.shareKey }
            val shared = shareKey in streamKeys
            if (!shared && streamKeys.distinct().size >= cap) return@synchronized null
            reserve(id, key, shareKey, cap, Kind.STREAM, evict)
        }
        victims ?: return false
        victims.forEach { runCatching { it.evict() } }
        return true
    }

    /** Reserve a slot for a background download only if the provider has room; never evicts. */
    fun tryOpenDownload(id: String, key: String, shareKey: String, limit: Int, evict: () -> Unit): Boolean =
        synchronized(lock) {
            val distinct = holders.values.filter { it.key == key }.map { it.shareKey }.distinct()
            if (shareKey in distinct || distinct.size < limit.coerceAtLeast(1)) {
                holders[id] = Holder(id, key, shareKey, Kind.DOWNLOAD, clock.nowMs(), evict)
                true
            } else false
        }

    /** Whether a fresh download could start on [key] right now (its distinct connections are below
     *  [limit]) - a peek for telling the user their download will wait, without reserving. */
    fun downloadFits(key: String, limit: Int): Boolean = synchronized(lock) {
        holders.values.filter { it.key == key }.map { it.shareKey }.distinct().size < limit.coerceAtLeast(1)
    }

    /** Keep a live connection from looking idle to the LRU eviction / stream reaper. */
    fun touch(id: String) { holders[id]?.lastMs = clock.nowMs() }

    /** True while [id] holds a live slot (used to tell an active stream from a prepared-but-idle one). */
    fun isOpen(id: String): Boolean = holders.containsKey(id)

    /** Release a slot; wakes anything waiting for the provider to free up. */
    fun close(id: String) {
        val freed = synchronized(lock) { holders.remove(id) != null }
        if (freed) onFreed.forEach { runCatching { it() } }
    }

    /** Release all reservations and ask their owners to stop. */
    fun closeAll() {
        val closing = synchronized(lock) {
            holders.values.toList().also { holders.clear() }
        }
        closing.forEach { runCatching { it.evict() } }
    }
}
