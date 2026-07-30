package com.buco7854.opentv.contract

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
data class WatchIntentPeer(val id: String, val name: String)

@Serializable
data class WatchIntentResponse(
    val sameContent: List<WatchIntentPeer>,
    val full: Boolean,
    val limit: Int,
)

@Serializable
data class JoinRequestBody(val peerId: String)

@Serializable
data class JoinAnswerBody(
    val requestId: String,
    val accept: Boolean,
)

@Serializable data class RequestControlBody(val requested: Boolean = true)
@Serializable data class GrantControlBody(val peerId: String, val grant: Boolean)
@Serializable data class KickBody(val targetId: String)
@Serializable data class SetControlBody(val targetId: String, val grant: Boolean)
@Serializable data class RoomAudioBody(val audioIndex: Int)
@Serializable data class ReadyBody(val generation: Long)

@Serializable
data class ClientCapabilitiesDto(
    val videoCodecs: List<String> = emptyList(),
    val audioCodecs: List<String> = emptyList(),
    val selectsTracksInBand: Boolean = false,
)

@Serializable
data class PlaybackCreateRequest(
    val contentId: String,
    val mode: String = "play",
    val catchupStartMs: Long? = null,
    val catchupDurationMs: Long? = null,
    val downloadId: String? = null,
    val capabilities: ClientCapabilitiesDto? = null,
)

@Serializable
data class PlaybackLeaseDto(
    val id: String,
    val contentId: String,
    val playlistId: Long,
    val mediaGrant: String,
    val mediaGrantExpiresAtMs: Long,
    val streamUrl: String? = null,
    /** Present only when the source is HLS and this server can share its untouched resources. */
    val sharedHlsUrl: String? = null,
    val relayUrl: String? = null,
    val transcodeUrl: String? = null,
    val remuxStartUrl: String,
    val downloadFileUrl: String? = null,
)

@Serializable
data class WebSocketAccessDto(val token: String, val expiresAtMs: Long)

@Serializable
data class MediaGrantDto(val token: String, val expiresAtMs: Long)

@Serializable
data class ServerInfoDto(
    val product: String = "opentv",
    val apiVersion: Int = 1,
    val version: String,
)

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

/**
 * Write-only playlist changes. Null or blank provider fields keep the stored value.
 * Provider fields intentionally have no matching value-bearing response DTO.
 */
@Serializable
data class PlaylistUpdateRequest(
    val name: String? = null,
    val server: String? = null,
    val username: String? = null,
    val password: String? = null,
    val url: String? = null,
    val epgUrl: String? = null,
    val content: String? = null,
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
data class PlaylistEditDto(
    val id: Long,
    val name: String,
    val mode: String,
    /** Fields the client should render. */
    val fields: List<String>,
    /** Hidden fields with a stored value. The value itself never leaves the server. */
    val storedFields: List<String>,
)

object PlaylistEditField {
    const val NAME = "NAME"
    const val SERVER = "SERVER"
    const val USERNAME = "USERNAME"
    const val PASSWORD = "PASSWORD"
    const val URL = "URL"
    const val EPG_URL = "EPG_URL"
    const val CONTENT = "CONTENT"
}

@Serializable
data class PlaylistRefreshResultDto(
    val playlist: PlaylistDto,
    val catalogChanged: Boolean,
    val epgStatus: String,
)

object PlaylistEpgRefreshStatus {
    const val SUCCEEDED = "SUCCEEDED"
    const val FAILED = "FAILED"
    const val NOT_CONFIGURED = "NOT_CONFIGURED"
}

@Serializable
data class PlaylistRefreshJobDto(
    val id: String,
    val status: String,
    val result: PlaylistRefreshResultDto? = null,
)

object PlaylistRefreshJobStatus {
    const val QUEUED = "QUEUED"
    const val RUNNING = "RUNNING"
    const val SUCCEEDED = "SUCCEEDED"
    const val FAILED = "FAILED"
}

@Serializable
data class PlaylistDeleteInfoDto(
    val id: Long,
    val name: String,
    val warning: String,
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
data class PlaylistCapabilitiesDto(
    val operations: List<PlaylistOperationCapabilityDto>,
)

@Serializable
data class PlaylistOperationCapabilityDto(
    val operation: String,
    val execution: String,
    /** Same-server web path. Hub clients resolve it against the connected hub. */
    val browserPath: String? = null,
)

object PlaylistOperation {
    const val REFRESH = "REFRESH"
    const val EDIT = "EDIT"
    const val DELETE = "DELETE"
    const val CLEAR_WATCH_PROGRESS = "CLEAR_WATCH_PROGRESS"
    const val CORRECT_CATEGORY_TYPE = "CORRECT_CATEGORY_TYPE"
    const val VIEW_PROVIDER_ACCOUNT = "VIEW_PROVIDER_ACCOUNT"
}

object PlaylistOperationExecution {
    const val IN_APP = "IN_APP"
    const val BROWSER = "BROWSER"
}

@Serializable
data class ChannelPageDto(
    val items: List<ChannelListItemDto>,
    val total: Int,
    val offset: Int,
    val limit: Int,
)

@Serializable
data class SeriesGroupPageDto(
    val items: List<SeriesGroupDto>,
    val total: Int,
    val offset: Int,
    val limit: Int,
)

@Serializable
data class XtreamSeriesPageDto(
    val items: List<XtreamSeriesListItemDto>,
    val total: Int,
    val offset: Int,
    val limit: Int,
)

@Serializable
data class EpisodePageDto(
    val items: List<EpisodeListItemDto>,
    val total: Int,
    val offset: Int,
    val limit: Int,
    val seasons: List<Int>,
    val seriesContentId: String?,
    val groupTitle: String? = null,
)

@Serializable
data class SeriesHitDto(
    val contentId: String,
    val seriesKey: String,
    val count: Int,
    val logo: String? = null,
    val groupTitle: String,
    val xtreamSeriesId: String? = null,
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

@Serializable data class GroupKindRequest(val groupTitle: String, val kind: Int? = null)
@Serializable data class SettingsDto(val userAgent: String = "", val downloadLimit: Int = 1, val pageSize: Int = 50)
@Serializable data class EnqueueDownloadRequest(val contentId: String)
@Serializable data class FavoriteRequest(val contentId: String)
