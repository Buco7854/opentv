package com.buco7854.opentv.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

object ChannelKind {
    const val LIVE = 0
    const val MOVIE = 1
    const val SERIES = 2
}

object DownloadStatus {
    const val QUEUED = 0
    const val RUNNING = 1
    const val DONE = 2
    const val FAILED = 3
    const val CANCELLED = 4
    const val PAUSED = 5
}

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Remote URL, or null for playlists imported from a local file. */
    val url: String?,
    val epgUrl: String? = null,
    // HTTP validators so refreshes can use conditional requests (304 = no re-download).
    val etag: String? = null,
    val lastModified: String? = null,
    val lastRefreshedMs: Long = 0,
    val epgEtag: String? = null,
    val epgLastModified: String? = null,
    val epgLastRefreshedMs: Long = 0,
    // Xtream-codes credentials auto-detected from the playlist URL (for connection monitoring).
    val xtreamBase: String? = null,
    val xtreamUser: String? = null,
    val xtreamPass: String? = null,
    val channelCount: Int = 0,
)

@Entity(
    tableName = "channels",
    indices = [
        Index("playlistId"),
        Index(value = ["playlistId", "kind", "groupTitle"]),
        Index(value = ["playlistId", "seriesKey"]),
    ]
)
data class ChannelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val name: String,
    val url: String,
    val logo: String?,
    val groupTitle: String,
    val tvgId: String?,
    val kind: Int,
    /** Normalized series title used to group episodes together. */
    val seriesKey: String?,
    val season: Int?,
    val episode: Int?,
    /** Original position in the M3U file, preserved for stable ordering. */
    val position: Int,
    /** Xtream stream id when this entry came from the panel API (enables get_vod_info etc.). */
    val xtreamStreamId: Long? = null,
    /** Days of catch-up archive the provider keeps for this live channel; 0 = none. */
    val catchupDays: Int = 0,
    // Episode details when the source provides them (Xtream get_series_info).
    val description: String? = null,
    val durationSecs: Int? = null,
    val airDate: String? = null,
)

/**
 * A series from an Xtream panel's catalog (get_series). Episodes are fetched
 * lazily per series and cached as [ChannelEntity] rows with
 * seriesKey = "xs:{seriesId}", so the playlist refresh never has to make one
 * request per show.
 */
@Entity(tableName = "xtream_series", primaryKeys = ["playlistId", "seriesId"])
data class XtreamSeriesEntity(
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
)

@Entity(
    tableName = "programmes",
    indices = [
        Index("playlistId"),
        Index(value = ["playlistId", "tvgId", "startMs"]),
    ]
)
data class ProgrammeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val tvgId: String,
    val title: String,
    val description: String?,
    val startMs: Long,
    val endMs: Long,
)

/**
 * User correction for a misclassified M3U category: "everything in this
 * group-title is actually LIVE/MOVIE/SERIES". Applied at every refresh, so it
 * permanently overrides the heuristics for messy providers.
 */
@Entity(tableName = "group_overrides", primaryKeys = ["playlistId", "groupTitle"])
data class GroupOverrideEntity(
    val playlistId: Long,
    val groupTitle: String,
    val kind: Int,
)

/**
 * A favorite, keyed by stable identity so it survives playlist refreshes
 * (channels are wiped and re-inserted on refresh, urls and series ids are
 * not). Key is the stream url for live/movies, the seriesKey for M3U series,
 * or "x:{seriesId}" for Xtream catalog series.
 */
@Entity(tableName = "favorites", primaryKeys = ["playlistId", "key"])
data class FavoriteEntity(
    val playlistId: Long,
    val key: String,
    val kind: Int,
    val addedMs: Long = System.currentTimeMillis(),
)

/**
 * Cached TMDB enrichment (synopsis, rating, cast) for a cleaned title.
 * Negative lookups are cached too (all-null fields) so unmatchable titles
 * don't generate a TMDB request on every detail-page open.
 */
@Entity(tableName = "metadata")
data class MetadataEntity(
    @PrimaryKey val cacheKey: String,
    val title: String? = null,
    val year: String? = null,
    val overview: String? = null,
    val rating: Double? = null,
    val castNames: String? = null,
    /** JSON cast list with photo urls (see data/meta/Cast.kt). */
    val castJson: String? = null,
    val posterUrl: String? = null,
    /** Extra facts line, " · " separated: genres, runtime, status, network/rated. */
    val infoLine: String? = null,
    /** Source-side id (TVMaze show id) enabling per-episode lookups. */
    val sourceId: Long? = null,
    val fetchedAtMs: Long,
)

@Entity(tableName = "downloads", indices = [Index("url")])
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val url: String,
    val filePath: String,
    val status: Int = DownloadStatus.QUEUED,
    val totalBytes: Long = 0,
    val downloadedBytes: Long = 0,
    val error: String? = null,
    val createdMs: Long = System.currentTimeMillis(),
)
