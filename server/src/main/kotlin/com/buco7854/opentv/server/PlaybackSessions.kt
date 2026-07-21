package com.buco7854.opentv.server

import com.buco7854.opentv.core.util.nowMs
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/** What a player reports about its current playback (client -> server). */
@Serializable
data class SessionHeartbeatDto(
    /** Stable per browser tab; identifies the session across heartbeats. */
    val id: String,
    val playlistId: Long? = null,
    val title: String = "",
    /** "live" | "movie" | "series" | "catchup" | "download". */
    val kind: String = "live",
    /** Encrypted logo token (as held by the client), for the card thumbnail. */
    val logo: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val paused: Boolean = false,
    val live: Boolean = false,
    /** Playback engine the client picked: "hls" | "mpegts" | "native" | "remux". */
    val engine: String = "native",
    /** Playing the server's own output (remux/download): the stream proxy is bypassed. */
    val direct: Boolean = false,
    /** Live audio the browser couldn't decode, rescued to AAC by the server. */
    val audioTranscoded: Boolean = false,
    /** ffmpeg is still probing the file; the copy-vs-transcode choice isn't made yet. */
    val preparing: Boolean = false,
    /** Set when engine == "remux"; joins to server-side ffmpeg diagnostics. */
    val remuxId: String? = null,
)

/** A remote-control command the admin queues for a viewer. */
@Serializable
data class SessionCommandDto(
    /** "pause" | "play" | "message". */
    val type: String,
    val text: String? = null,
)

@Serializable
data class HeartbeatResponseDto(val commands: List<SessionCommandDto> = emptyList())

/** ffmpeg pipeline facts for a remux session (server -> admin); labels are formatted client-side. */
@Serializable
data class RemuxDiagDto(
    val videoCodec: String,
    val transcodeVideo: Boolean,
    val videoEncoder: String,
    val nativeVideoCopy: Boolean,
    val audioCodec: String,
    val audioChannels: Int? = null,
    val audioLabel: String? = null,
    val subtitleCount: Int,
    val segmentCount: Int,
    val timeshift: Boolean,
    val providerKey: String,
    val connectionLimit: Int,
    val ffmpegRunning: Boolean,
    val durationSec: Double? = null,
    val lastLog: String? = null,
)

@Serializable
data class SessionStreamDto(
    val engine: String,
    val direct: Boolean,
    val audioTranscoded: Boolean,
    val preparing: Boolean,
    val remux: RemuxDiagDto? = null,
)

/** One active viewer (server -> admin dashboard). */
@Serializable
data class SessionDto(
    val id: String,
    val ip: String,
    val userAgent: String,
    val playlistName: String? = null,
    val title: String,
    val kind: String,
    val logo: String? = null,
    val positionMs: Long,
    val durationMs: Long,
    val paused: Boolean,
    val live: Boolean,
    val startedAtMs: Long,
    val lastSeenMs: Long,
    val stream: SessionStreamDto,
)

/**
 * In-memory registry of active web-client playback sessions. A player heartbeats
 * every few seconds; sessions with no recent heartbeat are dropped. Commands the
 * admin enqueues are delivered on the session's next heartbeat.
 *
 * Web sessions only: the Android app plays through the shared core layer, not this
 * server, so it never appears here.
 */
class PlaybackSessionRegistry {

    class Live(
        val id: String,
        @Volatile var ip: String,
        @Volatile var userAgent: String,
        @Volatile var state: SessionHeartbeatDto,
        val startedAtMs: Long,
        @Volatile var lastSeenMs: Long,
        val commands: ConcurrentLinkedQueue<SessionCommandDto> = ConcurrentLinkedQueue(),
    )

    private val sessions = ConcurrentHashMap<String, Live>()

    /** Upsert from a heartbeat and drain any commands queued for this session. */
    fun heartbeat(ip: String, userAgent: String, dto: SessionHeartbeatDto): List<SessionCommandDto> {
        val now = nowMs()
        val live = sessions.compute(dto.id) { _, existing ->
            existing?.apply { this.ip = ip; this.userAgent = userAgent; state = dto; lastSeenMs = now }
                ?: Live(dto.id, ip, userAgent, dto, now, now)
        }!!
        val drained = ArrayList<SessionCommandDto>()
        while (true) drained.add(live.commands.poll() ?: break)
        return drained
    }

    /** Queue a command for [id]; false when no such live session. */
    fun enqueue(id: String, command: SessionCommandDto): Boolean {
        val live = sessions[id] ?: return false
        live.commands.add(command)
        return true
    }

    fun remove(id: String) { sessions.remove(id) }

    /** Live sessions (stale ones pruned first), newest first. */
    fun active(): List<Live> {
        val cutoff = nowMs() - STALE_MS
        sessions.values.removeIf { it.lastSeenMs < cutoff }
        return sessions.values.sortedByDescending { it.startedAtMs }
    }

    companion object {
        /** Drop a session this long after its last heartbeat (client beats ~every 3s). */
        private const val STALE_MS = 12_000L
    }
}
