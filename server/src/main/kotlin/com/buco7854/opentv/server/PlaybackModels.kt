package com.buco7854.opentv.server

import kotlinx.serialization.Serializable

/** What a player reports about its current playback. */
@Serializable
data class SessionHeartbeatDto(
    val id: String,
    val playlistId: Long? = null,
    val title: String = "",
    val kind: String = "live",
    val logo: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val paused: Boolean = false,
    val live: Boolean = false,
    val engine: String = "native",
    val direct: Boolean = false,
    val audioTranscoded: Boolean = false,
    val preparing: Boolean = false,
    val remuxId: String? = null,
    val contentKey: String = "",
    val name: String = "",
)

@Serializable
data class SyncStateDto(
    val positionMs: Long,
    val paused: Boolean,
    val rate: Double = 1.0,
    val seek: Boolean = false,
)

@Serializable
data class RoomMemberDto(
    val id: String,
    val name: String,
    val host: Boolean,
    val controller: Boolean,
)

/** Discriminated server-to-client playback/watch-together event. */
@Serializable
data class SessionCommandDto(
    val type: String,
    val text: String? = null,
    val peerId: String? = null,
    val peerName: String? = null,
    val accepted: Boolean? = null,
    val quiet: Boolean = false,
    val sync: SyncStateDto? = null,
    val members: List<RoomMemberDto>? = null,
    val audioIndex: Int? = null,
)

@Serializable
data class HeartbeatResponseDto(val commands: List<SessionCommandDto> = emptyList())

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
    val roomId: String? = null,
    val roomSize: Int = 0,
)
