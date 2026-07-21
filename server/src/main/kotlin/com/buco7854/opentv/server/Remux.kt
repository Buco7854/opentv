package com.buco7854.opentv.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

/**
 * ffmpeg-backed VOD playback for browsers.
 *
 * A file is served as a VOD HLS playlist (all segments listed up front from the known
 * duration) played by hls.js; one long ffmpeg produces segments on demand, and a
 * backward or far-forward seek kills it and restarts at the target segment. Video is
 * copied when the browser can decode it (H.264, HEVC where supported), transcoded to
 * H.264 otherwise; audio is always AAC. Segments are fMP4 so HEVC passes through and
 * Chrome reads real AAC-LC instead of the HE-AAC hls.js forces for AAC in MPEG-TS.
 *
 * Provider connections: one read per session (a seek kills the previous first),
 * concurrent reads per provider capped at its max_connections (LRU-evicted past that),
 * and an idle session's ffmpeg reaped quickly.
 */
class RemuxService(private val http: ServerHttp, private val connections: ProviderConnections) {

    private val log = LoggerFactory.getLogger("opentv")

    // Encoder for non-browser-playable video (HEVC...). Software libx264 by default;
    // OPENTV_VIDEO_ENCODER selects a hardware encoder, or "copy"/none/off to never
    // transcode. OPENTV_X264_PRESET trades software speed against size.
    private val videoEncoder = System.getenv("OPENTV_VIDEO_ENCODER")?.takeIf { it.isNotBlank() } ?: "libx264"
    private val x264Preset = System.getenv("OPENTV_X264_PRESET")?.takeIf { it.isNotBlank() } ?: "veryfast"
    private val videoTranscodeOff = videoEncoder.lowercase() in setOf("copy", "none", "off", "disabled")

    /** The file has one audio track and no text subtitles: nothing to expose. */
    class NoExtraTracksException : Exception("This file has no additional tracks to expose")

    class StartResult(
        val id: String,
        val playlistUrl: String,
        val durationSec: Double?,
        val audioTracks: List<String>,
        val subtitleTracks: List<String>,
        /** Non-H.264 video that will be copied (not transcoded). */
        val nativeVideoCopy: Boolean,
    )

    private class Session(
        val id: String,
        val dir: Path,
        val url: String,
        // Groups sessions that share one provider's connection allowance.
        val providerKey: String,
        // How many reads that provider permits at once (its max_connections).
        val connectionLimit: Int,
        val clientHevc: Boolean,
        val audioIndex: Int,
        val durationSec: Double,
        // Target segment length (ffmpeg -hls_time). Actual boundaries are in `starts`.
        val segLenSec: Double,
        // Start time (s) of each segment. For copied video these fall on real keyframes so
        // the playlist's durations match the media exactly; uniform otherwise.
        val starts: List<Double>,
        // A catch-up timeshift: the provider serves it sequentially and ffmpeg can't -ss
        // into it, so it's produced from the start with no restart/prune/read throttle.
        val timeshift: Boolean,
        val transcodeVideo: Boolean,
        val videoCodec: String,
        val audio: ProbedStream,
        val subs: List<ProbedStream>,
        val audioLabels: List<String>,
        val subLabels: List<String>,
        val nativeVideoCopy: Boolean,
        @Volatile var lastAccessMs: Long,
        // The running ffmpeg and the segment index it was started at (-1 = none).
        @Volatile var process: Process? = null,
        @Volatile var startNumber: Int = -1,
        // Highest segment index this run has written. Only moves forward within a run (reset
        // on each launch), so pruning segments behind the playhead can't drag it back.
        @Volatile var writtenHead: Int = -1,
    )

    private val sessions = ConcurrentHashMap<String, Session>()
    /** Serializes every start/stop of a read, so the per-provider connection cap holds
     *  and there is no per-session lock-ordering hazard. */
    private val launchLock = Any()
    private val root: Path = Files.createTempDirectory("opentv-remux")
    // Subtitles don't depend on the audio track and mustn't shrink on a seek, so they
    // live in a grow-only store keyed by source URL, shared across a URL's sessions.
    private val subStore: Path = Files.createDirectories(root.resolve("subs"))
    private val subStoreLocks = ConcurrentHashMap<String, Any>()

    companion object {
        private const val IDLE_TIMEOUT_MS = 30_000L
        private const val EVICT_TIMEOUT_MS = 10 * 60_000L
        private const val PROBE_TTL_MS = 60 * 60_000L
        /** How long a segment/init request waits for ffmpeg to write it. */
        private const val SEGMENT_WAIT_MS = 30_000L
        /** How long a subtitle segment waits for ffmpeg to reach its region before serving. */
        private const val SUBTITLE_WAIT_MS = 8_000L
        /** Restart the read if a requested segment is more than this far ahead of what
         *  ffmpeg has written (a real seek, not just buffering ahead). */
        private const val FORWARD_RESTART_GAP_SEC = 24
        /** Segments kept behind the current one before deletion, to bound disk use. */
        private const val KEEP_BEHIND = 4
        private const val COPY_SEGMENT_SEC = 6
        private const val TRANSCODE_SEGMENT_SEC = 3
        /** Subtitle codecs ffmpeg can convert to WebVTT (bitmap subs cannot). */
        private val TEXT_SUB_CODECS = setOf("subrip", "srt", "ass", "ssa", "webvtt", "mov_text", "text")
        /** Audio codecs browsers decode natively; anything else needs the AAC transcode. */
        private val BROWSER_AUDIO = setOf("aac", "mp3", "opus", "flac", "vorbis")
        /** Video codecs browsers decode as-is; anything else is transcoded to H.264. */
        private val BROWSER_VIDEO = setOf("h264")
    }

    @Volatile
    private var availableCheck: Pair<Boolean, Long>? = null

    /** ffmpeg+ffprobe presence, re-checked periodically after a negative result. */
    val available: Boolean
        get() {
            availableCheck?.let { (ok, atMs) ->
                if (ok || System.currentTimeMillis() - atMs < 60_000) return ok
            }
            fun runs(binary: String) = runCatching {
                val process = ProcessBuilder(binary, "-version")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0
            }.onFailure { log.warn("Could not run {}: {}", binary, it.message) }.getOrDefault(false)
            val ok = runs("ffmpeg") && runs("ffprobe")
            availableCheck = ok to System.currentTimeMillis()
            return ok
        }

    /** `-readrate` (ffmpeg 5.0) and its initial burst (6.1) throttle the read; on older
     *  ffmpeg they're an unknown-option error, so gate them by version. */
    private val readrateArgs: List<String> by lazy {
        val version = runCatching {
            val process = ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start()
            val text = process.inputStream.bufferedReader().readText()
            process.waitFor(10, TimeUnit.SECONDS)
            Regex("""version n?(\d+)\.(\d+)""").find(text)?.destructured?.let { (a, b) -> a.toInt() to b.toInt() }
        }.getOrNull() ?: (0 to 0)
        val (major, minor) = version
        when {
            major > 6 || (major == 6 && minor >= 1) -> listOf("-readrate", "1.5", "-readrate_initial_burst", "30")
            major >= 5 -> listOf("-readrate", "1.5")
            else -> emptyList()
        }
    }

    init {
        Runtime.getRuntime().addShutdownHook(Thread {
            sessions.values.forEach { runCatching { it.process?.destroyForcibly() } }
            sessions.values.forEach { runCatching { deleteTree(it.dir) } }
        })
        Thread {
            while (true) {
                runCatching { Thread.sleep(5_000) }
                val now = System.currentTimeMillis()
                sessions.values.forEach { session ->
                    // Free the provider connection once the player stops asking for segments.
                    if (session.process != null && now - session.lastAccessMs > IDLE_TIMEOUT_MS) {
                        stopReading(session)
                    }
                    if (now - session.lastAccessMs > EVICT_TIMEOUT_MS) evict(session)
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun deleteTree(dir: Path) =
        Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }

    /** Kill a session's ffmpeg and wait for it to exit; leaves the connection reservation. */
    private fun killProcess(session: Session) {
        synchronized(launchLock) {
            session.process?.let { process ->
                process.destroyForcibly()
                runCatching { process.waitFor(3, TimeUnit.SECONDS) }
            }
            session.process = null
            session.startNumber = -1
        }
    }

    /** Stop a session's read and release its provider connection (idle reap, close, or an
     *  eviction by another stream). */
    private fun stopReading(session: Session) {
        killProcess(session)
        connections.close(session.id)
    }

    // Under launchLock so it can't race a launch into a half-deleted dir.
    private fun evict(session: Session) {
        synchronized(launchLock) {
            stopReading(session)
            sessions.remove(session.id)
            runCatching { deleteTree(session.dir) }
        }
    }

    fun stop(id: String) {
        sessions[id]?.let { evict(it) }
    }

    private fun sessionId(url: String, clientHevc: Boolean, audioIndex: Int): String =
        MessageDigest.getInstance("SHA-1").digest("$url@${if (clientHevc) "n" else "s"}@$audioIndex".toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)

    // ---- probe ----

    private class ProbedStream(
        val index: Int,
        val type: String,
        val codec: String,
        val language: String?,
        val title: String?,
        val channels: Int?,
        val forced: Boolean,
    )

    private class ProbeResult(val streams: List<ProbedStream>, val durationSec: Double?)

    private val probeCache = ConcurrentHashMap<String, Pair<ProbeResult, Long>>()

    /** Layout doesn't change, so probe each URL once per hour (also keeps the provider
     *  from being opened again on an audio switch or a fresh session for the same file). */
    private fun probeCached(url: String): ProbeResult {
        probeCache[url]?.let { (result, atMs) ->
            if (System.currentTimeMillis() - atMs < PROBE_TTL_MS) return result
            probeCache.remove(url)
        }
        val result = probe(url)
        if (probeCache.size > 128) probeCache.clear()
        probeCache[url] = result to System.currentTimeMillis()
        return result
    }

    private fun probe(url: String): ProbeResult {
        // stdout to a file: reading the pipe can block past the timeout if a provider stalls.
        val outFile = Files.createTempFile(root, "probe", ".json")
        val cmd = mutableListOf("ffprobe", "-v", "error")
        if (url.startsWith("http")) cmd += listOf("-user_agent", http.userAgent)
        cmd += listOf("-print_format", "json", "-show_streams", "-show_format", url)
        val output = try {
            val process = ProcessBuilder(cmd)
                .redirectOutput(outFile.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            if (!process.waitFor(45, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw IllegalStateException("ffprobe timed out reading the stream")
            }
            if (process.exitValue() != 0) throw IllegalStateException("ffprobe could not read the stream")
            Files.readString(outFile)
        } finally {
            Files.deleteIfExists(outFile)
        }
        val json = Json.parseToJsonElement(output) as? JsonObject
            ?: throw IllegalStateException("ffprobe returned no stream info")
        val streams = (json["streams"] as? JsonArray ?: JsonArray(emptyList())).mapNotNull { element ->
            val stream = element as? JsonObject ?: return@mapNotNull null
            fun text(key: String) = (stream[key] as? JsonPrimitive)?.content
            val tags = stream["tags"] as? JsonObject
            fun tag(key: String) = (tags?.get(key) as? JsonPrimitive)?.content
            val disposition = stream["disposition"] as? JsonObject
            ProbedStream(
                index = text("index")?.toIntOrNull() ?: return@mapNotNull null,
                type = text("codec_type") ?: return@mapNotNull null,
                codec = text("codec_name") ?: "",
                language = tag("language")?.takeIf { it.isNotBlank() && it != "und" },
                title = tag("title")?.takeIf { it.isNotBlank() },
                channels = text("channels")?.toIntOrNull(),
                forced = (disposition?.get("forced") as? JsonPrimitive)?.content == "1",
            )
        }
        val duration = ((json["format"] as? JsonObject)?.get("duration") as? JsonPrimitive)
            ?.content?.toDoubleOrNull()
        return ProbeResult(streams, duration)
    }

    private val keyframeCache = ConcurrentHashMap<String, List<Double>>()

    /** All video keyframe timestamps (relative to the first), or null if they can't be read
     *  in time. Copied video can only be cut on these, so knowing them lets the playlist
     *  list each segment's true length instead of a uniform guess. Cached per URL. */
    private fun keyframes(url: String): List<Double>? {
        keyframeCache[url]?.let { return it.ifEmpty { null } }
        val outFile = Files.createTempFile(root, "kf", ".csv")
        val cmd = mutableListOf("ffprobe", "-v", "error")
        if (url.startsWith("http")) cmd += listOf("-user_agent", http.userAgent)
        cmd += listOf("-select_streams", "v:0", "-show_entries", "packet=pts_time,flags", "-of", "csv=p=0", url)
        val result = try {
            val process = ProcessBuilder(cmd)
                .redirectOutput(outFile.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            if (!process.waitFor(30, TimeUnit.SECONDS)) { process.destroyForcibly(); null }
            else {
                val times = Files.readString(outFile).lineSequence().mapNotNull { line ->
                    val parts = line.split(',')
                    if (parts.size >= 2 && parts[1].contains('K')) parts[0].toDoubleOrNull() else null
                }.filter { it.isFinite() }.sorted().toList()
                times.takeIf { it.size >= 2 }?.let { kf -> kf.map { it - kf.first() } }
            }
        } catch (e: Exception) {
            null
        } finally {
            Files.deleteIfExists(outFile)
        }
        if (keyframeCache.size > 64) keyframeCache.clear()
        keyframeCache[url] = result ?: emptyList()
        return result
    }

    /** Segment start times: for copied video, the keyframe at/after each target boundary
     *  (matching ffmpeg's own cutting); uniform when keyframes are unknown or transcoding. */
    private fun segmentStarts(keyframes: List<Double>?, targetLen: Double, duration: Double): List<Double> {
        if (keyframes == null) return generateSequence(0.0) { it + targetLen }.takeWhile { it < duration - 0.1 }.toList()
        // Match ffmpeg's hls cutting: split at the first keyframe past each n*targetLen mark.
        val starts = mutableListOf(0.0)
        var target = targetLen
        for (kf in keyframes) {
            if (kf >= target && kf < duration - 0.1) { starts.add(kf); target += targetLen }
        }
        return starts
    }

    // ---- track labels ----

    private val languageNames = mapOf(
        "eng" to "English", "en" to "English",
        "fre" to "Français", "fra" to "Français", "fr" to "Français",
        "spa" to "Español", "es" to "Español",
        "ger" to "Deutsch", "deu" to "Deutsch", "de" to "Deutsch",
        "ita" to "Italiano", "it" to "Italiano",
        "por" to "Português", "pt" to "Português",
        "rus" to "Русский", "ru" to "Русский",
        "jpn" to "日本語", "ja" to "日本語",
        "kor" to "한국어", "ko" to "한국어",
        "chi" to "中文", "zho" to "中文", "zh" to "中文",
        "ara" to "العربية", "ar" to "العربية",
        "tur" to "Türkçe", "tr" to "Türkçe",
        "nld" to "Nederlands", "dut" to "Nederlands", "nl" to "Nederlands",
        "pol" to "Polski", "pl" to "Polski",
        "swe" to "Svenska", "dan" to "Dansk", "nor" to "Norsk", "fin" to "Suomi",
        "ces" to "Čeština", "cze" to "Čeština",
        "hun" to "Magyar", "ell" to "Ελληνικά", "gre" to "Ελληνικά",
        "heb" to "עברית", "hin" to "हिन्दी", "tha" to "ไทย", "vie" to "Tiếng Việt",
        "ukr" to "Українська", "ron" to "Română", "rum" to "Română",
        "bul" to "Български", "hrv" to "Hrvatski", "srp" to "Srpski",
        "slk" to "Slovenčina", "slv" to "Slovenščina", "cat" to "Català",
        "ind" to "Indonesia", "msa" to "Melayu", "may" to "Melayu",
        "fas" to "فارسی", "per" to "فارسی",
    )

    private val codecNames = mapOf(
        "aac" to "AAC", "ac3" to "AC3", "eac3" to "E-AC3", "dts" to "DTS",
        "truehd" to "TrueHD", "opus" to "Opus", "mp3" to "MP3",
        "flac" to "FLAC", "vorbis" to "Vorbis", "mp2" to "MP2",
    )

    private fun channelsName(channels: Int?) = when (channels) {
        null -> null
        1 -> "Mono"
        2 -> "Stereo"
        6 -> "5.1"
        8 -> "7.1"
        else -> "${channels}ch"
    }

    /** Display labels: language/title plus codec and layout, skipping what the title already says. */
    private fun audioLabels(streams: List<ProbedStream>): List<String> =
        unique(streams.mapIndexed { i, stream ->
            val language = languageNames[stream.language?.lowercase()] ?: stream.language
            val base = stream.title ?: language ?: "Track ${i + 1}"
            val parts = mutableListOf(base)
            if (stream.title != null && language != null && !base.contains(language, true)
                && stream.language?.let { base.contains(it, true) } != true) {
                parts += language
            }
            codecNames[stream.codec.lowercase()]?.let { codec ->
                if (!base.contains(codec, true)) parts += codec
            }
            channelsName(stream.channels)?.let { layout ->
                if (!base.contains(layout)) parts += layout
            }
            parts.joinToString(" · ")
        })

    private fun subtitleLabels(streams: List<ProbedStream>): List<String> =
        unique(streams.mapIndexed { i, stream ->
            val language = languageNames[stream.language?.lowercase()] ?: stream.language
            val base = stream.title ?: language ?: "Track ${i + 1}"
            val parts = mutableListOf(base)
            if (stream.title != null && language != null && !base.contains(language, true)
                && stream.language?.let { base.contains(it, true) } != true) {
                parts += language
            }
            if (stream.forced && !base.contains("forc", true)) parts += "Forced"
            parts.joinToString(" · ")
        })

    private fun unique(labels: List<String>): List<String> {
        val seen = HashMap<String, Int>()
        return labels.map { raw ->
            val label = raw.replace("\"", "").replace("\n", " ").trim().ifBlank { "Track" }
            val count = seen.merge(label, 1, Int::plus)!!
            if (count == 1) label else "$label ($count)"
        }
    }

    // ---- start / playlist ----

    private fun transcodeVideo(video: ProbedStream?, clientHevc: Boolean): Boolean {
        val decodable = video?.codec?.lowercase()?.let { it in BROWSER_VIDEO } ?: true
        val nativeCapable = video?.codec?.lowercase() == "hevc" && clientHevc
        return video != null && !decodable && !videoTranscodeOff && !nativeCapable
    }

    /**
     * Prepare an HLS session for [url] with audio track [audioIndex], writing its VOD
     * playlist. ffmpeg is not started until the first segment is fetched. [connectionLimit]
     * is how many concurrent reads the provider permits (its max_connections).
     */
    fun start(url: String, audioIndex: Int, clientHevc: Boolean, force: Boolean, connectionLimit: Int): StartResult {
        val id = sessionId(url, clientHevc, audioIndex)
        sessions[id]?.let {
            it.lastAccessMs = System.currentTimeMillis()
            return StartResult(id, playlistUrl(id, it.subLabels.isNotEmpty()), it.durationSec.takeIf { d -> d > 0 },
                it.audioLabels, it.subLabels, it.nativeVideoCopy)
        }

        val probed = probeCached(url)
        val audios = probed.streams.filter { it.type == "audio" }
        val subs = probed.streams.filter { it.type == "subtitle" && it.codec.lowercase() in TEXT_SUB_CODECS }
        val video = probed.streams.firstOrNull { it.type == "video" }
        val decodableAudio = audios.firstOrNull()?.codec?.lowercase()?.let { it in BROWSER_AUDIO } ?: true
        val decodableVideo = video?.codec?.lowercase()?.let { it in BROWSER_VIDEO } ?: true
        if (!force && audios.size <= 1 && subs.isEmpty() && decodableAudio && decodableVideo) {
            throw NoExtraTracksException()
        }
        if (video == null) throw IllegalStateException("No video stream found")
        if (audios.isEmpty()) throw IllegalStateException("No audio stream found")
        val duration = probed.durationSec?.takeIf { it > 0 }
            ?: throw IllegalStateException("The source has no known duration")

        val transcode = transcodeVideo(video, clientHevc)
        val nativeVideoCopy = !decodableVideo && !transcode
        // Transcoded video has keyframes forced on every boundary, so uniform segments are
        // exact. For a copied local file, reading its keyframes lists each segment's true
        // length cheaply. Reading them off a remote stream means downloading the whole file
        // (tens of seconds), so there we take uniform segments — hls.js re-anchors each one by
        // its own timestamps, so the small drift from real keyframe cuts is invisible.
        val segLen = if (transcode) TRANSCODE_SEGMENT_SEC.toDouble() else COPY_SEGMENT_SEC.toDouble()
        val useKeyframes = !transcode && !url.startsWith("http")
        val starts = segmentStarts(if (useKeyframes) keyframes(url) else null, segLen, duration)
        val audio = audios.getOrElse(audioIndex) { audios.first() }
        val dir = Files.createDirectories(root.resolve(id))

        val session = Session(
            id, dir, url, providerKeyOf(url), connectionLimit.coerceAtLeast(1), clientHevc, audioIndex,
            duration, segLen, starts, force, transcode, video.codec, audio, subs,
            audioLabels(audios), subtitleLabels(subs), nativeVideoCopy, System.currentTimeMillis(),
        )
        Files.writeString(dir.resolve("main.m3u8"), buildPlaylist(session))
        sessions[id] = session
        log.debug("remux {}: prepared ({}s, {} segs, video {} [{}], {} audio, {} subs)",
            id, duration, starts.size, video.codec,
            if (transcode) "->h264/$videoEncoder" else "copy", audios.size, subs.size)
        return StartResult(id, playlistUrl(id, subs.isNotEmpty()), duration, session.audioLabels,
            session.subLabels, nativeVideoCopy)
    }

    // Subtitles live in a master playlist as WebVTT renditions; without them the media playlist serves directly.
    private fun playlistUrl(id: String, hasSubs: Boolean) =
        "/api/remux/$id/${if (hasSubs) "master.m3u8" else "main.m3u8"}"

    private fun segFile(session: Session, n: Int): Path = session.dir.resolve("main$n.m4s")

    private fun segLengths(session: Session): List<Double> = session.starts.indices.map {
        ((if (it + 1 < session.starts.size) session.starts[it + 1] else session.durationSec) - session.starts[it])
            .coerceAtLeast(0.001)
    }

    private fun buildPlaylist(session: Session): String = buildString {
        val lengths = segLengths(session)
        append("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-PLAYLIST-TYPE:VOD\n")
        append("#EXT-X-TARGETDURATION:${ceil(lengths.maxOrNull() ?: session.segLenSec).toInt()}\n")
        append("#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-MAP:URI=\"init.mp4\"\n")
        lengths.forEachIndexed { n, len ->
            append("#EXTINF:%.6f,\n".format(len))
            append("main$n.m4s\n")
        }
        append("#EXT-X-ENDLIST\n")
    }

    // ---- on-demand segments ----

    /** Build the ffmpeg command for [session] starting at segment [startNumber]. */
    private fun command(session: Session, startNumber: Int): List<String> {
        val cmd = mutableListOf("ffmpeg", "-nostdin", "-y", "-loglevel", "error")
        if (session.url.startsWith("http")) {
            cmd += listOf("-user_agent", http.userAgent,
                "-reconnect", "1", "-reconnect_streamed", "1", "-reconnect_delay_max", "10")
        }
        // Read a little ahead of playback (input side) to bound disk and not hold the provider
        // connection far past what's watched; the initial burst fills hls.js's buffer at startup.
        if (!session.timeshift) cmd += readrateArgs
        if (startNumber > 0 && !session.timeshift && startNumber < session.starts.size) {
            val at = session.starts[startNumber]
            if (session.transcodeVideo) {
                cmd += listOf("-ss", at.toString())
            } else {
                // Copied video can only start on a keyframe: the +0.5s nudge lands -ss on the
                // boundary keyframe, and noaccurate_seek keeps the re-encoded audio there too.
                cmd += listOf("-noaccurate_seek", "-ss", (at + 0.5).toString())
            }
        }
        if (!session.transcodeVideo) cmd += listOf("-fflags", "+genpts")
        cmd += listOf("-i", session.url, "-map", "0:v:0", "-map", "0:${session.audio.index}")
        when {
            !session.transcodeVideo -> {
                cmd += listOf("-c:v", "copy")
                // Copied HEVC in fMP4 must be tagged hvc1, not hev1, or Safari/browsers refuse it.
                if (session.videoCodec.equals("hevc", true)) cmd += listOf("-tag:v", "hvc1")
            }
            videoEncoder == "libx264" -> cmd += listOf(
                "-c:v", "libx264", "-preset", x264Preset, "-crf", "23", "-pix_fmt", "yuv420p",
                // Keyframe on every segment boundary so equal-length segments are exact.
                "-force_key_frames", "expr:gte(t,n_forced*${session.segLenSec})", "-sc_threshold", "0",
            )
            else -> cmd += listOf("-c:v", videoEncoder,
                "-g", ceil(session.segLenSec * 25).toInt().toString())
        }
        val audioFilters = mutableListOf<String>()
        // On a copied-video restart the audio starts a few frames before the boundary; drop those
        // so hls.js joins onto the previous run's tail with a gap, not an overlap that corrupts it.
        if (!session.transcodeVideo && startNumber > 0 && startNumber < session.starts.size) {
            audioFilters += "aselect=gte(t\\,${session.starts[startNumber]})"
        }
        // Downmix to stereo then cap the level: a loud 5.1 centre summed into L+R can clip; the
        // 0.85 ceiling leaves headroom for AAC decode overshoot and is transparent below it.
        audioFilters += "aformat=channel_layouts=stereo"
        audioFilters += "alimiter=limit=0.85:level=disabled"
        cmd += listOf("-af", audioFilters.joinToString(","))
        cmd += listOf(
            "-c:a", "aac", "-b:a", "192k",
            // Keep the source clock and zero the base so A/V/subtitles stay aligned across seeks.
            "-copyts", "-avoid_negative_ts", "disabled", "-start_at_zero", "-max_muxing_queue_size", "2048",
            "-f", "hls", "-max_delay", "5000000", "-hls_time", session.segLenSec.toString(),
            "-hls_playlist_type", "vod", "-hls_list_size", "0", "-start_number", startNumber.toString(),
            "-hls_flags", "temp_file",
        )
        // fMP4 for everything: lets HEVC pass through, and keeps AAC signalled as real AAC-LC
        // instead of the HE-AAC hls.js forces for AAC in MPEG-TS. The init name must be bare:
        // ffmpeg resolves it against the playlist dir on Linux and the CWD on Windows, both the
        // session dir here (launch() sets the CWD) — an absolute path would break Linux.
        cmd += listOf("-hls_segment_type", "fmp4", "-hls_segment_options", "movflags=+frag_discont",
            "-hls_fmp4_init_filename", "init.mp4",
            "-hls_segment_filename", session.dir.resolve("main%d.m4s").toString())
        cmd += session.dir.resolve("ff.m3u8").toString()
        // Sidecar WebVTT per text sub, on the same zeroed clock as the video; flush per packet.
        session.subs.forEachIndexed { j, stream ->
            cmd += listOf("-map", "0:${stream.index}",
                "-copyts", "-avoid_negative_ts", "disabled", "-start_at_zero", "-flush_packets", "1",
                "-f", "webvtt", session.dir.resolve("sub_$j.vtt").toString())
        }
        return cmd
    }

    // Always called under launchLock (from ensureReaching), so it can't race evict.
    private fun launch(session: Session, startNumber: Int) {
        if (sessions[session.id] !== session) return // evicted; don't spawn an orphan
        killProcess(session)
        Files.createDirectories(session.dir)
        // Reserve the provider slot (evicting a stale stream or a background download if
        // the cap is full); the eviction callback stops this session if we're later bumped.
        connections.openStream(session.id, session.providerKey, session.connectionLimit) { stopReading(session) }
        val process = ProcessBuilder(command(session, startNumber))
            // Run in the session dir so ffmpeg's bare fMP4 init filename lands here on every OS.
            .directory(session.dir.toFile())
            .redirectError(ProcessBuilder.Redirect.appendTo(session.dir.resolve("ffmpeg.log").toFile()))
            .start()
        session.process = process
        session.startNumber = startNumber
        session.writtenHead = startNumber - 1
        log.debug("remux {}: ffmpeg from segment {} ({})", session.id, startNumber,
            if (session.transcodeVideo) "transcode" else "copy")
    }

    /** Highest fully-written segment index, or -1. The temp_file flag renames each segment
     *  into place atomically, so any segment that exists is complete. Scan forward from the
     *  last known head, not from startNumber: pruning deletes segments behind the playhead, so
     *  once startNumber's segment is gone a scan from there would wrongly report nothing written. */
    private fun writtenThrough(session: Session): Int {
        if (session.startNumber < 0) return -1
        var n = maxOf(session.startNumber, session.writtenHead + 1)
        while (Files.exists(segFile(session, n))) n++
        session.writtenHead = n - 1
        return session.writtenHead
    }

    /** Decide whether the running ffmpeg can reach segment [n] soon, or restart it there. */
    private fun ensureReaching(session: Session, n: Int) {
        synchronized(launchLock) {
            val process = session.process
            val alive = process != null && process.isAlive
            if (session.timeshift) {
                // Can't seek a timeshift; it only plays forward from the start. Relaunch
                // from 0 if the read was reaped; otherwise wait for it to reach [n].
                if (!alive) launch(session, 0)
                return
            }
            // This is only reached with segment [n] missing. The running ffmpeg will
            // deliver it only if it's just ahead of what's written; anything else - a
            // seek back to a pruned segment, or far past the write head - needs a restart.
            val gapSegments = (FORWARD_RESTART_GAP_SEC / session.segLenSec).toInt().coerceAtLeast(1)
            val written = writtenThrough(session)
            val willReachSoon = alive && n > written && n <= written + gapSegments
            if (!willReachSoon) launch(session, n)
        }
    }

    suspend fun playlist(id: String, call: ApplicationCall) {
        val session = sessions[id] ?: return notFound(call)
        session.lastAccessMs = System.currentTimeMillis()
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respondText(withContext(Dispatchers.IO) { Files.readString(session.dir.resolve("main.m3u8")) },
            ContentType.parse("application/vnd.apple.mpegurl"))
    }

    suspend fun initSegment(id: String, call: ApplicationCall) {
        val session = sessions[id] ?: return notFound(call)
        session.lastAccessMs = System.currentTimeMillis()
        connections.touch(id)
        val init = session.dir.resolve("init.mp4")
        // ffmpeg leaves init.mp4 empty until the first segment is written; wait for that,
        // not init's mere existence, or hls.js gets a 0-byte init and can't decode.
        if (!Files.exists(init) || Files.size(init) == 0L) {
            val start = session.startNumber.coerceAtLeast(0)
            produce(session, start, segFile(session, start))
        }
        if (!Files.exists(init) || Files.size(init) == 0L) return failed(session, call)
        respondFile(call, init, ContentType.parse("video/mp4"))
    }

    suspend fun segment(id: String, n: Int, call: ApplicationCall) {
        val session = sessions[id] ?: return notFound(call)
        session.lastAccessMs = System.currentTimeMillis()
        connections.touch(id)
        val seg = segFile(session, n)
        if (!Files.exists(seg)) produce(session, n, seg)
        if (!Files.exists(seg)) return failed(session, call)
        pruneBehind(session, n)
        respondFile(call, seg, ContentType.parse("video/iso.segment"))
    }

    /** Surface ffmpeg's last log line when it produced nothing, instead of a silent 404. */
    private suspend fun failed(session: Session, call: ApplicationCall) {
        val tail = withContext(Dispatchers.IO) {
            runCatching { Files.readString(session.dir.resolve("ffmpeg.log")).trim().lines().lastOrNull() }.getOrNull()
        }?.takeIf { it.isNotBlank() }
        log.warn("remux {}: ffmpeg produced no output{}", session.id, tail?.let { " - $it" } ?: "")
        call.respondText(tail ?: "ffmpeg produced no output", ContentType.Text.Plain, HttpStatusCode.BadGateway)
    }

    /** Reach segment [n] and wait for [file]; if the read dies without producing it (a
     *  stale single-connection provider often drops the first open), start once more. */
    private suspend fun produce(session: Session, n: Int, file: Path) = withContext(Dispatchers.IO) {
        repeat(2) {
            ensureReaching(session, n)
            awaitFile(session, file)
            if (Files.exists(file)) return@withContext
            stopReading(session)
        }
    }

    private class Cue(val start: Double, val end: Double, val block: String)

    // Our fMP4 segments start at clock 0, so a cue's local time is the media time as-is.
    private val timestampMap = "X-TIMESTAMP-MAP=MPEGTS:0,LOCAL:00:00:00.000"

    /** Master playlist: the video/audio rendition plus one WebVTT subtitle rendition per track. */
    suspend fun master(id: String, call: ApplicationCall) {
        val session = sessions[id] ?: return notFound(call)
        session.lastAccessMs = System.currentTimeMillis()
        val body = buildString {
            append("#EXTM3U\n#EXT-X-VERSION:7\n")
            session.subLabels.forEachIndexed { i, label ->
                append("#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"subs\",NAME=\"${label.replace('"', '\'')}\",")
                append("AUTOSELECT=YES,DEFAULT=NO,FORCED=NO,URI=\"sub_$i.m3u8\"\n")
            }
            append("#EXT-X-STREAM-INF:BANDWIDTH=3000000")
            if (session.subLabels.isNotEmpty()) append(",SUBTITLES=\"subs\"")
            append("\nmain.m3u8\n")
        }
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respondText(body, ContentType.parse("application/vnd.apple.mpegurl"))
    }

    /** A subtitle rendition's own VOD playlist: one WebVTT segment per video segment, so cues
     *  become available in step with the video as ffmpeg produces them. */
    suspend fun subtitlePlaylist(id: String, index: Int, call: ApplicationCall) {
        val session = sessions[id] ?: return notFound(call)
        session.lastAccessMs = System.currentTimeMillis()
        val lengths = segLengths(session)
        val body = buildString {
            append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-PLAYLIST-TYPE:VOD\n")
            append("#EXT-X-TARGETDURATION:${ceil(lengths.maxOrNull() ?: session.segLenSec).toInt()}\n")
            append("#EXT-X-MEDIA-SEQUENCE:0\n")
            lengths.forEachIndexed { n, len ->
                append("#EXTINF:%.6f,\n".format(len))
                append("sub_${index}_$n.vtt\n")
            }
            append("#EXT-X-ENDLIST\n")
        }
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respondText(body, ContentType.parse("application/vnd.apple.mpegurl"))
    }

    /** One subtitle segment: the store's cues overlapping this video segment, timestamp-mapped. */
    suspend fun subtitleSegment(id: String, index: Int, n: Int, call: ApplicationCall) {
        val session = sessions[id] ?: return notFound(call)
        session.lastAccessMs = System.currentTimeMillis()
        val starts = session.starts
        val from = starts.getOrElse(n) { session.durationSec }
        val to = if (n + 1 < starts.size) starts[n + 1] else session.durationSec
        val body = withContext(Dispatchers.IO) {
            // Wait until the matching video segment is written before serving, so hls.js (which
            // caches VOD segments) never caches an empty one for a region ffmpeg hasn't reached.
            val deadline = System.currentTimeMillis() + SUBTITLE_WAIT_MS
            while (writtenThrough(session) < n && System.currentTimeMillis() < deadline) {
                val process = session.process
                if (process != null && !process.isAlive) break
                delay(200)
            }
            val fresh = runCatching { Files.readString(session.dir.resolve("sub_$index.vtt")) }.getOrNull()
            val cues = mergeSubStore(session.url, index, fresh)
            buildString {
                append("WEBVTT\n").append(timestampMap).append("\n\n")
                cues.filter { it.start < to && it.end > from }.forEach { append(it.block).append("\n\n") }
            }
        }
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respondText(body, ContentType.parse("text/vtt"))
    }

    /** Fold this session's freshly-extracted cues into the URL's persistent store (both on the
     *  same clock) and return the union, so switching audio or seeking never drops a cue. */
    private fun mergeSubStore(url: String, index: Int, fresh: String?): List<Cue> {
        val key = sessionId(url, false, 0) + "_$index"
        val file = subStore.resolve("$key.vtt")
        synchronized(subStoreLocks.computeIfAbsent(key) { Any() }) {
            val stored = runCatching { Files.readString(file) }.getOrNull()
            val cues = LinkedHashMap<String, Cue>()
            parseCues(stored).forEach { cues.putIfAbsent(it.block, it) }
            parseCues(fresh).forEach { cues.putIfAbsent(it.block, it) }
            val sorted = cues.values.sortedBy { it.start }
            val merged = if (sorted.isEmpty()) "" else
                buildString { append("WEBVTT\n\n"); sorted.forEach { append(it.block).append("\n\n") } }
            if (merged.isNotBlank() && merged != stored) runCatching { Files.writeString(file, merged) }
            return sorted
        }
    }

    private fun parseCues(doc: String?): List<Cue> {
        doc ?: return emptyList()
        return doc.split(Regex("\\r?\\n\\r?\\n")).mapNotNull { raw ->
            val block = raw.trim()
            val line = block.lineSequence().firstOrNull { it.contains("-->") } ?: return@mapNotNull null
            val start = vttSeconds(line.substringBefore("-->").trim()) ?: return@mapNotNull null
            val end = vttSeconds(line.substringAfter("-->").trim().substringBefore(' ')) ?: (start + 5)
            Cue(start, end, block)
        }
    }

    private fun vttSeconds(ts: String): Double? {
        val parts = ts.split(':')
        val (h, m, s) = when (parts.size) {
            3 -> Triple(parts[0].toDoubleOrNull(), parts[1].toDoubleOrNull(), parts[2].replace(',', '.').toDoubleOrNull())
            2 -> Triple(0.0, parts[0].toDoubleOrNull(), parts[1].replace(',', '.').toDoubleOrNull())
            else -> return null
        }
        return if (h != null && m != null && s != null) h * 3600 + m * 60 + s else null
    }

    /** Wait (bounded) for ffmpeg to write [file], failing fast if it dies first. */
    private suspend fun awaitFile(session: Session, file: Path) {
        val deadline = System.currentTimeMillis() + SEGMENT_WAIT_MS
        while (!Files.exists(file) && System.currentTimeMillis() < deadline) {
            val process = session.process
            if (process != null && !process.isAlive && !Files.exists(file)) {
                // ffmpeg exited without producing it: let one more launch try, then give up.
                break
            }
            delay(100)
        }
    }

    /** Delete segments well behind the one being served, bounding disk without disturbing
     *  the ffmpeg that is writing ahead. */
    private fun pruneBehind(session: Session, current: Int) {
        // A timeshift can't be re-produced (no -ss), so keep everything already streamed.
        if (session.timeshift) return
        var n = current - KEEP_BEHIND - 1
        while (n >= 0) {
            if (!Files.deleteIfExists(segFile(session, n))) break
            n--
        }
    }

    private suspend fun respondFile(call: ApplicationCall, path: Path, type: ContentType) {
        if (!Files.exists(path)) return notFound(call)
        call.respondBytes(withContext(Dispatchers.IO) { Files.readAllBytes(path) }, type)
    }

    private suspend fun notFound(call: ApplicationCall) =
        call.respondText("Not found", ContentType.Text.Plain,
            io.ktor.http.HttpStatusCode.NotFound)
}
