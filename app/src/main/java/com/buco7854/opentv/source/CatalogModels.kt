package com.buco7854.opentv.source

data class CatalogItem(
    val ref: ContentRef,
    val title: String,
    val imageUrl: String?,
    val kind: Int,
    val group: String?,
    val seriesKey: String? = null,
    val seriesId: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val durationSecs: Int? = null,
    val tvgId: String? = null,
    val count: Int? = null,
    val genre: String? = null,
    val rating: Double? = null,
    val airDate: String? = null,
    val catchupDays: Int = 0,
    val hasCatchup: Boolean = false,
    val hasGuide: Boolean = false,
    val progress: Float? = null,
    val nowAiring: CatalogProgramme? = null,
)

/**
 * A series episode count only when there is one.
 *
 * [CatalogItem.count] holds a real count for an M3U series, whose episodes are rows the
 * server can count, and zero for a panel series, whose episodes it would have to fetch
 * from the provider to know. A listing never contains an M3U series with no episodes,
 * so zero always means "not counted" rather than "counted, and there are none". Printing
 * it states as fact something nobody measured, and every panel series in a listing then
 * describes itself as having no episodes.
 */
internal fun seriesEpisodeCount(count: Int?): Int? = count?.takeIf { it > 0 }

data class Page<T>(
    val items: List<T>,
    val total: Int,
    val seasons: List<Int> = emptyList(),
)

data class CatalogGroup(
    val name: String,
    val count: Int,
)

data class CatalogSearchResult(
    val live: List<CatalogItem> = emptyList(),
    val movies: List<CatalogItem> = emptyList(),
    val series: List<CatalogItem> = emptyList(),
) {
    val isEmpty: Boolean get() = live.isEmpty() && movies.isEmpty() && series.isEmpty()
}

data class CatalogProgramme(
    val tvgId: String,
    val title: String,
    val description: String?,
    val startMs: Long,
    val endMs: Long,
)

data class CatalogGuideEntry(
    val title: String,
    val description: String?,
    val startMs: Long,
    val endMs: Long,
    val replayable: Boolean,
)

data class CatalogResumePoint(
    val ref: ContentRef,
    val positionMs: Long,
    val durationMs: Long,
    val updatedMs: Long,
) {
    val progress: Float?
        get() = durationMs.takeIf { it > 0 }
            ?.let { (positionMs.toFloat() / it).coerceIn(0f, 1f) }
}

data class CatalogDetail(
    val item: CatalogItem,
    val description: String? = null,
    val cast: String? = null,
)

data class SourceTraits(
    /** Display name of this playlist, when the gateway can obtain it. */
    val title: String? = null,
    val hasXtreamSeries: Boolean,
    val hasGuide: Boolean,
    val hasAccountPanel: Boolean,
    val favoritesAreServerSide: Boolean,
    val resumeIsServerSide: Boolean,
    val supportsRefresh: Boolean,
    val supportsSourceEditing: Boolean,
    val usesXtreamCredentials: Boolean,
    val usesM3uUrl: Boolean,
    val isFileImport: Boolean,
)

enum class PlaylistOperation {
    REFRESH,
    EDIT,
    DELETE,
    CLEAR_WATCH_PROGRESS,
    CORRECT_CATEGORY_TYPE,
    VIEW_PROVIDER_ACCOUNT,
}

sealed interface PlaylistOperationAvailability {
    data object InApp : PlaylistOperationAvailability
    data class Browser(val url: String) : PlaylistOperationAvailability
}

data class PlaylistCapabilities(
    val operations: Map<PlaylistOperation, PlaylistOperationAvailability>,
) {
    operator fun get(operation: PlaylistOperation): PlaylistOperationAvailability? =
        operations[operation]
}

enum class PlaylistEditMode {
    XTREAM,
    M3U_URL,
    FILE,
}

enum class PlaylistEditField {
    NAME,
    SERVER,
    USERNAME,
    PASSWORD,
    URL,
    EPG_URL,
    CONTENT,
}

/**
 * Provider values are deliberately absent. [storedFields] only tells a form that
 * leaving the corresponding input blank will keep an existing server-side value.
 */
data class PlaylistEditForm(
    val id: Long,
    val name: String,
    val mode: PlaylistEditMode,
    val fields: Set<PlaylistEditField>,
    val storedFields: Set<PlaylistEditField>,
)

data class PlaylistEditUpdate(
    val name: String? = null,
    val server: String? = null,
    val username: String? = null,
    val password: String? = null,
    val url: String? = null,
    val epgUrl: String? = null,
    val content: String? = null,
)

enum class PlaylistEpgRefreshOutcome {
    SUCCEEDED,
    FAILED,
    NOT_CONFIGURED,
}

data class PlaylistRefreshResult(
    val catalogChanged: Boolean,
    val epg: PlaylistEpgRefreshOutcome,
    val lastRefreshedMs: Long,
    val channelCount: Int,
)

sealed interface PlaylistRefreshProgress {
    data object Queued : PlaylistRefreshProgress
    data object Running : PlaylistRefreshProgress
    data class Finished(val result: PlaylistRefreshResult) : PlaylistRefreshProgress
}

class PlaylistRefreshFailedException :
    Exception("The server could not refresh the playlist")

data class PlaylistDeleteInfo(
    val id: Long,
    val name: String,
    /** Complete confirmation copy supplied by the authoritative server. */
    val warning: String,
)

data class ProviderAccountInfo(
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

sealed interface CatalogResult<out T> {
    data class Success<T>(val value: T) : CatalogResult<T>
    data object SignedOut : CatalogResult<Nothing>
    data object Unreachable : CatalogResult<Nothing>
    data class Failed(val cause: Throwable) : CatalogResult<Nothing>
}
