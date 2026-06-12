package com.buco7854.opentv.data.repo

import com.buco7854.opentv.data.db.AppDatabase
import com.buco7854.opentv.data.db.MetadataEntity
import com.buco7854.opentv.data.meta.ITunesStore
import com.buco7854.opentv.data.meta.TitleCleaner
import com.buco7854.opentv.data.meta.TvMaze
import com.buco7854.opentv.diag.ErrorLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Metadata enrichment from keyless public APIs - TVMaze for series (synopsis,
 * rating, cast), the iTunes Search API for movies (synopsis, genre, director).
 * Hard cache: each cleaned title costs at most two requests every [CACHE_MS],
 * and misses are cached as negative entries so unmatchable provider titles
 * never trigger repeat lookups.
 */
class MetadataRepository(private val db: AppDatabase) {

    companion object {
        const val CACHE_MS = 30L * 24 * 60 * 60 * 1000
    }

    private val mutex = Mutex()

    suspend fun forTitle(isSeries: Boolean, rawName: String): MetadataEntity? =
        withContext(Dispatchers.IO) {
            val (title, year) = TitleCleaner.clean(rawName)
            if (title.isBlank()) return@withContext null
            val cacheKey = listOf(
                if (isSeries) "tv" else "movie",
                title.lowercase(Locale.ROOT),
                year ?: "",
            ).joinToString(":")

            mutex.withLock {
                val now = System.currentTimeMillis()
                db.metadataDao().get(cacheKey)
                    ?.takeIf { now - it.fetchedAtMs < CACHE_MS }
                    ?.let { return@withLock it.takeIf { m -> m.overview != null || m.castNames != null } }

                try {
                    val info = if (isSeries) TvMaze.fetch(title) else ITunesStore.fetch(title, year)
                    val entity = MetadataEntity(
                        cacheKey = cacheKey,
                        title = info?.title,
                        year = info?.year ?: year,
                        overview = info?.overview,
                        rating = info?.rating,
                        castNames = info?.credits,
                        posterUrl = info?.posterUrl,
                        fetchedAtMs = now,
                    )
                    db.metadataDao().upsert(entity)
                    entity.takeIf { info != null }
                } catch (e: Exception) {
                    ErrorLog.log("Metadata lookup", e)
                    db.metadataDao().get(cacheKey)
                        ?.takeIf { it.overview != null || it.castNames != null }
                }
            }
        }
}
