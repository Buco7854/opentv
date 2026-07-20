package com.buco7854.opentv.data

import com.buco7854.opentv.core.model.Channel
import com.buco7854.opentv.core.model.Download
import com.buco7854.opentv.core.model.Favorite
import com.buco7854.opentv.core.model.GroupOverride
import com.buco7854.opentv.core.model.Metadata
import com.buco7854.opentv.core.model.Playlist
import com.buco7854.opentv.core.model.Programme
import com.buco7854.opentv.core.model.ResumePoint
import com.buco7854.opentv.core.model.XtreamSeries
import com.buco7854.opentv.data.db.ChannelRow
import com.buco7854.opentv.data.db.DownloadRow
import com.buco7854.opentv.data.db.FavoriteRow
import com.buco7854.opentv.data.db.GroupOverrideRow
import com.buco7854.opentv.data.db.MetadataRow
import com.buco7854.opentv.data.db.PlaylistRow
import com.buco7854.opentv.data.db.ProgrammeRow
import com.buco7854.opentv.data.db.ResumePointRow
import com.buco7854.opentv.data.db.XtreamSeriesRow

internal fun PlaylistRow.toModel() = Playlist(
    id, name, url, epgUrl, etag, lastModified, lastRefreshedMs,
    epgEtag, epgLastModified, epgLastRefreshedMs,
    xtreamBase, xtreamUser, xtreamPass, channelCount,
)

internal fun Playlist.toRow() = PlaylistRow(
    id, name, url, epgUrl, etag, lastModified, lastRefreshedMs,
    epgEtag, epgLastModified, epgLastRefreshedMs,
    xtreamBase, xtreamUser, xtreamPass, channelCount,
)

internal fun ChannelRow.toModel() = Channel(
    id, playlistId, name, url, logo, groupTitle, tvgId, kind, seriesKey,
    season, episode, position, xtreamStreamId, catchupDays, catchupSource,
    description, durationSecs, airDate,
)

internal fun Channel.toRow() = ChannelRow(
    id, playlistId, name, url, logo, groupTitle, tvgId, kind, seriesKey,
    season, episode, position, xtreamStreamId, catchupDays, catchupSource,
    description, durationSecs, airDate,
)

internal fun XtreamSeriesRow.toModel() = XtreamSeries(
    playlistId, seriesId, name, categoryName, cover, plot, castNames, genre, rating, episodesFetchedAtMs,
)

internal fun XtreamSeries.toRow() = XtreamSeriesRow(
    playlistId, seriesId, name, categoryName, cover, plot, castNames, genre, rating, episodesFetchedAtMs,
)

internal fun ProgrammeRow.toModel() = Programme(id, playlistId, tvgId, title, description, startMs, endMs)
internal fun Programme.toRow() = ProgrammeRow(id, playlistId, tvgId, title, description, startMs, endMs)

internal fun GroupOverrideRow.toModel() = GroupOverride(playlistId, groupTitle, kind)
internal fun GroupOverride.toRow() = GroupOverrideRow(playlistId, groupTitle, kind)

internal fun FavoriteRow.toModel() = Favorite(playlistId, key, kind, addedMs)
internal fun Favorite.toRow() = FavoriteRow(playlistId, key, kind, addedMs)

internal fun ResumePointRow.toModel() = ResumePoint(url, positionMs, durationMs, updatedMs)
internal fun ResumePoint.toRow() = ResumePointRow(url, positionMs, durationMs, updatedMs)

internal fun MetadataRow.toModel() = Metadata(
    cacheKey, title, year, overview, rating, castNames, castJson, posterUrl, infoLine, sourceId, fetchedAtMs,
)

internal fun Metadata.toRow() = MetadataRow(
    cacheKey, title, year, overview, rating, castNames, castJson, posterUrl, infoLine, sourceId, fetchedAtMs,
)

internal fun DownloadRow.toModel() = Download(
    id, title, url, filePath, status, totalBytes, downloadedBytes, error, createdMs,
)

internal fun Download.toRow() = DownloadRow(
    id, title, url, filePath, status, totalBytes, downloadedBytes, error, createdMs,
)
