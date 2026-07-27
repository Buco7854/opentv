package com.buco7854.opentv.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Copies video, re-encodes audio to AAC, remuxes to MPEG-TS. Client fallback for
 * AC3/E-AC3/DTS/MP2 tracks that mpegts.js can't decode. One process per viewer.
 */
class AudioTranscoder(
    private val http: ServerHttp,
    private val processRunner: MediaProcessRunner = JvmMediaProcessRunner,
) {
    private val processes = ConcurrentHashMap<String, Process>()

    suspend fun stream(
        url: String,
        call: ApplicationCall,
        sid: String,
        leaseGuard: () -> Unit,
    ) {
        leaseGuard()
        val command = mutableListOf(
            "ffmpeg", "-nostdin", "-loglevel", "error", "-user_agent", http.userAgent,
        )
        // Providers drop long-lived transfers; reconnect in place like the remux.
        if (url.startsWith("http")) {
            command += listOf("-reconnect", "1", "-reconnect_streamed", "1", "-reconnect_delay_max", "10")
        }
        command += listOf(
            "-i", url,
            "-map", "0:v:0?", "-map", "0:a:0?",
            "-c:v", "copy",
            "-c:a", "aac", "-ac", "2", "-b:a", "128k",
            "-f", "mpegts", "-",
        )
        val process = withContext(Dispatchers.IO) {
            processRunner.start(MediaProcessRequest(command, discardStderr = true))
        }
        processes.put(sid, process)?.destroyForcibly()
        try {
            leaseGuard()
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respondOutputStream(ContentType.parse("video/mp2t")) {
                withContext(Dispatchers.IO) {
                    process.inputStream.copyTo(this@respondOutputStream, STREAM_COPY_BUFFER_BYTES)
                }
            }
        } finally {
            // Free ffmpeg and its upstream connection when the peer is gone.
            processes.remove(sid, process)
            withContext(NonCancellable + Dispatchers.IO) {
                process.destroyForcibly()
                process.waitFor(3, TimeUnit.SECONDS)
            }
        }
    }

    fun drop(sid: String) {
        processes.remove(sid)?.destroyForcibly()
    }

    private companion object {
        const val STREAM_COPY_BUFFER_BYTES = 64 * 1024
    }
}
