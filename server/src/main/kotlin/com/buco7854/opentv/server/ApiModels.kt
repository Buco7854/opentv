package com.buco7854.opentv.server

import kotlinx.serialization.Serializable

@Serializable
data class MessageDto(val message: String)

@Serializable
data class ApiErrorDto(
    val code: String,
    val message: String,
    val field: String? = null,
)

@Serializable
data class WatchIntentRequest(
    val selfId: String,
    val contentKey: String,
    val source: String? = null,
    val playlistId: Long? = null,
)

@Serializable
data class WatchIntentPeer(val id: String, val name: String)

@Serializable
data class WatchIntentResponse(
    val sameContent: List<WatchIntentPeer>,
    val full: Boolean,
    val limit: Int,
)

@Serializable
data class JoinRequestBody(val peerId: String, val peerName: String, val contentKey: String = "")

@Serializable
data class JoinAnswerBody(
    val peerId: String,
    val hostName: String,
    val contentKey: String = "",
    val accept: Boolean,
)

@Serializable data class RequestControlBody(val peerName: String)
@Serializable data class GrantControlBody(val peerId: String, val grant: Boolean)
@Serializable data class KickBody(val targetId: String)
@Serializable data class SetControlBody(val targetId: String, val grant: Boolean)
@Serializable data class RoomAudioBody(val audioIndex: Int)

@Serializable
data class ClientFrameDto(
    val type: String,
    val heartbeat: SessionHeartbeatDto? = null,
    val sync: SyncStateDto? = null,
)

@Serializable data class RemuxAvailableDto(val available: Boolean)

@Serializable
data class RemuxStartDto(
    val id: String,
    val playlistUrl: String,
    val duration: Double? = null,
    val audioTracks: List<String> = emptyList(),
    val subtitleTracks: List<String> = emptyList(),
    val nativeVideoCopy: Boolean = false,
    val audio: Int = 0,
)

@Serializable
data class PlaylistUpsertRequest(
    val mode: String,
    val name: String = "",
    val server: String = "",
    val username: String = "",
    val password: String = "",
    val url: String = "",
    val epgUrl: String = "",
    val content: String = "",
)

@Serializable
data class PlaylistDto(
    val id: Long,
    val name: String,
    val mode: String,
    val hasXtreamPanel: Boolean,
    val lastRefreshedMs: Long,
    val channelCount: Int,
)

@Serializable
data class PlaylistDetailDto(
    val playlist: PlaylistDto,
    val isXtreamNative: Boolean,
    val liveCount: Int,
    val movieCount: Int,
    val seriesCount: Int,
)

@Serializable
data class SeriesHitDto(
    val seriesKey: String,
    val count: Int,
    val logo: String? = null,
    val groupTitle: String,
    val xtreamSeriesId: Long? = null,
)

@Serializable
data class SearchResultsDto(
    val live: List<ChannelDto> = emptyList(),
    val movies: List<ChannelDto> = emptyList(),
    val series: List<SeriesHitDto> = emptyList(),
)

@Serializable
data class XtreamSeriesDetailDto(
    val series: XtreamSeriesDto,
    val episodes: List<ChannelDto>,
    val error: String? = null,
)

@Serializable
data class FavoritesResolvedDto(
    val live: List<ChannelDto> = emptyList(),
    val movies: List<ChannelDto> = emptyList(),
    val series: List<SeriesHitDto> = emptyList(),
)

@Serializable data class CatchupUrlDto(val url: String?)
@Serializable data class GroupKindRequest(val groupTitle: String, val kind: Int? = null)
@Serializable data class SettingsDto(val userAgent: String = "", val downloadLimit: Int = 1, val pageSize: Int = 50)
@Serializable data class EnqueueDownloadRequest(val channelId: Long)
@Serializable data class FavoriteRequest(val key: String, val kind: Int)
