package com.buco7854.opentv.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

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

    fun inspect(url: String): MediaProbeResult {
        probes[url]?.let { (result, timestamp) ->
            if (clock.nowMs() - timestamp < PROBE_TTL_MS) return result
            probes.remove(url)
        }
        val result = runProbe(url)
        if (probes.size > MAX_PROBES) probes.clear()
        probes[url] = result to clock.nowMs()
        return result
    }

    fun keyframes(url: String): List<Double>? {
        keyframes[url]?.let { return it.ifEmpty { null } }
        val output = Files.createTempFile(workDirectory, "kf", ".csv")
        val command = ffprobeCommand(url) +
            listOf("-select_streams", "v:0", "-show_entries", "packet=pts_time,flags", "-of", "csv=p=0", url)
        val result = try {
            val process = processRunner.start(
                MediaProcessRequest(command, stdoutFile = output, discardStderr = true)
            )
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly()
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
        } catch (_: Exception) {
            null
        } finally {
            Files.deleteIfExists(output)
        }
        if (keyframes.size > MAX_KEYFRAME_ENTRIES) keyframes.clear()
        keyframes[url] = result ?: emptyList()
        return result
    }

    fun segmentStarts(keyframes: List<Double>?, targetLength: Double, duration: Double): List<Double> {
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
                target += targetLength
            }
        }
        return starts
    }

    private fun runProbe(url: String): MediaProbeResult {
        val output = Files.createTempFile(workDirectory, "probe", ".json")
        val command = ffprobeCommand(url) +
            listOf("-print_format", "json", "-show_streams", "-show_format", url)
        val document = try {
            val process = processRunner.start(
                MediaProcessRequest(command, stdoutFile = output, discardStderr = true)
            )
            if (!process.waitFor(45, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw IllegalStateException("ffprobe timed out reading the stream")
            }
            if (process.exitValue() != 0) {
                throw IllegalStateException("ffprobe could not read the stream")
            }
            Files.readString(output)
        } finally {
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
                index = text("index")?.toIntOrNull() ?: return@mapNotNull null,
                type = text("codec_type") ?: return@mapNotNull null,
                codec = text("codec_name") ?: "",
                language = tag("language")?.takeIf { it.isNotBlank() && it != "und" },
                title = tag("title")?.takeIf(String::isNotBlank),
                channels = text("channels")?.toIntOrNull(),
                forced = (disposition?.get("forced") as? JsonPrimitive)?.content == "1",
            )
        }
        val duration = ((json["format"] as? JsonObject)?.get("duration") as? JsonPrimitive)
            ?.content?.toDoubleOrNull()
        return MediaProbeResult(streams, duration)
    }

    private fun ffprobeCommand(url: String): List<String> = buildList {
        addAll(listOf("ffprobe", "-v", "error"))
        if (url.startsWith("http")) addAll(listOf("-user_agent", http.userAgent))
    }

    private companion object {
        const val PROBE_TTL_MS = 60 * 60_000L
        const val MAX_PROBES = 128
        const val MAX_KEYFRAME_ENTRIES = 64
    }
}
