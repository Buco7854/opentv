package com.buco7854.opentv.core.repo

import com.buco7854.opentv.core.log.CoreLog
import com.buco7854.opentv.core.log.rethrowCancellation
import com.buco7854.opentv.core.meta.ITunesApi
import com.buco7854.opentv.core.meta.TitleCleaner
import com.buco7854.opentv.core.meta.TvMazeApi
import com.buco7854.opentv.core.meta.encodeCast
import com.buco7854.opentv.core.model.Metadata
import com.buco7854.opentv.core.net.HttpFetcher
import com.buco7854.opentv.core.storage.MetadataStore
import com.buco7854.opentv.core.util.nowMs
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Metadata enrichment from keyless APIs (TVMaze for series, iTunes for movies).
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

    private val tvMaze = TvMazeApi(http)
    private val iTunes = ITunesApi(http)
    private val mutex = Mutex()

    suspend fun forTitle(isSeries: Boolean, rawName: String): Metadata? {
        val (title, year) = TitleCleaner.clean(rawName)
        if (title.isBlank()) return null
        val cacheKey = listOf(
            if (isSeries) "tv" else "movie",
            title.lowercase(),
            year ?: "",
        ).joinToString(":")

        mutex.withLock {
            val now = nowMs()
            store.get(cacheKey)
                ?.takeIf { now - it.fetchedAtMs < CACHE_MS }
                ?.let { return it.takeIf { m -> m.overview != null || m.castNames != null } }

            return try {
                val info = if (isSeries) tvMaze.fetch(title) else iTunes.fetch(title, year)
                val entity = Metadata(
                    cacheKey = cacheKey,
                    title = info?.title,
                    year = info?.year ?: year,
                    overview = info?.overview,
                    rating = info?.rating,
                    castNames = info?.credits,
                    castJson = info?.castList?.takeIf { it.isNotEmpty() }?.let { encodeCast(it) },
                    posterUrl = info?.posterUrl,
                    infoLine = info?.infoLine,
                    sourceId = info?.sourceId,
                    fetchedAtMs = now,
                )
                store.upsert(entity)
                entity.takeIf { info != null }
            } catch (e: Exception) {
                e.rethrowCancellation()
                log.log("Metadata lookup", e)
                store.get(cacheKey)
                    ?.takeIf { it.overview != null || it.castNames != null }
            }
        }
    }

    /** Per-episode details for an M3U series via TVMaze; cached (misses too). */
    suspend fun episodeInfo(seriesRawName: String, season: Int, episode: Int): Metadata? {
        val showId = forTitle(isSeries = true, rawName = seriesRawName)?.sourceId
            ?: return null
        val cacheKey = "tvep:$showId:$season:$episode"
        mutex.withLock {
            val now = nowMs()
            store.get(cacheKey)
                ?.takeIf { now - it.fetchedAtMs < CACHE_MS }
                ?.let { return it.takeIf { m -> m.overview != null || m.posterUrl != null } }
            return try {
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
                entity.takeIf { info != null }
            } catch (e: Exception) {
                e.rethrowCancellation()
                log.log("Episode details", e)
                null
            }
        }
    }
}
