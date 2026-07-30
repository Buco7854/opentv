package com.buco7854.opentv.contract

import kotlinx.serialization.Serializable

@Serializable
data class ChannelDto(
    val contentId: String,
    val id: Long,
    val playlistId: Long,
    val name: String,
    val logo: String?,
    val groupTitle: String,
    val tvgId: String?,
    val kind: Int,
    val seriesKey: String?,
    val season: Int?,
    val episode: Int?,
    val position: Int,
    val xtreamStreamId: String?,
    val catchupDays: Int,
    /** Whether catch-up is available without exposing its provider URL template. */
    val hasCatchup: Boolean,
    val description: String?,
    val durationSecs: Int?,
    val airDate: String?,
)

/** Compact channel shape used only by category listings. */
@Serializable
data class ChannelListItemDto(
    val contentId: String,
    val id: Long,
    val name: String,
    val logo: String?,
    val tvgId: String?,
    val kind: Int,
    val xtreamStreamId: String?,
    val catchupDays: Int,
    /** Whether catch-up is available without exposing its provider URL template. */
    val hasCatchup: Boolean,
)

/** Compact episode shape used by the paged episode listing. */
@Serializable
data class EpisodeListItemDto(
    val contentId: String,
    val id: Long,
    val playlistId: Long,
    val name: String,
    val logo: String?,
    val groupTitle: String,
    val kind: Int,
    val seriesKey: String?,
    val season: Int?,
    val episode: Int?,
    val durationSecs: Int?,
    val airDate: String?,
)

@Serializable
data class MetadataDto(
    val cacheKey: String,
    val title: String?,
    val year: String?,
    val overview: String?,
    val rating: Double?,
    val castNames: String?,
    val castJson: String?,
    val posterUrl: String?,
    val infoLine: String?,
    val sourceId: String?,
    val fetchedAtMs: Long,
)

@Serializable
data class FavoriteDto(
    val contentId: String,
    val playlistId: Long,
    val key: String,
    val kind: Int,
    val addedMs: Long,
)

@Serializable
data class ResumePointDto(
    val contentId: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedMs: Long = 0,
)

@Serializable
data class DownloadDto(
    val id: String,
    val contentId: String,
    val title: String,
    val status: String,
    val active: Boolean,
    val suspended: Boolean,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val error: String?,
    val createdMs: Long,
    val fileToken: String? = null,
    val fileTokenExpiresAtMs: Long? = null,
)

@Serializable
data class AdminDownloadDto(
    val userId: String,
    val userDownloadId: String,
    val blobId: String,
    val contentId: String,
    val title: String,
    val status: String,
    val active: Boolean,
    val suspended: Boolean,
    val totalBytes: Long,
    val downloadedBytes: Long,
)

@Serializable data class AdminBlobCancellationDto(val affectedUserIds: List<String>)

@Serializable
data class GroupCountDto(val groupTitle: String, val count: Int)

@Serializable
data class SeriesGroupDto(
    val contentId: String,
    val seriesKey: String,
    val count: Int,
    val logo: String?,
    val groupTitle: String,
)

@Serializable
data class XtreamSeriesDto(
    val contentId: String,
    val playlistId: Long,
    val seriesId: String,
    val name: String,
    val categoryName: String,
    val cover: String?,
    val plot: String?,
    val castNames: String?,
    val genre: String?,
    val rating: Double?,
    val episodesFetchedAtMs: Long,
)

/** Native series category rows do not carry detail-only plot and cast fields. */
@Serializable
data class XtreamSeriesListItemDto(
    val contentId: String,
    val seriesId: String,
    val name: String,
    val cover: String?,
    val genre: String?,
    val rating: Double?,
)

@Serializable
data class ProgrammeDto(
    val id: Long,
    val playlistId: Long,
    val tvgId: String,
    val title: String,
    val description: String?,
    val startMs: Long,
    val endMs: Long,
)

@Serializable
data class GuideEntryDto(
    val title: String,
    val description: String?,
    val startMs: Long,
    val endMs: Long,
    val replayable: Boolean,
)

@Serializable
data class AccountInfoDto(
    val activeConnections: Int,
    val maxConnections: Int,
    val status: String,
    val expiresAtMs: Long?,
    val isTrial: Boolean,
    val createdAtMs: Long?,
    val timezone: String?,
    val fetchedAtMs: Long,
    val stale: Boolean,
)
