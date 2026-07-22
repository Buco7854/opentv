package com.buco7854.opentv.server

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Admits live streams into the shared [ProviderConnections] budget, so the backend enforces a
 * provider's concurrent-stream cap even if a client ignores the UI. Each player tab is one live
 * stream keyed by its session id (its own connection - only the remux shares one between two
 * viewers of the same file). A stream is kept alive by its ongoing requests, or a touch during
 * a continuous transport stream; one that goes quiet is reaped, freeing its slot. A new stream
 * is refused when the provider's other streams (live or remuxed VOD) already fill it - refusing
 * the newcomer rather than cutting off whoever is already watching.
 */
class StreamGate(private val connections: ProviderConnections) {

    private val lastSeen = ConcurrentHashMap<String, Long>()

    init {
        Thread {
            while (true) {
                runCatching { TimeUnit.SECONDS.sleep(5) }
                val cutoff = System.currentTimeMillis() - IDLE_MS
                lastSeen.filterValues { it < cutoff }.keys.forEach { release(it) }
            }
        }.apply { isDaemon = true }.start()
    }

    /** Register or refresh live stream [sid] on [providerKey]; false when the provider is full. */
    fun admit(sid: String, providerKey: String, limit: Int): Boolean {
        if (lastSeen.containsKey(sid) && connections.isOpen(sid)) {
            lastSeen[sid] = System.currentTimeMillis()
            connections.touch(sid)
            return true
        }
        // A live viewer's connection is its own (share key = the session id).
        if (!connections.tryOpenStream(sid, providerKey, sid, limit) { release(sid) }) return false
        lastSeen[sid] = System.currentTimeMillis()
        return true
    }

    /** Keep a continuous stream's slot alive between its infrequent requests. */
    fun touch(sid: String) {
        if (lastSeen.containsKey(sid)) { lastSeen[sid] = System.currentTimeMillis(); connections.touch(sid) }
    }

    fun release(sid: String) {
        lastSeen.remove(sid)
        connections.close(sid)
    }

    companion object {
        /** Reap a stream this long after its last request/touch (segments arrive far faster). */
        private const val IDLE_MS = 20_000L
    }
}
