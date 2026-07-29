package com.buco7854.opentv.server

import com.buco7854.opentv.contract.*
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import java.io.InputStream
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

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
    /** Whether ffmpeg is present, so the shared read can transcode audio to AAC for everyone. */
    private val ffmpegAvailable: () -> Boolean,
    private val processRunner: MediaProcessRunner = JvmMediaProcessRunner,
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
        val capabilities: MediaCapabilities,
    ) {
        // Each member (keyed by its session id) gets a bounded channel; a member that can't keep
        // up drops the oldest bytes and resyncs on the next transport-stream keyframe rather than
        // stalling the room. Keying by session id lets the server cut a kicked member's stream.
        private val members = ConcurrentHashMap<String, Channel<ByteArray>>()
        val lifecycle = Any()
        @Volatile private var reader: Job? = null
        @Volatile private var closer: Job? = null
        @Volatile private var upstream: InputStream? = null
        @Volatile private var probe: Process? = null
        @Volatile private var ffmpeg: Process? = null
        @Volatile private var dead = false
        // The audio codec to output: "copy" when every room member can decode the source, "aac"
        // otherwise. Probed once and reused across reconnects.
        @Volatile private var audioCodec: String? = null

        /** Attach [member], starting the single upstream read if this is the first member.
         *  REFUSED when the provider is full; DEAD when this relay has been retired and the
         *  caller should fetch a fresh one. */
        fun attach(sid: String, member: Channel<ByteArray>): Attach {
            synchronized(lifecycle) {
                if (dead) return Attach.DEAD
                closer?.cancel(); closer = null
                if (reader?.isActive != true) {
                    // First member of the room: claim the one shared connection or give up.
                    if (!connections.tryOpenStream(key, providerKey, group, limit, ::evicted)) {
                        return Attach.REFUSED
                    }
                    reader = scope.launch { pump() }
                }
                members.put(sid, member)?.cancel() // reconnect cuts buffered bytes from the old response
                return Attach.ATTACHED
            }
        }

        fun detach(sid: String, member: Channel<ByteArray>) {
            member.cancel()
            synchronized(lifecycle) {
                // Only clear the map entry if it's still this channel (not a newer reconnect).
                if (members[sid] === member) members.remove(sid)
                closeWhenIdle()
            }
        }

        /** Cut a member's stream (left or kicked); its response ends and the client falls back to
         *  solo. No-op if this relay doesn't hold that session. */
        fun drop(sid: String) {
            synchronized(lifecycle) {
                members.remove(sid)?.cancel()
                closeWhenIdle()
            }
        }

        // Always called under [lifecycle].
        private fun closeWhenIdle() {
            // Keep the upstream briefly so a channel hop or a quick reconnect reuses it.
            if (members.isEmpty() && closer == null && !dead) {
                closer = scope.launch {
                    delay(IDLE_KEEP_MS)
                    synchronized(lifecycle) { if (members.isEmpty()) stop() }
                }
            }
        }

        private fun evicted() {
            synchronized(lifecycle) { stop() }
        }

        // Always called under [lifecycle].
        fun stop() {
            if (dead) return
            dead = true
            reader?.cancel(); reader = null
            closer?.cancel(); closer = null
            runCatching { upstream?.close() } // unblock a read parked on the socket
            probe?.let(::terminate)
            ffmpeg?.let(::terminate)
            upstream = null
            probe = null
            ffmpeg = null
            relays.remove(key, this)
            connections.close(key)
            members.values.forEach { it.cancel() }
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
                        members.values.forEach { it.trySend(chunk) }
                    }
                } catch (e: Exception) {
                    // Providers drop long transfers; fall through and reconnect.
                } finally {
                    runCatching { stream.close() }
                    ffmpeg?.let(::terminate)
                    upstream = null
                    ffmpeg = null
                }
                delay(RECONNECT_MS)
            }
        }

        private fun open(): InputStream {
            // One ffmpeg reads the provider (the room's single connection), copies the video, and
            // transcodes the audio to AAC only when the room can't decode the source codec -
            // otherwise it's copied too. Remuxed back to a transport stream we tee; resent headers
            // let a member that joins mid-stream sync.
            if (ffmpegAvailable()) {
                val audio = audioCodec ?: probeAudioCodec().also { audioCodec = it }
                val cmd = mutableListOf("ffmpeg", "-nostdin", "-loglevel", "error")
                if (url.startsWith("http")) cmd += listOf(
                    "-user_agent", http.userAgent,
                    "-reconnect", "1", "-reconnect_streamed", "1", "-reconnect_delay_max", "10")
                // Probe and encode the same first audio stream. ffmpeg's automatic "best"
                // selection can otherwise choose a different, unsupported language/codec.
                cmd += listOf(
                    "-i", url,
                    "-map", "0:v:0?", "-map", "0:a:0?",
                    "-c:v", "copy", "-c:a", audio,
                )
                if (audio == "aac") cmd += listOf("-b:a", "192k")
                cmd += listOf("-f", "mpegts", "-mpegts_flags", "+resend_headers", "-flush_packets", "1", "pipe:1")
                val process = synchronized(lifecycle) {
                    if (dead) throw CancellationException("Relay was retired")
                    processRunner.start(
                        MediaProcessRequest(cmd, discardStderr = true)
                    ).also { ffmpeg = it }
                }
                return process.inputStream
            }
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

        /** "copy" when the source audio is room-decodable, "aac" when it isn't. Unknown is
         *  normalized to AAC: copying an unknown codec could publish a stream no room member can
         *  decode. */
        private fun probeAudioCodec(): String {
            val output = Files.createTempFile("opentv-relay-probe", ".txt")
            val cmd = mutableListOf("ffprobe", "-v", "error")
            if (url.startsWith("http")) cmd += listOf("-user_agent", http.userAgent)
            cmd += listOf("-select_streams", "a:0", "-show_entries", "stream=codec_name",
                "-of", "default=nokey=1:noprint_wrappers=1", url)
            var process: Process? = null
            return try {
                val started = synchronized(lifecycle) {
                    if (dead) throw CancellationException("Relay was retired")
                    processRunner.start(
                        MediaProcessRequest(cmd, stdoutFile = output, discardStderr = true)
                    ).also { probe = it }
                }
                process = started
                if (!started.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    terminate(started)
                    "aac"
                } else if (started.exitValue() != 0) {
                    "aac"
                } else {
                    val codec = Files.readString(output).trim().lowercase()
                    if (codec.isNotEmpty() && capabilities.audioDecodable(codec)) "copy" else "aac"
                }
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw error
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                "aac"
            } finally {
                process?.takeIf(Process::isAlive)?.let(::terminate)
                process?.let { completed ->
                    synchronized(lifecycle) {
                        if (probe === completed) probe = null
                    }
                }
                Files.deleteIfExists(output)
            }
        }

        private fun terminate(process: Process) {
            runCatching { process.destroyForcibly() }
            runCatching {
                if (!process.waitFor(PROCESS_EXIT_WAIT_SECONDS, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    process.waitFor(PROCESS_EXIT_WAIT_SECONDS, TimeUnit.SECONDS)
                }
            }
        }
    }

    /** Serve [url] to the room member [sid], sharing the room's single upstream read. */
    internal suspend fun stream(
        call: ApplicationCall,
        url: String,
        group: String,
        providerKey: String,
        limit: Int,
        sid: String,
        capabilities: MediaCapabilities,
        leaseGuard: () -> Unit,
    ) {
        val key = "$group|${capabilities.fingerprint}|$url"
        val member = Channel<ByteArray>(
            capacity = RELAY_QUEUE_CHUNKS,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        while (true) {
            val (relay, attached) = synchronized(relays) {
                // A capability/source change is a replacement physical pipeline, not another
                // connection that may hide behind the room's shared accounting key.
                relays.values.filter { it.group == group && it.key != key }.forEach { old ->
                    synchronized(old.lifecycle) { old.stop() }
                }
                val current = relays.computeIfAbsent(key) {
                    Relay(key, url, group, providerKey, limit, capabilities)
                }
                current to current.attach(sid, member)
            }
            when (attached) {
                Attach.REFUSED -> {
                    call.respond(
                        HttpStatusCode.TooManyRequests,
                        ApiErrorDto("provider_capacity", "Provider connection limit reached"),
                    )
                    return
                }
                Attach.DEAD -> continue // retired as we grabbed it; computeIfAbsent makes a fresh one
                Attach.ATTACHED -> {
                    try {
                        coroutineScope {
                            leaseGuard()
                            val heartbeat = launch {
                                while (isActive) {
                                    delay(STREAM_GUARD_INTERVAL_MS)
                                    leaseGuard()
                                }
                            }
                            try {
                                call.response.header(HttpHeaders.CacheControl, "no-store")
                                call.respondOutputStream(ContentType.parse("video/mp2t")) {
                                    withContext(Dispatchers.IO) {
                                        for (chunk in member) {
                                            leaseGuard()
                                            write(chunk)
                                        }
                                    }
                                }
                            } finally {
                                heartbeat.cancel()
                            }
                        }
                    } finally {
                        relay.detach(sid, member)
                    }
                    return
                }
            }
        }
    }

    /** Cut [sid]'s shared live stream if it's riding one - called when it leaves or is kicked from
     *  its room, so the server enforces the removal instead of trusting the client to disconnect. */
    fun drop(sid: String) {
        relays.values.forEach { it.drop(sid) }
    }

    /** Stop every relay and cancel the worker scope owned by this service. */
    fun close() {
        relays.values.forEach { relay ->
            synchronized(relay.lifecycle) { relay.stop() }
        }
        scope.cancel()
    }

    companion object {
        /** Keep an idle room's upstream this long, so a channel hop or reload reuses it. */
        private const val IDLE_KEEP_MS = 10_000L
        /** Back off this long before reopening a dropped live source. */
        private const val RECONNECT_MS = 500L
        /** 4 MiB per slow member at the 64 KiB pump size; enough to absorb jitter without
         *  retaining the previous 16 MiB while DROP_OLDEST waits to take effect. */
        private const val RELAY_QUEUE_CHUNKS = 64
        private const val STREAM_GUARD_INTERVAL_MS = 4_000L
        private const val PROBE_TIMEOUT_SECONDS = 15L
        private const val PROCESS_EXIT_WAIT_SECONDS = 3L
    }
}
