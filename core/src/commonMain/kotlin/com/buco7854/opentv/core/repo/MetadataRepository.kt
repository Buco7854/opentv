package com.buco7854.opentv.core.repo

import com.buco7854.opentv.core.log.CoreLog
import com.buco7854.opentv.core.log.rethrowCancellation
import com.buco7854.opentv.core.meta.ITunesApi
import com.buco7854.opentv.core.meta.MetaInfo
import com.buco7854.opentv.core.meta.TitleCleaner
import com.buco7854.opentv.core.meta.TvMazeApi
import com.buco7854.opentv.core.meta.WikidataMovieCastApi
import com.buco7854.opentv.core.meta.decodeCast
import com.buco7854.opentv.core.meta.encodeCast
import com.buco7854.opentv.core.model.Metadata
import com.buco7854.opentv.core.net.HttpFetcher
import com.buco7854.opentv.core.storage.MetadataStore
import com.buco7854.opentv.core.util.nowMs
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Metadata enrichment from keyless APIs (TVMaze for series, iTunes plus Wikidata for movies).
 * Hard cache per cleaned title, including negative entries for unmatchable titles.
 */
class MetadataRepository(
    private val store: MetadataStore,
    http: HttpFetcher,
    private val log: CoreLog,
) {
    companion object {
        const val CACHE_MS = 30L * 24 * 60 * 60 * 1000
    }

    private fun isUseful(metadata: Metadata): Boolean = with(metadata) {
        overview != null || castNames != null || castJson != null || posterUrl != null ||
            rating != null || infoLine != null || sourceId != null
    }

    private val tvMaze = TvMazeApi(http)
    private val iTunes = ITunesApi(http)
    private val wikidata = WikidataMovieCastApi(http)

    /**
     * Striped so two viewers opening the same title still share one lookup, while unrelated
     * titles do not queue behind each other: the lock is held across the provider request, and
     * on a shared server a single slow API must not stall everyone else's metadata.
     */
    private val locks = List(32) { Mutex() }

    private suspend fun <T> withTitleLock(cacheKey: String, block: suspend () -> T): T =
        locks[(cacheKey.hashCode() and Int.MAX_VALUE) % locks.size].withLock { block() }

    suspend fun forTitle(isSeries: Boolean, rawName: String): Metadata? {
        val (title, year) = TitleCleaner.clean(rawName)
        if (title.isBlank()) return null
        val cacheKey = listOf(
            // movie2 invalidates the former iTunes-only rows once, so an existing install
            // receives cast photos immediately instead of waiting up to 30 days.
            if (isSeries) "tv" else "movie2",
            title.lowercase(),
            year ?: "",
        ).joinToString(":")

        return withTitleLock(cacheKey) {
            val now = nowMs()
            val cached = store.get(cacheKey)?.takeIf { now - it.fetchedAtMs < CACHE_MS }
            if (cached != null) {
                if (!isSeries && cached.castJson == null) {
                    val refreshed = addMovieCast(cached, cached.title ?: title, cached.year ?: year)
                    if (refreshed !== cached) store.upsert(refreshed)
                    return@withTitleLock refreshed.takeIf(::isUseful)
                }
                return@withTitleLock cached.takeIf(::isUseful)
            }

            try {
                val info = if (isSeries) tvMaze.fetch(title) else movieInfo(title, year)
                val entity = Metadata(
                    cacheKey = cacheKey,
                    title = info?.title,
                    year = info?.year ?: year,
                    overview = info?.overview,
                    rating = info?.rating,
                    castNames = info?.credits,
                    castJson = when {
                        info?.castLookupCompleted == true -> encodeCast(info.castList)
                        else -> info?.castList?.takeIf { it.isNotEmpty() }?.let { encodeCast(it) }
                    },
                    posterUrl = info?.posterUrl,
                    infoLine = info?.infoLine,
                    sourceId = info?.sourceId,
                    fetchedAtMs = now,
                )
                store.upsert(entity)
                entity.takeIf(::isUseful)
            } catch (e: Exception) {
                e.rethrowCancellation()
                log.log("Metadata lookup", e)
                store.get(cacheKey)?.takeIf(::isUseful)
            }
        }
    }

    /**
     * Merge provider-owned movie details with keyless enrichment. The provider remains
     * authoritative for synopsis/rating/credits; Wikidata wins only when it supplies more
     * actual cast photos than the panel's usual names-only list.
     */
    suspend fun movieForTitle(rawName: String, provider: Metadata? = null): Metadata? {
        val enrichment = forTitle(isSeries = false, rawName = rawName)
        if (provider == null) return enrichment
        if (enrichment == null) return provider
        val providerPhotos = decodeCast(provider.castJson).count { it.photo != null }
        val enrichmentPhotos = decodeCast(enrichment.castJson).count { it.photo != null }
        return provider.copy(
            title = provider.title ?: enrichment.title,
            year = provider.year ?: enrichment.year,
            overview = provider.overview ?: enrichment.overview,
            rating = provider.rating ?: enrichment.rating,
            castNames = provider.castNames ?: enrichment.castNames,
            castJson = if (enrichmentPhotos > providerPhotos) enrichment.castJson else provider.castJson,
            posterUrl = provider.posterUrl ?: enrichment.posterUrl,
            infoLine = provider.infoLine ?: enrichment.infoLine,
            sourceId = provider.sourceId ?: enrichment.sourceId,
            fetchedAtMs = maxOf(provider.fetchedAtMs, enrichment.fetchedAtMs),
        )
    }

    private suspend fun movieInfo(title: String, year: String?): MetaInfo? {
        // iTunes also supplies the canonical title/year used to disambiguate Wikidata. A
        // transport failure must escape to forTitle's stale-cache fallback rather than being
        // recorded as a legitimate metadata miss for 30 days.
        val base = iTunes.fetch(title, year)
        val resolvedTitle = base?.title ?: title
        val resolvedYear = base?.year ?: year
        val fallback = base ?: MetaInfo(
            title = resolvedTitle,
            year = resolvedYear,
            overview = null,
            rating = null,
            credits = null,
            posterUrl = null,
        )
        return try {
            fallback.copy(
                castList = wikidata.fetch(resolvedTitle, resolvedYear),
                castLookupCompleted = true,
            )
        } catch (error: Exception) {
            error.rethrowCancellation()
            log.log("Movie cast lookup", error)
            base
        }
    }

    /**
     * A transient Wikidata failure must not pin an otherwise useful iTunes row without cast
     * for the whole 30-day cache window. A completed empty lookup is encoded as `[]`, while a
     * null castJson remains retryable on the next detail open.
     */
    private suspend fun addMovieCast(cached: Metadata, title: String, year: String?): Metadata =
        try {
            cached.copy(castJson = encodeCast(wikidata.fetch(title, year)))
        } catch (error: Exception) {
            error.rethrowCancellation()
            log.log("Movie cast lookup", error)
            cached
        }

    /** Per-episode details for an M3U series via TVMaze; cached (misses too). */
    suspend fun episodeInfo(seriesRawName: String, season: Int, episode: Int): Metadata? {
        val showId = forTitle(isSeries = true, rawName = seriesRawName)?.sourceId
            ?: return null
        val cacheKey = "tvep:$showId:$season:$episode"
        return withTitleLock(cacheKey) {
            val now = nowMs()
            val cached = store.get(cacheKey)?.takeIf { now - it.fetchedAtMs < CACHE_MS }
            if (cached != null) return@withTitleLock cached.takeIf(::isUseful)
            try {
                val info = tvMaze.episode(showId, season, episode)
                val entity = Metadata(
                    cacheKey = cacheKey,
                    title = info?.title,
                    year = info?.year, // full air date for episodes
                    overview = info?.overview,
                    rating = info?.rating,
                    posterUrl = info?.posterUrl,
                    infoLine = info?.infoLine,
                    fetchedAtMs = now,
                )
                store.upsert(entity)
                entity.takeIf(::isUseful)
            } catch (e: Exception) {
                e.rethrowCancellation()
                log.log("Episode details", e)
                null
            }
        }
    }
}
