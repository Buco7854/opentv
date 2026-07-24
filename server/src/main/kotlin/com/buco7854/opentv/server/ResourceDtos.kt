package com.buco7854.opentv.server

import com.buco7854.opentv.core.meta.decodeCast
import com.buco7854.opentv.core.meta.encodeCast
import com.buco7854.opentv.core.model.Channel
import com.buco7854.opentv.core.model.Download
import com.buco7854.opentv.core.model.Favorite
import com.buco7854.opentv.core.model.GroupCount
import com.buco7854.opentv.core.model.Metadata
import com.buco7854.opentv.core.model.Programme
import com.buco7854.opentv.core.model.ResumePoint
import com.buco7854.opentv.core.model.SeriesGroup
import com.buco7854.opentv.core.model.XtreamSeries
import com.buco7854.opentv.core.repo.GuideEntry
import com.buco7854.opentv.core.xtream.AccountInfo
import kotlinx.serialization.Serializable

@Serializable
data class ChannelDto(
    val id: Long,
    val playlistId: Long,
    val name: String,
    val url: String,
    val logo: String?,
    val groupTitle: String,
    val tvgId: String?,
    val kind: Int,
    val seriesKey: String?,
    val season: Int?,
    val episode: Int?,
    val position: Int,
    val xtreamStreamId: Long?,
    val catchupDays: Int,
    val catchupSource: String?,
    val description: String?,
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
    val sourceId: Long?,
    val fetchedAtMs: Long,
)

@Serializable
data class FavoriteDto(
    val playlistId: Long,
    val key: String,
    val kind: Int,
    val addedMs: Long,
)

@Serializable
data class ResumePointDto(
    val url: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedMs: Long = 0,
)

@Serializable
data class DownloadDto(
    val id: Long,
    val title: String,
    val url: String,
    val filePath: String,
    val status: Int,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val error: String?,
    val createdMs: Long,
)

@Serializable
data class GroupCountDto(val groupTitle: String, val count: Int)

@Serializable
data class SeriesGroupDto(
    val seriesKey: String,
    val count: Int,
    val logo: String?,
    val groupTitle: String,
)

@Serializable
data class XtreamSeriesDto(
    val playlistId: Long,
    val seriesId: Long,
    val name: String,
    val categoryName: String,
    val cover: String?,
    val plot: String?,
    val castNames: String?,
    val genre: String?,
    val rating: Double?,
    val episodesFetchedAtMs: Long,
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
    val username: String?,
    val isTrial: Boolean,
    val createdAtMs: Long?,
    val timezone: String?,
)

internal fun Channel.toDto(cipher: StreamCipher) = ChannelDto(
    id, playlistId, name, cipher.encrypt(url), cipher.encryptOrNull(logo), groupTitle,
    tvgId, kind, seriesKey, season, episode, position, xtreamStreamId, catchupDays,
    catchupSource, description, durationSecs, airDate,
)

internal fun Metadata?.toDto(cipher: StreamCipher) = (this ?: Metadata(cacheKey = "", fetchedAtMs = 0)).let {
    MetadataDto(
        it.cacheKey,
        it.title,
        it.year,
        it.overview,
        it.rating,
        it.castNames,
        it.castJson?.let { json ->
            encodeCast(decodeCast(json).map { member ->
                member.copy(photo = cipher.encryptOrNull(member.photo))
            })
        },
        cipher.encryptOrNull(it.posterUrl),
        it.infoLine,
        it.sourceId,
        it.fetchedAtMs,
    )
}

internal fun Favorite.toDto(cipher: StreamCipher) = FavoriteDto(
    playlistId = playlistId,
    key = if (kind == com.buco7854.opentv.core.model.ChannelKind.SERIES) key else cipher.encrypt(key),
    kind = kind,
    addedMs = addedMs,
)

internal fun ResumePoint.toDto(cipher: StreamCipher) =
    ResumePointDto(cipher.encrypt(url), positionMs, durationMs, updatedMs)

internal fun Download.toDto(cipher: StreamCipher) =
    DownloadDto(id, title, cipher.encrypt(url), filePath, status, totalBytes, downloadedBytes, error, createdMs)

internal fun GroupCount.toDto() = GroupCountDto(groupTitle, count)
internal fun SeriesGroup.toDto(cipher: StreamCipher) =
    SeriesGroupDto(seriesKey, count, cipher.encryptOrNull(logo), groupTitle)

internal fun XtreamSeries.toDto(cipher: StreamCipher) = XtreamSeriesDto(
    playlistId, seriesId, name, categoryName, cipher.encryptOrNull(cover), plot,
    castNames, genre, rating, episodesFetchedAtMs,
)

internal fun Programme.toDto() = ProgrammeDto(id, playlistId, tvgId, title, description, startMs, endMs)
internal fun GuideEntry.toDto() = GuideEntryDto(title, description, startMs, endMs, replayable)
internal fun AccountInfo.toDto() = AccountInfoDto(
    activeConnections, maxConnections, status, expiresAtMs, username, isTrial, createdAtMs, timezone,
)
