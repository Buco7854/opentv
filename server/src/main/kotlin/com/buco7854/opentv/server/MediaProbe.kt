package com.buco7854.opentv.server

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

internal data class MediaStreamInfo(
    val index: Int,
    val type: String,
    val codec: String,
    val language: String?,
    val title: String?,
    val channels: Int?,
    val forced: Boolean,
)

internal data class MediaProbeResult(
    val streams: List<MediaStreamInfo>,
    val durationSec: Double?,
)

/** Owns ffprobe invocation, output parsing, and bounded probe caches. */
internal class MediaProbe(
    private val http: ServerHttp,
    private val processRunner: MediaProcessRunner,
    private val workDirectory: Path,
    private val clock: ServerClock = ServerClock.SYSTEM,
) {
    private val probes = ConcurrentHashMap<String, Pair<MediaProbeResult, Long>>()
    private val keyframes = ConcurrentHashMap<String, List<Double>>()
    private val inFlight = ConcurrentHashMap<String, Any>()

    /**
     * Stream layout and duration, cached and single-flighted.
     *
     * A remote probe opens one of the provider's connections and sits on the path to the
     * first frame, so two viewers starting the same title must not each pay for one - the
     * second waits for the first and reads its result from the cache.
     */
    fun inspect(
        url: String,
        acquireUpstream: () -> AutoCloseable? = { null },
    ): MediaProbeResult {
        cached(url)?.let { return it }
        val monitor = inFlight.computeIfAbsent(url) { Any() }
        try {
            synchronized(monitor) {
                cached(url)?.let { return it }
                val reservation = acquireUpstream()
                val result = try {
                    runProbe(url)
                } finally {
                    reservation?.close()
                }
                if (probes.size > MAX_PROBES) probes.clear()
                probes[url] = result to clock.nowMs()
                return result
            }
        } finally {
            inFlight.remove(url, monitor)
        }
    }

    private fun cached(url: String): MediaProbeResult? {
        val (result, timestamp) = probes[url] ?: return null
        if (clock.nowMs() - timestamp < PROBE_TTL_MS) return result
        probes.remove(url)
        return null
    }

    fun keyframes(url: String): List<Double>? {
        keyframes[url]?.takeIf { it.isNotEmpty() }?.let { return it }
        val output = Files.createTempFile(workDirectory, "kf", ".csv")
        val command = ffprobeCommand(url) +
            listOf("-select_streams", "v:0", "-show_entries", "packet=pts_time,flags", "-of", "csv=p=0", url)
        var process: Process? = null
        val result = try {
            val started = processRunner.start(
                MediaProcessRequest(command, stdoutFile = output, discardStderr = true)
            )
            process = started
            if (!started.waitFor(30, TimeUnit.SECONDS)) {
                terminate(started)
                null
            } else if (started.exitValue() != 0 ||
                Files.size(output) > MAX_KEYFRAME_OUTPUT_BYTES
            ) {
                null
            } else {
                val times = Files.readString(output).lineSequence().mapNotNull { line ->
                    val parts = line.split(',')
                    if (parts.size >= 2 && parts[1].contains('K')) parts[0].toDoubleOrNull() else null
                }.filter(Double::isFinite).sorted().toList()
                times.takeIf { it.size >= 2 }?.let { values ->
                    values.map { it - values.first() }
                }
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw error
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        } finally {
            process?.takeIf(Process::isAlive)?.let(::terminate)
            Files.deleteIfExists(output)
        }
        if (keyframes.size > MAX_KEYFRAME_ENTRIES) keyframes.clear()
        result?.let { keyframes[url] = it }
        return result
    }

    fun segmentStarts(keyframes: List<Double>?, targetLength: Double, duration: Double): List<Double> {
        require(targetLength.isFinite() && targetLength > 0) { "Invalid segment target" }
        require(duration.isFinite() && duration > 0) { "Invalid media duration" }
        require(ceil(duration / targetLength) <= MAX_SEGMENTS) { "Media has too many segments" }
        if (keyframes == null) {
            return generateSequence(0.0) { it + targetLength }
                .takeWhile { it < duration - 0.1 }
                .toList()
        }
        val starts = mutableListOf(0.0)
        var target = targetLength
        for (keyframe in keyframes) {
            if (keyframe >= target && keyframe < duration - 0.1) {
                starts += keyframe
                require(starts.size <= MAX_SEGMENTS) { "Media has too many segments" }
                target += targetLength
            }
        }
        return starts
    }

    /**
     * Bounded first, unbounded only if the answer looks short.
     *
     * ffprobe's defaults keep reading until they are certain, which on a remote panel is
     * seconds of download before ffmpeg has even started. A few seconds of media names every
     * stream in the containers a panel serves; when it does not - no audio listed, or no
     * duration - the full probe still runs, so a rare awkward file costs time rather than
     * tracks.
     */
    private fun runProbe(url: String): MediaProbeResult {
        if (!url.startsWith("http")) return runProbe(url, bounded = false)
        val quick = try {
            runProbe(url, bounded = true)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw error
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        if (quick != null && quick.durationSec != null &&
            quick.streams.any { it.type == "audio" } && quick.streams.any { it.type == "video" }
        ) {
            return quick
        }
        return runProbe(url, bounded = false)
    }

    private fun runProbe(url: String, bounded: Boolean): MediaProbeResult {
        val output = Files.createTempFile(workDirectory, "probe", ".json")
        val command = ffprobeCommand(url, bounded) +
            listOf("-print_format", "json", "-show_streams", "-show_format", url)
        var process: Process? = null
        val document = try {
            val started = processRunner.start(
                MediaProcessRequest(command, stdoutFile = output, discardStderr = true)
            )
            process = started
            if (!started.waitFor(45, TimeUnit.SECONDS)) {
                terminate(started)
                throw IllegalStateException("ffprobe timed out reading the stream")
            }
            if (started.exitValue() != 0) {
                throw IllegalStateException("ffprobe could not read the stream")
            }
            if (Files.size(output) > MAX_PROBE_OUTPUT_BYTES) {
                throw IllegalStateException("ffprobe returned too much stream info")
            }
            Files.readString(output)
        } finally {
            process?.takeIf(Process::isAlive)?.let(::terminate)
            Files.deleteIfExists(output)
        }
        val json = Json.parseToJsonElement(document) as? JsonObject
            ?: throw IllegalStateException("ffprobe returned no stream info")
        val streams = (json["streams"] as? JsonArray ?: JsonArray(emptyList())).mapNotNull { element ->
            val stream = element as? JsonObject ?: return@mapNotNull null
            fun text(key: String) = (stream[key] as? JsonPrimitive)?.content
            val tags = stream["tags"] as? JsonObject
            fun tag(key: String) = (tags?.get(key) as? JsonPrimitive)?.content
            val disposition = stream["disposition"] as? JsonObject
            MediaStreamInfo(
                index = text("index")?.toIntOrNull()?.takeIf { it >= 0 } ?: return@mapNotNull null,
                type = text("codec_type") ?: return@mapNotNull null,
                codec = text("codec_name") ?: "",
                language = tag("language")?.takeIf { it.isNotBlank() && it != "und" },
                title = tag("title")?.takeIf(String::isNotBlank),
                channels = text("channels")?.toIntOrNull()?.takeIf { it in 1..MAX_AUDIO_CHANNELS },
                forced = (disposition?.get("forced") as? JsonPrimitive)?.content == "1",
            )
        }
        val duration = ((json["format"] as? JsonObject)?.get("duration") as? JsonPrimitive)
            ?.content?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
        return MediaProbeResult(streams, duration)
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

    private fun ffprobeCommand(url: String, bounded: Boolean = false): List<String> = buildList {
        addAll(listOf("ffprobe", "-v", "error"))
        if (url.startsWith("http")) addAll(listOf("-user_agent", http.userAgent))
        if (bounded) {
            addAll(listOf("-analyzeduration", ANALYZE_DURATION_US, "-probesize", PROBE_SIZE_BYTES))
        }
    }

    private companion object {
        const val PROBE_TTL_MS = 60 * 60_000L
        const val ANALYZE_DURATION_US = "5000000"
        const val PROBE_SIZE_BYTES = "8000000"
        const val MAX_PROBES = 128
        const val MAX_KEYFRAME_ENTRIES = 64
        const val MAX_SEGMENTS = 100_000
        const val MAX_AUDIO_CHANNELS = 64
        const val MAX_PROBE_OUTPUT_BYTES = 4L * 1024 * 1024
        const val MAX_KEYFRAME_OUTPUT_BYTES = 32L * 1024 * 1024
        const val PROCESS_EXIT_WAIT_SECONDS = 3L
    }
}
