package com.buco7854.opentv.server

import java.util.concurrent.ConcurrentHashMap

/**
 * Enforces a provider's concurrent-stream cap for live playback in the backend, so it holds
 * even if a client ignores the UI's advice. Each player tab is one stream, keyed by its
 * session id; a live stream needs its own connection (only the remux shares one between
 * two viewers of the same file), so distinct tabs on a provider each count once.
 *
 * A stream is kept alive by its ongoing requests (playlist/segment fetches, or a periodic
 * touch during a continuous transport stream); one that goes quiet is reaped, freeing its
 * slot. A new stream is refused when the provider's other live streams already fill it -
 * refusing the newcomer rather than cutting off whoever is already watching.
 */
class StreamGate {

    private class Entry(@Volatile var providerKey: String, @Volatile var lastMs: Long)

    private val streams = ConcurrentHashMap<String, Entry>()
    private val lock = Any()

    /** Register or refresh stream [sid] on [providerKey]; false when the provider is full. */
    fun admit(sid: String, providerKey: String, limit: Int): Boolean = synchronized(lock) {
        val now = System.currentTimeMillis()
        streams.values.removeIf { now - it.lastMs > IDLE_MS }
        val existing = streams[sid]
        if (existing != null && existing.providerKey == providerKey) {
            existing.lastMs = now
            return true
        }
        // A new stream here (or this tab moving to another provider): only admit if the
        // provider's other live streams leave room. This tab's own entry never blocks it.
        val others = streams.count { (id, e) -> id != sid && e.providerKey == providerKey }
        if (others >= limit.coerceAtLeast(1)) return false
        streams[sid] = Entry(providerKey, now)
        return true
    }

    /** Keep a continuous stream's slot alive between its infrequent requests. */
    fun touch(sid: String) { streams[sid]?.lastMs = System.currentTimeMillis() }

    fun release(sid: String) { streams.remove(sid) }

    companion object {
        /** Reap a stream this long after its last request/touch (segments arrive far faster). */
        private const val IDLE_MS = 20_000L
    }
}
