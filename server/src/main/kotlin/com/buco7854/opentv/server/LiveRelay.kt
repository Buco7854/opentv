package com.buco7854.opentv.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import java.io.InputStream
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Shares one upstream live connection across a watch-together room. The channel is read from the
 * provider exactly once - one connection, one seat, keyed by the room's share group - and its
 * bytes are fanned out to every member, so N people watching a channel together still cost the
 * provider a single stream. Solo viewers keep their own connection through [StreamProxy]; only
 * room members are routed here. A transport stream can be joined mid-flow, so a member who joins
 * late simply starts receiving from the live edge.
 */
class LiveRelay(
    private val http: ServerHttp,
    private val connections: ProviderConnections,
) {
    private enum class Attach { ATTACHED, REFUSED, DEAD }

    private val relays = ConcurrentHashMap<String, Relay>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private inner class Relay(
        val key: String,
        val url: String,
        val group: String,
        val providerKey: String,
        val limit: Int,
    ) {
        // Each member gets a bounded channel; a member that can't keep up drops the oldest bytes
        // and resyncs on the next transport-stream keyframe rather than stalling the whole room.
        private val members = CopyOnWriteArrayList<Channel<ByteArray>>()
        private val lifecycle = Any()
        @Volatile private var reader: Job? = null
        @Volatile private var closer: Job? = null
        @Volatile private var upstream: InputStream? = null
        @Volatile private var dead = false

        /** Attach [member], starting the single upstream read if this is the first member.
         *  REFUSED when the provider is full; DEAD when this relay has been retired and the
         *  caller should fetch a fresh one. */
        fun attach(member: Channel<ByteArray>): Attach {
            synchronized(lifecycle) {
                if (dead) return Attach.DEAD
                closer?.cancel(); closer = null
                if (reader?.isActive != true) {
                    // First member of the room: claim the one shared connection or give up.
                    if (!connections.tryOpenStream(key, providerKey, group, limit) { stop() }) {
                        return Attach.REFUSED
                    }
                    reader = scope.launch { pump() }
                }
                members.add(member)
                return Attach.ATTACHED
            }
        }

        fun detach(member: Channel<ByteArray>) {
            member.close()
            synchronized(lifecycle) {
                members.remove(member)
                // Keep the upstream briefly so a channel hop or a quick reconnect reuses it.
                if (members.isEmpty() && closer == null && !dead) {
                    closer = scope.launch {
                        delay(IDLE_KEEP_MS)
                        synchronized(lifecycle) { if (members.isEmpty()) stop() }
                    }
                }
            }
        }

        // Always called under [lifecycle].
        private fun stop() {
            if (dead) return
            dead = true
            reader?.cancel(); reader = null
            closer?.cancel(); closer = null
            runCatching { upstream?.close() } // unblock a read parked on the socket
            upstream = null
            relays.remove(key, this)
            connections.close(key)
            members.forEach { it.close() }
            members.clear()
        }

        private suspend fun pump() {
            val buffer = ByteArray(64 * 1024)
            while (currentCoroutineContext().isActive) {
                val stream = try {
                    open()
                } catch (e: Exception) {
                    delay(RECONNECT_MS)
                    continue
                }
                upstream = stream
                try {
                    while (currentCoroutineContext().isActive) {
                        val n = stream.read(buffer)
                        if (n < 0) break
                        if (n == 0) continue
                        connections.touch(key)
                        val chunk = buffer.copyOf(n)
                        members.forEach { it.trySend(chunk) }
                    }
                } catch (e: Exception) {
                    // Providers drop long transfers; fall through and reconnect.
                } finally {
                    runCatching { stream.close() }
                    upstream = null
                }
                delay(RECONNECT_MS)
            }
        }

        private fun open(): InputStream {
            val request = HttpRequest.newBuilder(URI(url))
                .timeout(java.time.Duration.ofSeconds(30))
                .header("User-Agent", http.userAgent)
                .build()
            val response = http.client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            if (response.statusCode() !in 200..299) {
                response.body().close()
                throw IllegalStateException("upstream HTTP ${response.statusCode()}")
            }
            return response.body()
        }
    }

    /** Serve [url] to one room member, sharing the room's single upstream read. */
    suspend fun stream(call: ApplicationCall, url: String, group: String, providerKey: String, limit: Int) {
        val key = "$group|$url"
        val member = Channel<ByteArray>(capacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        while (true) {
            val relay = relays.computeIfAbsent(key) { Relay(key, url, group, providerKey, limit) }
            when (relay.attach(member)) {
                Attach.REFUSED -> {
                    call.respond(HttpStatusCode.TooManyRequests, MessageDto("Provider connection limit reached"))
                    return
                }
                Attach.DEAD -> continue // retired as we grabbed it; computeIfAbsent makes a fresh one
                Attach.ATTACHED -> {
                    try {
                        call.response.header(HttpHeaders.CacheControl, "no-store")
                        call.respondOutputStream(ContentType.parse("video/mp2t")) {
                            for (chunk in member) {
                                write(chunk)
                                flush()
                            }
                        }
                    } finally {
                        relay.detach(member)
                    }
                    return
                }
            }
        }
    }

    companion object {
        /** Keep an idle room's upstream this long, so a channel hop or reload reuses it. */
        private const val IDLE_KEEP_MS = 10_000L
        /** Back off this long before reopening a dropped live source. */
        private const val RECONNECT_MS = 500L
    }
}
