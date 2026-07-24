package com.buco7854.opentv.core.model

import com.buco7854.opentv.core.util.nowMs
// Domain models are storage- and transport-agnostic. Server JSON uses server-owned DTOs.

object DownloadStatus {
    const val QUEUED = 0
    const val RUNNING = 1
    const val DONE = 2
    const val FAILED = 3
    const val CANCELLED = 4
    const val PAUSED = 5
}

data class Playlist(
    val id: Long = 0,
    val name: String,
    /** Remote URL, or null for playlists imported from a local file. */
    val url: String?,
    val epgUrl: String? = null,
    // HTTP validators for conditional refresh (304 = no re-download).
    val etag: String? = null,
    val lastModified: String? = null,
    val lastRefreshedMs: Long = 0,
    val epgEtag: String? = null,
    val epgLastModified: String? = null,
    val epgLastRefreshedMs: Long = 0,
    // Xtream credentials auto-detected from the playlist URL (for connection monitoring).
    val xtreamBase: String? = null,
    val xtreamUser: String? = null,
    val xtreamPass: String? = null,
    val channelCount: Int = 0,
)

data class Channel(
    val id: Long = 0,
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
    /** M3U `catchup-source` template (placeholders resolved at play time); null = none. */
    val catchupSource: String? = null,
    // Episode details when the source provides them (Xtream get_series_info).
    val description: String? = null,
    val durationSecs: Int? = null,
    val airDate: String? = null,
)

/** True when this channel can offer catch-up replay. */
val Channel.hasCatchup: Boolean get() = catchupSource != null || catchupDays > 0

/** Xtream channels have a per-channel panel guide; M3U needs stored programme rows. */
fun Channel.hasGuide(guideIds: Set<String>): Boolean =
    xtreamStreamId != null || (tvgId != null && tvgId in guideIds)

/**
 * A series from an Xtream panel's catalog. Episodes are fetched lazily and
 * cached as [Channel] rows (seriesKey = "xs:{seriesId}"), so refresh never
 * costs one request per show.
 */
data class XtreamSeries(
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

data class Programme(
    val id: Long = 0,
    val playlistId: Long,
    val tvgId: String,
    val title: String,
    val description: String?,
    val startMs: Long,
    val endMs: Long,
)

/** User correction for a misclassified M3U category, reapplied every refresh to override the heuristics. */
data class GroupOverride(
    val playlistId: Long,
    val groupTitle: String,
    val kind: Int,
)

/**
 * A favorite, keyed by stable identity so it survives refreshes (which wipe
 * channel rows). Key = stream url for live/movies, seriesKey for M3U series,
 * "x:{seriesId}" for Xtream catalog series.
 */
data class Favorite(
    val playlistId: Long,
    val key: String,
    val kind: Int,
    val addedMs: Long = nowMs(),
)

/** Saved VOD playback position, keyed by stream URL; live streams are never stored. */
data class ResumePoint(
    val url: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedMs: Long,
)

/**
 * Cached enrichment (synopsis, rating, cast) for a cleaned title. Negative
 * lookups are cached too (all-null fields) so unmatchable titles don't re-fetch.
 */
data class Metadata(
    val cacheKey: String,
    val title: String? = null,
    val year: String? = null,
    val overview: String? = null,
    val rating: Double? = null,
    val castNames: String? = null,
    /** JSON cast list with photo urls (see core/meta/Cast.kt). */
    val castJson: String? = null,
    val posterUrl: String? = null,
    /** Extra facts line, " · " separated: genres, runtime, status, network/rated. */
    val infoLine: String? = null,
    /** Source-side id (TVMaze show id) enabling per-episode lookups. */
    val sourceId: Long? = null,
    val fetchedAtMs: Long,
)

data class Download(
    val id: Long = 0,
    val title: String,
    val url: String,
    val filePath: String,
    val status: Int = DownloadStatus.QUEUED,
    val totalBytes: Long = 0,
    val downloadedBytes: Long = 0,
    val error: String? = null,
    val createdMs: Long = nowMs(),
)

// Query projections shared by both UIs.

data class GroupCount(val groupTitle: String, val count: Int)

data class SeriesGroup(val seriesKey: String, val count: Int, val logo: String?, val groupTitle: String)

data class GroupHit(val groupTitle: String, val kind: Int, val count: Int)
