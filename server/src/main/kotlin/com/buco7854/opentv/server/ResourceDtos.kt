package com.buco7854.opentv.server

import com.buco7854.opentv.contract.AccountInfoDto
import com.buco7854.opentv.contract.ChannelDto
import com.buco7854.opentv.contract.ChannelListItemDto
import com.buco7854.opentv.contract.EpisodeListItemDto
import com.buco7854.opentv.contract.GroupCountDto
import com.buco7854.opentv.contract.GuideEntryDto
import com.buco7854.opentv.contract.MetadataDto
import com.buco7854.opentv.contract.ProgrammeDto
import com.buco7854.opentv.contract.SeriesGroupDto
import com.buco7854.opentv.contract.XtreamSeriesDto
import com.buco7854.opentv.contract.XtreamSeriesListItemDto
import com.buco7854.opentv.core.meta.decodeCast
import com.buco7854.opentv.core.meta.encodeCast
import com.buco7854.opentv.core.model.Channel
import com.buco7854.opentv.core.model.GroupCount
import com.buco7854.opentv.core.model.Metadata
import com.buco7854.opentv.core.model.Programme
import com.buco7854.opentv.core.model.SeriesGroup
import com.buco7854.opentv.core.model.XtreamSeries
import com.buco7854.opentv.core.repo.GuideEntry
import com.buco7854.opentv.core.storage.ChannelListing
import com.buco7854.opentv.core.storage.XtreamSeriesListing
import com.buco7854.opentv.core.xtream.AccountInfo

internal fun Channel.toDto(cipher: StreamCipher, contentId: String, imageUserId: String) = ChannelDto(
    contentId, id, playlistId, name, cipher.encryptOrNull(logo, imageUserId, playlistId), groupTitle,
    tvgId, kind, seriesKey, season, episode, position, xtreamStreamId?.toString(), catchupDays,
    catchupSource != null || catchupDays > 0, description, durationSecs, airDate,
)

internal fun ChannelListing.toChannelListItemDto(
    cipher: StreamCipher,
    contentId: String,
    imageUserId: String,
) = ChannelListItemDto(
    contentId,
    id,
    name,
    cipher.encryptOrNull(logo, imageUserId, playlistId),
    tvgId,
    kind,
    xtreamStreamId?.toString(),
    catchupDays,
    catchupSource != null || catchupDays > 0,
)

internal fun ChannelListing.toEpisodeListItemDto(
    cipher: StreamCipher,
    contentId: String,
    imageUserId: String,
) = EpisodeListItemDto(
    contentId,
    id,
    playlistId,
    name,
    cipher.encryptOrNull(logo, imageUserId, playlistId),
    groupTitle,
    kind,
    seriesKey,
    season,
    episode,
    durationSecs,
    airDate,
)

internal fun Metadata?.toDto(
    cipher: StreamCipher,
    imageUserId: String,
    playlistId: Long? = null,
) =
    (this ?: Metadata(cacheKey = "", fetchedAtMs = 0)).let {
        MetadataDto(
            it.cacheKey,
            it.title,
            it.year,
            it.overview,
            it.rating,
            it.castNames,
            it.castJson?.let { json ->
                encodeCast(decodeCast(json).map { member ->
                    member.copy(photo = cipher.encryptOrNull(member.photo, imageUserId, playlistId))
                })
            },
            cipher.encryptOrNull(it.posterUrl, imageUserId, playlistId),
            it.infoLine,
            it.sourceId?.toString(),
            it.fetchedAtMs,
        )
    }

internal fun GroupCount.toDto() = GroupCountDto(groupTitle, count)
internal fun SeriesGroup.toDto(
    cipher: StreamCipher,
    contentId: String,
    imageUserId: String,
    playlistId: Long,
) = SeriesGroupDto(
    contentId,
    seriesKey,
    count,
    cipher.encryptOrNull(logo, imageUserId, playlistId),
    groupTitle,
)

internal fun XtreamSeries.toDto(cipher: StreamCipher, contentId: String, imageUserId: String) =
    XtreamSeriesDto(
        contentId, playlistId, seriesId.toString(), name, categoryName,
        cipher.encryptOrNull(cover, imageUserId, playlistId), plot,
        castNames, genre, rating, episodesFetchedAtMs,
    )

internal fun XtreamSeriesListing.toListItemDto(
    cipher: StreamCipher,
    contentId: String,
    imageUserId: String,
) = XtreamSeriesListItemDto(
    contentId,
    seriesId.toString(),
    name,
    cipher.encryptOrNull(cover, imageUserId, playlistId),
    genre,
    rating,
)

internal fun Programme.toDto() = ProgrammeDto(id, playlistId, tvgId, title, description, startMs, endMs)
internal fun GuideEntry.toDto() = GuideEntryDto(title, description, startMs, endMs, replayable)
internal fun AccountInfo.toDto(fetchedAtMs: Long, stale: Boolean) = AccountInfoDto(
    activeConnections, maxConnections, status, expiresAtMs, isTrial, createdAtMs, timezone,
    fetchedAtMs, stale,
)
