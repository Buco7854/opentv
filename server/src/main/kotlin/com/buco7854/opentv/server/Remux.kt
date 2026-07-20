package com.buco7854.opentv.server

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
 * ffmpeg-backed track exposure for VOD files: remuxes to an HLS tree so hls.js
 * can list every embedded audio track (as renditions) and text subtitle (as
 * WebVTT). One process reads the source once from the requested anchor; seeks
 * beyond the produced range re-anchor a new session, and the client keeps the
 * full-file timeline via the returned offset. Sessions are reaped when idle.
 */
class RemuxService(private val http: ServerHttp) {

    private val log = LoggerFactory.getLogger("opentv")

    // Encoder for non-browser-playable sources (HEVC...). Default software libx264;
    // OPENTV_VIDEO_ENCODER selects a hardware encoder, or "copy"/none/off/disabled
    // to skip video transcode entirely. OPENTV_X264_PRESET trades speed against size.
    private val videoEncoder = System.getenv("OPENTV_VIDEO_ENCODER")?.takeIf { it.isNotBlank() } ?: "libx264"
    private val x264Preset = System.getenv("OPENTV_X264_PRESET")?.takeIf { it.isNotBlank() } ?: "veryfast"
    private val videoTranscodeOff = videoEncoder.lowercase() in setOf("copy", "none", "off", "disabled")

    /** The file has one audio track and no text subtitles: nothing to expose. */
    class NoExtraTracksException : Exception("This file has no additional tracks to expose")

    class StartResult(
        val id: String,
        val offsetSec: Int,
        val durationSec: Double?,
        val audioTracks: List<String>,
        val subtitleTracks: List<String>,
        /** Non-H.264 video copied (not transcoded); plays only where the browser decodes it. */
        val nativeVideoCopy: Boolean,
    )

    private class Session(
        val id: String,
        val dir: Path,
        val process: Process,
        val offsetSec: Int,
        val durationSec: Double?,
        val audioLabels: List<String>,
        val subs: List<ProbedStream>,
        val subLabels: List<String>,
        val nativeVideoCopy: Boolean,
        @Volatile var lastAccessMs: Long,
        // Played-timeline origin on the source clock; -1 until measured.
        @Volatile var originSec: Double = -1.0,
    )

    private val sessions = ConcurrentHashMap<String, Session>()
    private val root: Path = Files.createTempDirectory("opentv-remux")

    companion object {
        private const val IDLE_TIMEOUT_MS = 10 * 60_000L
        private const val MAX_SESSIONS = 3
        private const val PROBE_TTL_MS = 60 * 60_000L
        /** How long the playlist route waits for ffmpeg's first flush. */
        private const val PLAYLIST_WAIT_MS = 25_000L
        /** Subtitle window length; also the reload cadence for hls.js. */
        private const val SUB_WINDOW_SECONDS = 30
        /** Subtitle codecs ffmpeg can convert to WebVTT (bitmap subs cannot). */
        private val TEXT_SUB_CODECS = setOf("subrip", "srt", "ass", "ssa", "webvtt", "mov_text", "text")
        /** Audio codecs browsers decode natively; anything else needs the AAC transcode. */
        private val BROWSER_AUDIO = setOf("aac", "mp3", "opus", "flac", "vorbis")
        /** Video codecs browsers decode as-is; anything else is transcoded to H.264. */
        private val BROWSER_VIDEO = setOf("h264")
        private val MEDIA_NAME = Regex("""NAME="[^"]*"""")
        private val SUB_PLAYLIST = Regex("""sub_(\d+)\.m3u8""")
        private val SUB_SEGMENT = Regex("""sub_(\d+)_(\d+)\.vtt""")
        private val VTT_CUE_TIME = Regex("""(?:(\d+):)?(\d{2}):(\d{2})\.(\d{3}) --> (?:(\d+):)?(\d{2}):(\d{2})\.(\d{3})""")
    }

    @Volatile
    private var availableCheck: Pair<Boolean, Long>? = null

    /** ffmpeg+ffprobe presence. A negative result is re-checked periodically
     *  so a transient spawn failure can't disable the feature until restart. */
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
            }.onFailure {
                LoggerFactory.getLogger("opentv").warn("Could not run {}: {}", binary, it.message)
            }.getOrDefault(false)
            val ok = runs("ffmpeg") && runs("ffprobe")
            availableCheck = ok to System.currentTimeMillis()
            return ok
        }

    init {
        Runtime.getRuntime().addShutdownHook(Thread { sessions.values.forEach { destroy(it) } })
        Thread {
            while (true) {
                Thread.sleep(60_000)
                val cutoff = System.currentTimeMillis() - IDLE_TIMEOUT_MS
                sessions.values.filter { it.lastAccessMs < cutoff }.forEach { evict(it) }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun evict(session: Session) {
        sessions.remove(session.id)
        log.debug("remux {}: evicted (alive={}, {} live sessions remain)",
            session.id, session.process.isAlive, sessions.size)
        destroy(session)
    }

    private fun destroy(session: Session) {
        runCatching { session.process.destroyForcibly() }
        runCatching {
            Files.walk(session.dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun sessionId(url: String): String =
        MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)

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

    /** Layout doesn't change across re-anchors, so probe each URL once per hour. */
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
        // stdout to a file: reading the pipe can block forever past the timeout if a provider stalls.
        val outFile = Files.createTempFile(root, "probe", ".json")
        val output = try {
            val process = ProcessBuilder(
                "ffprobe", "-v", "error", "-user_agent", http.userAgent,
                "-print_format", "json", "-show_streams", "-show_format", url,
            ).redirectOutput(outFile.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start()
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

    /** Starts (or reuses) a remux session anchored at [startSec]; returns once ffmpeg is up. */
    fun start(url: String, startSec: Int = 0, force: Boolean = false, clientHevc: Boolean = false): StartResult {
        // HEVC-capable vs incapable clients produce different output, so they key different sessions.
        val id = sessionId("$url@$startSec@${if (clientHevc) "n" else "s"}")
        sessions[id]?.let { session ->
            if (session.process.isAlive || Files.exists(session.dir.resolve("index.m3u8"))) {
                session.lastAccessMs = System.currentTimeMillis()
                return StartResult(id, session.offsetSec, session.durationSec,
                    session.audioLabels, session.subLabels, session.nativeVideoCopy)
            }
            evict(session)
        }

        val probed = probeCached(url)
        val audios = probed.streams.filter { it.type == "audio" }
        val subs = probed.streams.filter { it.type == "subtitle" && it.codec.lowercase() in TEXT_SUB_CODECS }
        // `force` (catch-up) always engages: the caller wants the seekable HLS timeline.
        val videoStream = probed.streams.firstOrNull { it.type == "video" }
        val decodableAudio = audios.firstOrNull()?.codec?.lowercase()?.let { it in BROWSER_AUDIO } ?: true
        val decodableVideo = videoStream?.codec?.lowercase()?.let { it in BROWSER_VIDEO } ?: true
        // Non-H.264 video is copied when the client can decode it or transcode is off, else re-encoded.
        val nativeCapable = videoStream?.codec?.lowercase() == "hevc" && clientHevc
        val transcodeVideo = !decodableVideo && !videoTranscodeOff && !nativeCapable
        val nativeVideoCopy = videoStream != null && !decodableVideo && !transcodeVideo
        // Nothing to expose only when the browser can play the source directly.
        if (!force && audios.size <= 1 && subs.isEmpty() && decodableAudio && decodableVideo) {
            throw NoExtraTracksException()
        }
        if (videoStream == null) throw IllegalStateException("No video stream found")
        if (audios.isEmpty()) throw IllegalStateException("No audio stream found")

        while (sessions.size >= MAX_SESSIONS) {
            evict(sessions.values.minByOrNull { it.lastAccessMs } ?: break)
        }

        val audioLabels = audioLabels(audios)
        val subLabels = subtitleLabels(subs)
        val dir = Files.createDirectories(root.resolve(id))

        val command = mutableListOf(
            "ffmpeg", "-nostdin", "-y", "-loglevel", "error",
            "-user_agent", http.userAgent,
        )
        // Providers routinely drop long-lived transfers; resume in place.
        if (url.startsWith("http")) {
            command += listOf("-reconnect", "1", "-reconnect_streamed", "1", "-reconnect_delay_max", "10")
        }
        // Input-side seek: fast keyframe jump, output timeline starts at 0.
        if (startSec > 0) command += listOf("-ss", startSec.toString())
        command += listOf("-i", url, "-map", "0:v:0")
        audios.forEach { command += listOf("-map", "0:${it.index}") }
        when {
            !transcodeVideo -> command += listOf("-c:v", "copy")
            videoEncoder == "libx264" ->
                command += listOf("-c:v", "libx264", "-preset", x264Preset, "-crf", "23", "-pix_fmt", "yuv420p")
            // Hardware encoder: keep it minimal, its own defaults apply.
            else -> command += listOf("-c:v", videoEncoder)
        }
        command += listOf(
            "-c:a", "aac", "-ac", "2", "-b:a", "192k",
            // Keep everything on the source's absolute clock so an anchored -ss doesn't
            // skew copied video (keyframe) apart from audio/subs (exact target).
            "-copyts", "-avoid_negative_ts", "disabled",
            "-muxdelay", "0", "-muxpreload", "0",
            "-f", "hls",
            "-hls_time", "6",
            "-hls_playlist_type", "event",
            // fMP4 (CMAF) segments: MSE ingests directly, so hls.js skips TS transmux
            // (its TS demuxer can't handle HEVC). HEVC in fMP4 plays where the browser decodes it.
            "-hls_segment_type", "fmp4",
            "-hls_flags", "independent_segments+temp_file",
            // Bare basename: ffmpeg copies it verbatim into EXT-X-MAP and runs in the session dir.
            "-hls_fmp4_init_filename", "init_%v.mp4",
            "-hls_segment_filename", dir.resolve("seg_%v_%05d.m4s").toString(),
            "-master_pl_name", "master.m3u8",
            // Plain a0/a1 names keep display labels out of file naming; NAMEs rewritten in served master.
            "-var_stream_map", buildString {
                append("v:0,agroup:aud")
                audios.forEachIndexed { i, stream ->
                    append(" a:$i,agroup:aud,name:a$i")
                    stream.language?.let { append(",language:$it") }
                    if (i == 0) append(",default:yes")
                }
            },
            dir.resolve("out_%v.m3u8").toString(),
        )
        // Sidecar WebVTT from the same read; per-packet flush or tiny cues stall in the buffer.
        subs.forEachIndexed { j, stream ->
            command += listOf(
                "-map", "0:${stream.index}", "-flush_packets", "1", "-copyts",
                "-f", "webvtt", dir.resolve("sub_$j.vtt").toString(),
            )
        }

        fun spawn(): Process = ProcessBuilder(command)
            // Run in the session dir so the relative fMP4 init filename lands here.
            .directory(dir.toFile())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.appendTo(dir.resolve("ffmpeg.log").toFile()))
            .start()

        // Watch startup only: an instant death is usually a stale provider connection;
        // one delayed respawn rides it out. Later failures surface via the playlist route.
        var process = spawn()
        var attempt = 0
        val watchDeadline = System.currentTimeMillis() + 2_500
        while (System.currentTimeMillis() < watchDeadline) {
            if (Files.exists(dir.resolve("master.m3u8"))) break
            if (!process.isAlive) {
                if (attempt++ > 0) {
                    val tail = runCatching {
                        Files.readString(dir.resolve("ffmpeg.log")).trim().lines().lastOrNull()
                    }.getOrNull()
                    runCatching {
                        Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                    }
                    log.warn("remux {}: ffmpeg died twice at startup (start={}s) - {}", id, startSec, tail)
                    throw IllegalStateException("ffmpeg could not open the stream" + (tail?.let { ": $it" } ?: ""))
                }
                Thread.sleep(2_000)
                process = spawn()
            }
            Thread.sleep(150)
        }

        val session = Session(
            id, dir, process, startSec, probed.durationSec,
            audioLabels, subs, subLabels, nativeVideoCopy, System.currentTimeMillis(),
        )
        sessions[id] = session
        log.debug("remux {}: started (start={}s, video {} [{}], {} audio, {} subs, ffmpeg {})",
            id, startSec, videoStream.codec, if (transcodeVideo) "->h264/$videoEncoder" else "copy",
            audioLabels.size, subLabels.size, if (process.isAlive) "up" else "already exited")
        return StartResult(id, startSec, probed.durationSec, audioLabels, subLabels, nativeVideoCopy)
    }

    /** index.m3u8 = ffmpeg's master with our audio names and subtitle renditions injected. */
    private fun ensureEntryPoint(session: Session) {
        synchronized(session) {
            if (Files.exists(session.dir.resolve("index.m3u8"))) return
            if (!Files.exists(session.dir.resolve("master.m3u8"))) return
            val master = Files.readAllLines(session.dir.resolve("master.m3u8"))
            // master is written non-atomically; wait for STREAM-INF (last line) so the
            // cached index.m3u8 isn't built from a half-written, video-less master.
            if (master.none { it.startsWith("#EXT-X-STREAM-INF") }) return
            var audioIndex = 0
            val lines = master.flatMap { line ->
                when {
                    line.startsWith("#EXT-X-MEDIA:TYPE=AUDIO") -> {
                        val name = session.audioLabels.getOrNull(audioIndex++)
                        listOf(if (name == null) line else MEDIA_NAME.replaceFirst(line, "NAME=\"$name\""))
                    }
                    line.startsWith("#EXT-X-STREAM-INF") -> {
                        val media = session.subs.mapIndexed { j, stream ->
                            val language = stream.language?.let { ",LANGUAGE=\"$it\"" } ?: ""
                            "#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"sub\",NAME=\"${session.subLabels[j]}\"$language,URI=\"sub_$j.m3u8\""
                        }
                        media + (if (session.subs.isEmpty()) line else "$line,SUBTITLES=\"sub\"")
                    }
                    else -> listOf(line)
                }
            }
            Files.writeString(session.dir.resolve("index.m3u8"), lines.joinToString("\n") + "\n")
        }
    }

    /** Played-timeline origin = first video timestamp (the -ss keyframe); used to window subs. */
    private fun playedOrigin(session: Session): Double {
        val known = session.originSec
        if (known >= 0) return known
        val first = session.dir.resolve("seg_0_00000.ts")
        if (!Files.exists(first)) return session.offsetSec.toDouble()
        val pts = runCatching {
            val process = ProcessBuilder(
                "ffprobe", "-v", "error", "-select_streams", "v",
                "-show_entries", "packet=pts_time", "-of", "csv=p=0",
                "-read_intervals", "%+#1", first.toString(),
            ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            val output = process.inputStream.readBytes().decodeToString()
            process.waitFor(10, TimeUnit.SECONDS)
            output.lineSequence().firstOrNull { it.isNotBlank() }?.trim(',', ' ')?.toDoubleOrNull()
        }.getOrNull() ?: return session.offsetSec.toDouble()
        session.originSec = pts
        return pts
    }

    private class VttCue(val startSec: Double, val endSec: Double, val block: String)

    /** ffmpeg's sidecar VTT grows as it reads the source; parse what exists. */
    private fun parseCues(session: Session, subIndex: Int): List<VttCue> {
        val text = runCatching { Files.readString(session.dir.resolve("sub_$subIndex.vtt")) }.getOrNull()
            ?: return emptyList()
        return text.split(Regex("\n\n+")).mapNotNull { block ->
            val match = VTT_CUE_TIME.find(block) ?: return@mapNotNull null
            fun sec(h: String?, m: String, s: String, ms: String) =
                (h?.toDouble() ?: 0.0) * 3600 + m.toDouble() * 60 + s.toDouble() + ms.toDouble() / 1000
            val (h1, m1, s1, ms1, h2, m2, s2, ms2) = match.destructured
            VttCue(sec(h1.ifEmpty { null }, m1, s1, ms1), sec(h2.ifEmpty { null }, m2, s2, ms2), block.trim())
        }
    }

    /**
     * A subtitle rendition as a growing windowed playlist: the sidecar VTT only holds
     * cues up to ffmpeg's read position, so a window is listed only once no later cue
     * can land in it. Players poll it with live semantics.
     */
    private fun subPlaylist(session: Session, subIndex: Int): String {
        val origin = playedOrigin(session)
        val done = !session.process.isAlive
        val duration = (session.durationSec?.takeIf { it > 0 } ?: 7200.0) - session.offsetSec
        val total = ceil(duration / SUB_WINDOW_SECONDS).toInt().coerceAtLeast(1)
        // Window K is complete once a cue starts past it, the video writer read well
        // past it (dialog-free gaps mustn't stall), or ffmpeg finished.
        val maxStart = (parseCues(session, subIndex).maxOfOrNull { it.startSec } ?: origin) - origin
        val videoSec = runCatching {
            Files.list(session.dir).use { stream ->
                stream.map { it.fileName.toString() }
                    .filter { it.startsWith("seg_0_") && it.endsWith(".ts") }
                    .mapToInt { it.removePrefix("seg_0_").removeSuffix(".ts").toIntOrNull() ?: -1 }
                    .max().orElse(-1)
            }
        }.getOrDefault(-1) * 6.0
        val read = maxOf(maxStart, videoSec - 12)
        val complete = if (done) total else (read / SUB_WINDOW_SECONDS).toInt().coerceIn(0, total)
        return buildString {
            append("#EXTM3U\n#EXT-X-VERSION:3\n")
            append("#EXT-X-TARGETDURATION:$SUB_WINDOW_SECONDS\n")
            append("#EXT-X-MEDIA-SEQUENCE:0\n")
            for (k in 0 until complete) {
                val len = if (k == total - 1) duration - SUB_WINDOW_SECONDS.toLong() * k else SUB_WINDOW_SECONDS.toDouble()
                append("#EXTINF:%.3f,\n".format(len))
                append("sub_${subIndex}_$k.vtt\n")
            }
            if (done) append("#EXT-X-ENDLIST\n")
        }
    }

    private fun subSegment(session: Session, subIndex: Int, window: Int): String {
        val origin = playedOrigin(session)
        val from = window.toLong() * SUB_WINDOW_SECONDS + origin
        val to = from + SUB_WINDOW_SECONDS
        val cues = parseCues(session, subIndex).filter { it.startSec < to && it.endSec > from }
        // Cues and TS packets share the source clock (-copyts), so the identity map aligns them.
        return "WEBVTT\nX-TIMESTAMP-MAP=LOCAL:00:00:00.000,MPEGTS:0\n\n" +
            cues.joinToString("\n\n") { it.block } + "\n"
    }

    /** Ends a session now, freeing its provider connection and disk. */
    fun stop(id: String) {
        sessions[id]?.let { evict(it) }
    }

    /** Resolves a file in a session dir; playlist requests wait for ffmpeg's first flush. */
    fun resolve(id: String, file: String): Path? {
        val session = sessions[id] ?: run {
            log.debug("remux {}: no such session for {} (evicted, replaced, or already stopped)", id, file)
            return null
        }
        if (file.contains('/') || file.contains('\\') || file.contains("..")) return null
        session.lastAccessMs = System.currentTimeMillis()
        val path = session.dir.resolve(file)

        SUB_PLAYLIST.matchEntire(file)?.let { match ->
            if (match.groupValues[1].toInt() >= session.subs.size) return null
            Files.writeString(path, subPlaylist(session, match.groupValues[1].toInt()))
            return path
        }
        SUB_SEGMENT.matchEntire(file)?.let { match ->
            val (subIndex, window) = match.groupValues[1].toInt() to match.groupValues[2].toInt()
            if (subIndex >= session.subs.size) return null
            Files.writeString(path, subSegment(session, subIndex, window))
            return path
        }
        if (!file.endsWith(".m3u8")) return path.takeIf { Files.exists(it) }

        val deadline = System.currentTimeMillis() + PLAYLIST_WAIT_MS
        while (true) {
            ensureEntryPoint(session)
            if (Files.exists(path)) return path
            // Dead writer that never flushed: report now so the player can fall back.
            if (!session.process.isAlive) {
                log.warn("remux {}: ffmpeg exited before producing {} - {}", id, file, ffmpegTail(session))
                return null
            }
            if (System.currentTimeMillis() >= deadline) {
                log.warn("remux {}: gave up after {}ms waiting for {} (ffmpeg still running) - {}",
                    id, PLAYLIST_WAIT_MS, file, ffmpegTail(session))
                return null
            }
            Thread.sleep(150)
            session.lastAccessMs = System.currentTimeMillis()
        }
    }

    /** Last few lines of a session's ffmpeg stderr, for surfacing failures. */
    private fun ffmpegTail(session: Session): String =
        runCatching {
            Files.readString(session.dir.resolve("ffmpeg.log")).trim().lines()
                .filter { it.isNotBlank() }.takeLast(3).joinToString(" | ")
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "(no ffmpeg output yet)"
}
