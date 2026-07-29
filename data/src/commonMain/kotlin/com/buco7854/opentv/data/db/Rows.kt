package com.buco7854.opentv.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// Room rows, 1:1 with core.model. Names match the historical schema to preserve installs.

@Entity(tableName = "playlists")
data class PlaylistRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val url: String?,
    val epgUrl: String? = null,
    val etag: String? = null,
    val lastModified: String? = null,
    val lastRefreshedMs: Long = 0,
    val epgEtag: String? = null,
    val epgLastModified: String? = null,
    val epgLastRefreshedMs: Long = 0,
    val xtreamBase: String? = null,
    val xtreamUser: String? = null,
    val xtreamPass: String? = null,
    val channelCount: Int = 0,
)

@Entity(
    tableName = "channels",
    indices = [
        Index("playlistId"),
        Index(value = ["playlistId", "kind", "groupTitle", "position"]),
        Index(value = ["playlistId", "kind", "groupTitle", "seriesKey"]),
        Index(value = ["playlistId", "seriesKey"]),
        Index(value = ["playlistId", "kind", "searchName", "position", "id"]),
    ]
)
data class ChannelRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
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
    val xtreamStreamId: Long? = null,
    val catchupDays: Int = 0,
    val catchupSource: String? = null,
    val description: String? = null,
    val durationSecs: Int? = null,
    val airDate: String? = null,
    @ColumnInfo(defaultValue = "''") val searchName: String = "",
)

@Entity(
    tableName = "xtream_series",
    primaryKeys = ["playlistId", "seriesId"],
    indices = [
        Index(value = ["playlistId", "searchName", "seriesId"]),
        Index(value = ["playlistId", "categoryName", "name", "seriesId"]),
    ],
)
data class XtreamSeriesRow(
    val playlistId: Long,
    val seriesId: Long,
    val name: String,
    val categoryName: String,
    val cover: String?,
    val plot: String?,
    val castNames: String?,
    val genre: String?,
    val rating: Double?,
    val episodesFetchedAtMs: Long = 0,
    @ColumnInfo(defaultValue = "''") val searchName: String = "",
)

@Entity(
    tableName = "programmes",
    indices = [
        Index("playlistId"),
        Index(value = ["playlistId", "tvgId", "startMs"], unique = true),
        // "What is on now" asks across every channel of a playlist, which the per-channel
        // index above cannot serve; without this it scans the eight days of guide the
        // playlist retains, once a minute while Live is open.
        Index(value = ["playlistId", "endMs", "startMs"]),
    ]
)
data class ProgrammeRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val tvgId: String,
    val title: String,
    val description: String?,
    val startMs: Long,
    val endMs: Long,
)

@Entity(tableName = "group_overrides", primaryKeys = ["playlistId", "groupTitle"])
data class GroupOverrideRow(
    val playlistId: Long,
    val groupTitle: String,
    val kind: Int,
)

@Entity(tableName = "resume_points")
data class ResumePointRow(
    @PrimaryKey val url: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedMs: Long,
)

@Entity(tableName = "favorites", primaryKeys = ["playlistId", "key"])
data class FavoriteRow(
    val playlistId: Long,
    val key: String,
    val kind: Int,
    val addedMs: Long,
)

@Entity(tableName = "metadata")
data class MetadataRow(
    @PrimaryKey val cacheKey: String,
    val title: String? = null,
    val year: String? = null,
    val overview: String? = null,
    val rating: Double? = null,
    val castNames: String? = null,
    val castJson: String? = null,
    val posterUrl: String? = null,
    val infoLine: String? = null,
    val sourceId: Long? = null,
    val fetchedAtMs: Long,
)

@Entity(tableName = "downloads", indices = [Index("url")])
data class DownloadRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val url: String,
    val filePath: String,
    val status: Int,
    val totalBytes: Long = 0,
    val downloadedBytes: Long = 0,
    val error: String? = null,
    val createdMs: Long,
    val hubSourceId: Long? = null,
    val contentId: String? = null,
    val serverDownloadId: String? = null,
)

@Entity(tableName = "hub_sources")
data class HubSourceRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val baseUrl: String,
    val userId: String? = null,
    val username: String? = null,
    val role: String? = null,
    val addedMs: Long,
    val lastSeenMs: Long? = null,
)
