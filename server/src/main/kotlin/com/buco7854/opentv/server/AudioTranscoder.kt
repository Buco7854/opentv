package com.buco7854.opentv.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Copies video and remuxes to MPEG-TS, re-encoding audio to AAC only when the
 * lease's effective capabilities cannot decode it. One process per viewer.
 */
class AudioTranscoder(
    private val http: ServerHttp,
    private val processRunner: MediaProcessRunner = JvmMediaProcessRunner,
) {
    private data class Active(val token: Any, val process: Process?)

    private val active = HashMap<String, Active>()
    private val lifecycle = Any()

    internal suspend fun stream(
        url: String,
        call: ApplicationCall,
        sid: String,
        capabilities: MediaCapabilities,
        leaseGuard: () -> Unit,
    ) = coroutineScope {
        val token = Any()
        val previous = synchronized(lifecycle) {
            active.put(sid, Active(token, null))?.process
        }
        withContext(NonCancellable + Dispatchers.IO) {
            previous?.let(::terminate)
        }
        leaseGuard()
        var process: Process? = null
        try {
            val audioCodec = probeAudioCodec(url, capabilities, sid, token)
            ensureCurrent(sid, token)
            leaseGuard()
            val command = mutableListOf("ffmpeg", "-nostdin", "-loglevel", "error")
            // Providers drop long-lived transfers; reconnect in place like the remux.
            if (url.startsWith("http")) {
                command += listOf(
                    "-user_agent", http.userAgent,
                    "-reconnect", "1", "-reconnect_streamed", "1", "-reconnect_delay_max", "10",
                )
            }
            command += listOf(
                "-i", url,
                "-map", "0:v:0?", "-map", "0:a:0?",
                "-c:v", "copy",
                "-c:a", audioCodec,
            )
            if (audioCodec == "aac") command += listOf("-ac", "2", "-b:a", "128k")
            command += listOf("-f", "mpegts", "-")

            // Capture the child in the outer finally before returning to the cancellable caller.
            // Otherwise cancellation at the IO dispatcher hand-off can discard a started process
            // before it is registered anywhere.
            withContext(NonCancellable + Dispatchers.IO) {
                val started = processRunner.start(MediaProcessRequest(command, discardStderr = true))
                process = started
                if (!register(sid, token, started)) {
                    terminate(started)
                    throw CancellationException("Transcode was superseded")
                }
            }
            ensureCurrent(sid, token)
            leaseGuard()
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respondOutputStream(ContentType.parse("video/mp2t")) {
                coroutineScope {
                    val heartbeat = launch {
                        while (isActive) {
                            delay(STREAM_GUARD_INTERVAL_MS)
                            try {
                                ensureCurrent(sid, token)
                                leaseGuard()
                            } catch (error: Throwable) {
                                withContext(NonCancellable + Dispatchers.IO) {
                                    process?.let(::terminate)
                                }
                                throw error
                            }
                        }
                    }
                    try {
                        withContext(Dispatchers.IO) {
                            val buffer = ByteArray(STREAM_COPY_BUFFER_BYTES)
                            val input = requireNotNull(process).inputStream
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (count == 0) continue
                                ensureCurrent(sid, token)
                                leaseGuard()
                                this@respondOutputStream.write(buffer, 0, count)
                            }
                        }
                    } finally {
                        heartbeat.cancel()
                        finish(sid, token)
                        withContext(NonCancellable + Dispatchers.IO) {
                            process?.let(::terminate)
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            finish(sid, token)
            withContext(NonCancellable + Dispatchers.IO) {
                process?.let(::terminate)
            }
            throw error
        }
    }

    fun drop(sid: String) {
        synchronized(lifecycle) { active.remove(sid)?.process }?.let(::terminate)
    }

    private fun probeAudioCodec(
        url: String,
        capabilities: MediaCapabilities,
        sid: String,
        token: Any,
    ): String {
        val output = Files.createTempFile("opentv-audio-probe", ".txt")
        val command = mutableListOf("ffprobe", "-v", "error")
        if (url.startsWith("http")) command += listOf("-user_agent", http.userAgent)
        command += listOf(
            "-select_streams", "a:0",
            "-show_entries", "stream=codec_name",
            "-of", "default=nokey=1:noprint_wrappers=1",
            url,
        )
        var process: Process? = null
        return try {
            val started = processRunner.start(
                MediaProcessRequest(command, stdoutFile = output, discardStderr = true)
            )
            process = started
            if (!register(sid, token, started)) {
                terminate(started)
                throw CancellationException("Transcode was superseded")
            }
            if (!started.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                terminate(started)
                "aac"
            } else if (started.exitValue() != 0) {
                "aac"
            } else {
                val codec = Files.readString(output).trim()
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
            process?.let { clearProcess(sid, token, it) }
            Files.deleteIfExists(output)
        }
    }

    private fun register(sid: String, token: Any, process: Process): Boolean =
        synchronized(lifecycle) {
            val current = active[sid]
            if (current?.token !== token) {
                false
            } else {
                active[sid] = Active(token, process)
                true
            }
        }

    private fun clearProcess(sid: String, token: Any, process: Process) {
        synchronized(lifecycle) {
            val current = active[sid]
            if (current?.token === token && current.process === process) {
                active[sid] = Active(token, null)
            }
        }
    }

    private fun ensureCurrent(sid: String, token: Any) {
        if (synchronized(lifecycle) { active[sid]?.token !== token }) {
            throw CancellationException("Transcode was superseded")
        }
    }

    private fun finish(sid: String, token: Any) {
        synchronized(lifecycle) {
            if (active[sid]?.token === token) active.remove(sid)
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

    private companion object {
        const val STREAM_COPY_BUFFER_BYTES = 64 * 1024
        const val STREAM_GUARD_INTERVAL_MS = 4_000L
        const val PROBE_TIMEOUT_SECONDS = 15L
        const val PROCESS_EXIT_WAIT_SECONDS = 3L
    }
}
