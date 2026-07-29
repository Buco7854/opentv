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

sealed interface CatalogResult<out T> {
    data class Success<T>(val value: T) : CatalogResult<T>
    data object SignedOut : CatalogResult<Nothing>
    data object Unreachable : CatalogResult<Nothing>
    data class Failed(val cause: Throwable) : CatalogResult<Nothing>
}
