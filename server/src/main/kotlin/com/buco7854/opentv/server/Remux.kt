package com.buco7854.opentv.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import com.buco7854.opentv.core.log.ProviderSecrets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * ffmpeg-backed VOD playback for browsers.
 *
 * A file is served as a VOD HLS playlist (all segments listed up front from the known
 * duration) played by hls.js; one long ffmpeg produces segments on demand, and a
 * backward or far-forward seek kills it and restarts at the target segment. Video is
 * copied when the browser can decode it (H.264, HEVC where supported), transcoded to
 * H.264 otherwise; audio is always AAC.
 *
 * This class owns session lifetime, segment production and HTTP serving; the pipeline it
 * runs lives in [RemuxCommandBuilder], the documents it publishes in [RemuxPlaylists], and
 * the cues it accumulates in [SubtitleCueStore].
 *
 * Provider connections: one read per session (a seek kills the previous first),
 * concurrent reads per provider capped at its max_connections (LRU-evicted past that),
 * and an idle session's ffmpeg reaped quickly.
 */
class RemuxService(
    http: ServerHttp,
    private val connections: ProviderConnections,
    private val videoEncoder: String = "libx264",
    x264Preset: String = "veryfast",
    private val processRunner: MediaProcessRunner = JvmMediaProcessRunner,
) {
    private val log = LoggerFactory.getLogger("opentv")

    // Encoder for non-browser-playable video (HEVC...). Software libx264 by default;
    // OPENTV_VIDEO_ENCODER selects a hardware encoder, or "copy"/none/off to never
    // transcode. OPENTV_X264_PRESET trades software speed against size.
    private val videoTranscodeOff = videoEncoder.lowercase() in setOf("copy", "none", "off", "disabled")

    /** The file has one audio track and no text subtitles: nothing to expose. */
    class NoExtraTracksException : Exception("This file has no additional tracks to expose")

    /** The provider's other active streams already fill its connection allowance. */
    class ConnectionLimitException(val limit: Int) :
        Exception("The provider allows only $limit connection${if (limit == 1) "" else "s"} at once, all in use")

    class StartResult(
        val id: String,
        val playlistUrl: String,
        val durationSec: Double?,
        val audioTracks: List<String>,
        val subtitleTracks: List<String>,
        /** Non-H.264 video that will be copied (not transcoded). */
        val nativeVideoCopy: Boolean,
    )

    private val sessions = ConcurrentHashMap<String, RemuxSession>()
    /** Serializes every start/stop of a read, so the per-provider connection cap holds
     *  and there is no per-session lock-ordering hazard. */
    private val launchLock = Any()
    private val root: Path = Files.createTempDirectory("opentv-remux")
    private val subtitles = SubtitleCueStore(root)
    private val mediaProbe = MediaProbe(http, processRunner, root)
    private val ffmpeg = FfmpegSupport(processRunner)
    private val commands = RemuxCommandBuilder({ http.userAgent }, videoEncoder, x264Preset, ffmpeg)

    companion object {
        private const val IDLE_TIMEOUT_MS = 30_000L
        private const val EVICT_TIMEOUT_MS = 10 * 60_000L
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
        // Our fMP4 segments start at clock 0, so a cue's local time is the media time as-is.
        private const val TIMESTAMP_MAP = "X-TIMESTAMP-MAP=MPEGTS:0,LOCAL:00:00:00.000"
    }

    /** ffmpeg+ffprobe presence, re-checked periodically after a negative result. */
    val available: Boolean get() = ffmpeg.available

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var running = true
    private val reaper = scope.launch {
        while (isActive) {
            delay(5_000)
            val now = System.currentTimeMillis()
            sessions.values.forEach { session ->
                // Free the provider connection once the player stops asking for segments.
                if (session.process != null && now - session.lastAccessMs > IDLE_TIMEOUT_MS) {
                    stopReading(session)
                }
                if (now - session.lastAccessMs > EVICT_TIMEOUT_MS) evict(session)
            }
        }
    }

    /** Stop all processes and remove the temporary artifact tree. */
    fun close() {
        if (!running) return
        running = false
        reaper.cancel()
        scope.cancel()
        sessions.values.toList().forEach(::evict)
        runCatching { deleteTree(root) }
    }

    private fun deleteTree(dir: Path) =
        Files.walk(dir).use { tree ->
            tree.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }

    /** Kill a session's ffmpeg and wait for it to exit; leaves the connection reservation. */
    private fun killProcess(session: RemuxSession) {
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
    private fun stopReading(session: RemuxSession) {
        killProcess(session)
        connections.close(session.id)
    }

    // Under launchLock so it can't race a launch into a half-deleted dir.
    private fun evict(session: RemuxSession) {
        synchronized(launchLock) {
            stopReading(session)
            sessions.remove(session.id)
            runCatching { deleteTree(session.dir) }
        }
    }

    fun stop(id: String) {
        sessions[id]?.let { evict(it) }
    }

    /** Stop only reads owned by this playback lease/share group. */
    fun stopGroup(group: String) {
        sessions.values.filter { it.shareKey == group }.forEach { evict(it) }
    }

    /** Read-only snapshot of a live session's ffmpeg pipeline, for the admin dashboard's
     *  "why is this transcoding/remuxing" panel. Null when the session is gone. */
    data class RemuxDiagnostics(
        val videoCodec: String,
        val transcodeVideo: Boolean,
        val videoEncoder: String,
        val nativeVideoCopy: Boolean,
        val audioCodec: String,
        val audioChannels: Int?,
        val audioLabel: String?,
        val subtitleCount: Int,
        val segmentCount: Int,
        val timeshift: Boolean,
        val providerKey: String,
        val connectionLimit: Int,
        val ffmpegRunning: Boolean,
        val durationSec: Double?,
        val lastLog: String?,
    )

    fun diagnostics(id: String): RemuxDiagnostics? {
        val session = sessions[id] ?: return null
        return RemuxDiagnostics(
            videoCodec = session.videoCodec,
            transcodeVideo = session.transcodeVideo,
            videoEncoder = videoEncoder,
            nativeVideoCopy = session.nativeVideoCopy,
            audioCodec = session.audio.codec,
            audioChannels = session.audio.channels,
            audioLabel = session.audioLabels.getOrNull(session.audioIndex),
            subtitleCount = session.subLabels.size,
            segmentCount = session.starts.size,
            timeshift = session.timeshift,
            providerKey = session.providerKey,
            connectionLimit = session.connectionLimit,
            ffmpegRunning = session.process?.isAlive == true,
            durationSec = session.durationSec.takeIf { it > 0 },
            lastLog = lastLogLine(session),
        )
    }

    /** ffmpeg echoes the input URL in its errors, and that URL carries the provider's
     *  credentials - this line is served to the viewer and shown on the admin dashboard. */
    private fun lastLogLine(session: RemuxSession): String? = runCatching {
        Files.readString(session.logFile).trim().lines().lastOrNull { it.isNotBlank() }
    }.getOrNull()?.let(ProviderSecrets::redact)

    private fun sessionId(url: String, clientHevc: Boolean, audioIndex: Int, group: String): String =
        shortSha1("$url@${if (clientHevc) "n" else "s"}@$audioIndex@$group")

    // ---- start / playlist ----

    /**
     * Prepare an HLS session for [url] with audio track [audioIndex], writing its VOD
     * playlist. ffmpeg is not started until the first segment is fetched. [connectionLimit]
     * is how many concurrent reads the provider permits (its max_connections).
     */
    fun start(url: String, audioIndex: Int, clientHevc: Boolean, timeshift: Boolean, connectionLimit: Int,
              group: String, supersede: Set<String>): StartResult {
        val id = sessionId(url, clientHevc, audioIndex, group)
        prepared(id)?.let { return it }

        // Refuse a new stream when the provider's other streams (live or other VOD) already
        // fill its connection allowance, so the viewer sees a clear message instead of bumping
        // someone else off. This group's own reads (an audio-track switch, or another member of
        // the same room) share its one connection and don't count. Checked before probing, since
        // ffprobe itself opens one of the provider's connections.
        val providerKey = providerKeyOf(url)
        val cap = connectionLimit.coerceAtLeast(1)
        if (connections.distinctStreams(providerKey, group) >= cap) {
            throw ConnectionLimitException(connectionLimit)
        }

        val probed = mediaProbe.inspect(url) { reserveProbeConnection(id, url, providerKey, connectionLimit) }
        val audios = probed.streams.filter { it.type == "audio" }
        val subs = probed.streams.filter { it.type == "subtitle" && it.codec.lowercase() in TEXT_SUB_CODECS }
        val video = probed.streams.firstOrNull { it.type == "video" }
        val decodableAudio = MediaCodecs.audioDecodable(audios.firstOrNull()?.codec)
        val decodableVideo = MediaCodecs.videoDecodable(video?.codec)
        if (!timeshift && audios.size <= 1 && subs.isEmpty() && decodableAudio && decodableVideo) {
            throw NoExtraTracksException()
        }
        if (video == null) throw IllegalStateException("No video stream found")
        if (audios.isEmpty()) throw IllegalStateException("No audio stream found")
        val duration = probed.durationSec?.takeIf { it > 0 }
            ?: throw IllegalStateException("The source has no known duration")

        // Copy HEVC the browser says it can decode; transcode anything else it can't, unless
        // transcoding is turned off entirely.
        val nativeCapable = MediaCodecs.isHevc(video.codec) && clientHevc
        val transcode = !decodableVideo && !videoTranscodeOff && !nativeCapable
        val nativeVideoCopy = !decodableVideo && !transcode
        // Transcoded video has keyframes forced on every boundary, so uniform segments are
        // exact. For a copied local file, reading its keyframes lists each segment's true
        // length cheaply. Reading them off a remote stream means downloading the whole file
        // (tens of seconds), so there we take uniform segments — hls.js re-anchors each one by
        // its own timestamps, so the small drift from real keyframe cuts is invisible.
        val segLen = if (transcode) TRANSCODE_SEGMENT_SEC.toDouble() else COPY_SEGMENT_SEC.toDouble()
        val useKeyframes = !transcode && !url.startsWith("http")
        val starts = mediaProbe.segmentStarts(
            if (useKeyframes) mediaProbe.keyframes(url) else null,
            segLen,
            duration,
        )
        val audio = audios.getOrElse(audioIndex) { audios.first() }
        val dir = Files.createDirectories(root.resolve(id))

        val session = RemuxSession(
            id, dir, url, providerKey, group, cap, audioIndex,
            duration, segLen, starts, timeshift, transcode, video.codec, audio, subs,
            MediaTrackLabels.audio(audios), MediaTrackLabels.subtitles(subs), nativeVideoCopy,
            System.currentTimeMillis(),
        )
        // Publish the viable replacement before retiring old reads. Preparation failures leave
        // existing viewers untouched.
        return synchronized(launchLock) {
            prepared(id)?.let { return@synchronized it }
            Files.writeString(session.playlistFile, RemuxPlaylists.media(session))
            sessions[id] = session
            sessions.values.filter { it.id != id && it.shareKey in supersede }.forEach { evict(it) }
            log.debug("remux {}: prepared ({}s, {} segs, video {} [{}], {} audio, {} subs)",
                id, duration, starts.size, video.codec,
                if (transcode) "->h264/$videoEncoder" else "copy", audios.size, subs.size)
            StartResult(id, playlistUrl(id, subs.isNotEmpty()), duration, session.audioLabels,
                session.subLabels, nativeVideoCopy)
        }
    }

    /** ffprobe opens one of the provider's connections; hold a slot for the length of the probe
     *  so it can't push the provider over its cap. Local files touch no provider. */
    private fun reserveProbeConnection(
        id: String,
        url: String,
        providerKey: String,
        connectionLimit: Int,
    ): AutoCloseable? {
        if (!url.startsWith("http")) return null
        val probeId = "probe:$id"
        if (!connections.tryOpenStream(probeId, providerKey, probeId, connectionLimit, {})) {
            throw ConnectionLimitException(connectionLimit)
        }
        return AutoCloseable { connections.close(probeId) }
    }

    private fun prepared(id: String): StartResult? = sessions[id]?.let { session ->
        session.lastAccessMs = System.currentTimeMillis()
        StartResult(
            id,
            playlistUrl(id, session.subLabels.isNotEmpty()),
            session.durationSec.takeIf { duration -> duration > 0 },
            session.audioLabels,
            session.subLabels,
            session.nativeVideoCopy,
        )
    }

    // Subtitles live in a master playlist as WebVTT renditions; without them the media playlist serves directly.
    private fun playlistUrl(id: String, hasSubs: Boolean) =
        "/api/v1/remux/$id/${if (hasSubs) "master.m3u8" else "main.m3u8"}"

    // ---- on-demand segments ----

    // Always called under launchLock (from ensureReaching), so it can't race evict.
    private fun launch(session: RemuxSession, startNumber: Int) {
        if (sessions[session.id] !== session) return // evicted; don't spawn an orphan
        killProcess(session)
        Files.createDirectories(session.dir)
        // Reserve the provider slot (this file's connection, shared with any other viewer of
        // it, evicting background downloads if need be); the callback stops this session if
        // we're ever bumped.
        if (!connections.tryOpenStream(
                session.id,
                session.providerKey,
                session.shareKey,
                session.connectionLimit,
                { stopReading(session) },
            )
        ) {
            throw ConnectionLimitException(session.connectionLimit)
        }
        val process = processRunner.start(
            MediaProcessRequest(
                command = commands.build(session, startNumber),
                // Bare fMP4 init filenames land in the session directory on every OS.
                workingDirectory = session.dir,
                appendStderrFile = session.logFile,
            )
        )
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
    private fun writtenThrough(session: RemuxSession): Int {
        if (session.startNumber < 0) return -1
        var n = maxOf(session.startNumber, session.writtenHead + 1)
        while (Files.exists(session.segmentFile(n))) n++
        session.writtenHead = n - 1
        return session.writtenHead
    }

    /** Decide whether the running ffmpeg can reach segment [n] soon, or restart it there. */
    private fun ensureReaching(session: RemuxSession, n: Int) {
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

    /** Reach segment [n] and wait for [file]; if the read dies without producing it (a
     *  stale single-connection provider often drops the first open), start once more. */
    private suspend fun produce(session: RemuxSession, n: Int, file: Path) = withContext(Dispatchers.IO) {
        repeat(2) {
            ensureReaching(session, n)
            awaitFile(session, file)
            if (Files.exists(file)) return@withContext
            stopReading(session)
        }
    }

    /** Produce [file] unless the provider refuses the read, which is the client's cue to stop
     *  asking rather than to treat the gap as a decode failure. False when it answered 429. */
    private suspend fun produceOrRefuse(
        session: RemuxSession,
        n: Int,
        file: Path,
        call: ApplicationCall,
    ): Boolean {
        try {
            produce(session, n, file)
        } catch (error: ConnectionLimitException) {
            call.respond(
                HttpStatusCode.TooManyRequests,
                ApiErrorDto("provider_capacity", error.message ?: "Provider connection limit reached"),
            )
            return false
        }
        return true
    }

    /** Wait (bounded) for ffmpeg to write [file], failing fast if it dies first. */
    private suspend fun awaitFile(session: RemuxSession, file: Path) {
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
    private fun pruneBehind(session: RemuxSession, current: Int) {
        // A timeshift can't be re-produced (no -ss), so keep everything already streamed.
        if (session.timeshift) return
        var n = current - KEEP_BEHIND - 1
        while (n >= 0) {
            if (!Files.deleteIfExists(session.segmentFile(n))) break
            n--
        }
    }

    // ---- HTTP ----

    suspend fun playlist(id: String, call: ApplicationCall, mediaQuery: String = "") {
        val session = touched(id) ?: return notFound(call)
        val stored = withContext(Dispatchers.IO) { Files.readString(session.playlistFile) }
        respondPlaylist(call, RemuxPlaylists.withMediaQuery(stored, mediaQuery))
    }

    suspend fun master(id: String, call: ApplicationCall, mediaQuery: String = "") {
        val session = touched(id) ?: return notFound(call)
        respondPlaylist(call, RemuxPlaylists.master(session, mediaQuery))
    }

    suspend fun subtitlePlaylist(id: String, index: Int, call: ApplicationCall, mediaQuery: String = "") {
        val session = touched(id) ?: return notFound(call)
        respondPlaylist(call, RemuxPlaylists.subtitles(session, index, mediaQuery))
    }

    suspend fun initSegment(id: String, call: ApplicationCall) {
        val session = touched(id) ?: return notFound(call)
        connections.touch(id)
        val init = session.initFile
        // ffmpeg leaves init.mp4 empty until the first segment is written; wait for that,
        // not init's mere existence, or hls.js gets a 0-byte init and can't decode.
        if (!hasContent(init)) {
            val start = session.startNumber.coerceAtLeast(0)
            if (!produceOrRefuse(session, start, session.segmentFile(start), call)) return
        }
        if (!hasContent(init)) return failed(session, call)
        respondFile(call, init, ContentType.parse("video/mp4"))
    }

    suspend fun segment(id: String, n: Int, call: ApplicationCall) {
        val session = touched(id) ?: return notFound(call)
        connections.touch(id)
        val segment = session.segmentFile(n)
        if (!Files.exists(segment) && !produceOrRefuse(session, n, segment, call)) return
        if (!Files.exists(segment)) return failed(session, call)
        pruneBehind(session, n)
        respondFile(call, segment, ContentType.parse("video/iso.segment"))
    }

    /** One subtitle segment: the store's cues overlapping this video segment, timestamp-mapped. */
    suspend fun subtitleSegment(id: String, index: Int, n: Int, call: ApplicationCall) {
        val session = touched(id) ?: return notFound(call)
        val window = session.segmentWindow(n)
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
            val cues = subtitles.merge(session.url, index, fresh)
            buildString {
                append("WEBVTT\n").append(TIMESTAMP_MAP).append("\n\n")
                cues.filter { it.start < window.endInclusive && it.end > window.start }
                    .forEach { append(it.block).append("\n\n") }
            }
        }
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respondText(body, ContentType.parse("text/vtt"))
    }

    private fun touched(id: String): RemuxSession? =
        sessions[id]?.also { it.lastAccessMs = System.currentTimeMillis() }

    private fun hasContent(path: Path): Boolean = Files.exists(path) && Files.size(path) > 0L

    private suspend fun respondPlaylist(call: ApplicationCall, body: String) {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respondText(body, ContentType.parse("application/vnd.apple.mpegurl"))
    }

    /** Surface ffmpeg's last log line when it produced nothing, instead of a silent 404. */
    private suspend fun failed(session: RemuxSession, call: ApplicationCall) {
        val tail = withContext(Dispatchers.IO) { lastLogLine(session) }?.takeIf { it.isNotBlank() }
        log.warn("remux {}: ffmpeg produced no output{}", session.id, tail?.let { " - $it" } ?: "")
        call.respondText(tail ?: "ffmpeg produced no output", ContentType.Text.Plain, HttpStatusCode.BadGateway)
    }

    private suspend fun respondFile(call: ApplicationCall, path: Path, type: ContentType) {
        if (!Files.exists(path)) return notFound(call)
        call.respondBytes(withContext(Dispatchers.IO) { Files.readAllBytes(path) }, type)
    }

    private suspend fun notFound(call: ApplicationCall) =
        call.respondText("Not found", ContentType.Text.Plain, HttpStatusCode.NotFound)
}
