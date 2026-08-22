package com.buco7854.opentv.core.repo

import com.buco7854.opentv.core.log.CoreLog
import com.buco7854.opentv.core.meta.castFromNames
import com.buco7854.opentv.core.meta.decodeCast
import com.buco7854.opentv.core.meta.encodeCast
import com.buco7854.opentv.core.model.Metadata
import com.buco7854.opentv.core.net.Urls
import com.buco7854.opentv.core.storage.MetadataStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MetadataRepositoryTest {
    @Test
    fun provider_movie_keeps_its_details_but_gains_wikidata_cast_photos() = runTest {
        val store = MemoryMetadataStore()
        var requests = 0
        val repository = MetadataRepository(
            store = store,
            http = { url ->
                requests++
                if (url.startsWith("https://itunes.apple.com/")) {
                    """{"results":[{"trackName":"A Film","releaseDate":"2024-01-01",
                      "artistName":"Director","primaryGenreName":"Drama"}]}"""
                } else {
                    wikidataResponse(url)
                }
            },
            log = CoreLog { _, _ -> },
        )
        val panel = Metadata(
            cacheKey = "panel",
            overview = "Panel synopsis",
            castNames = "Cast: Panel Actor · Director: Panel Director",
            castJson = encodeCast(castFromNames("Panel Actor")),
            fetchedAtMs = 1,
        )

        val merged = repository.movieForTitle("A Film (2024)", panel)!!
        assertEquals("Panel synopsis", merged.overview)
        assertEquals("Cast: Panel Actor · Director: Panel Director", merged.castNames)
        assertEquals("Photographed Actor", decodeCast(merged.castJson).single().name)
        assertEquals(5, requests)

        repository.movieForTitle("A Film (2024)", panel)
        assertEquals(5, requests, "the 30-day metadata cache was bypassed")
    }

    @Test
    fun transient_wikidata_failure_does_not_pin_a_movie_without_cast_for_30_days() = runTest {
        val store = MemoryMetadataStore()
        var wikidataFailed = false
        val repository = MetadataRepository(
            store = store,
            http = { url ->
                if (url.startsWith("https://itunes.apple.com/")) {
                    """{"results":[{"trackName":"A Film","releaseDate":"2024-01-01"}]}"""
                } else if (!wikidataFailed) {
                    wikidataFailed = true
                    error("temporary Wikidata failure")
                } else {
                    wikidataResponse(url)
                }
            },
            log = CoreLog { _, _ -> },
        )

        assertEquals(emptyList(), decodeCast(repository.forTitle(false, "A Film (2024)")?.castJson))
        assertEquals(
            "Photographed Actor",
            decodeCast(repository.forTitle(false, "A Film (2024)")?.castJson).single().name,
        )
    }

    @Test
    fun transient_itunes_failure_is_not_cached_as_a_metadata_miss() = runTest {
        var itunesRequests = 0
        val repository = MetadataRepository(
            store = MemoryMetadataStore(),
            http = { url ->
                if (url.startsWith("https://itunes.apple.com/")) {
                    itunesRequests++
                    if (itunesRequests == 1) error("temporary iTunes failure")
                    """{"results":[{"trackName":"A Film","releaseDate":"2024-01-01"}]}"""
                } else {
                    wikidataResponse(url)
                }
            },
            log = CoreLog { _, _ -> },
        )

        assertEquals(null, repository.forTitle(false, "A Film (2024)"))
        assertEquals(
            "Photographed Actor",
            decodeCast(repository.forTitle(false, "A Film (2024)")?.castJson).single().name,
        )
        assertEquals(2, itunesRequests)
    }

    private fun wikidataResponse(url: String): String {
        val parts = requireNotNull(Urls.parse(url))
        return when (parts.queryParameter("action")) {
            "wbsearchentities" -> """{"search":[{"id":"Q10"}]}"""
            "wbgetentities" -> when (parts.queryParameter("ids")) {
                "Q10" -> """{"entities":{"Q10":{
                  "labels":{"en":{"value":"A Film"}},
                  "claims":{
                    "P577":[{"mainsnak":{"datavalue":{"value":{"time":"+2024-01-01T00:00:00Z"}}}}],
                    "P161":[{"mainsnak":{"datavalue":{"value":{"id":"Q1"}}}}]
                  }
                }}}"""
                "Q1" -> """{"entities":{"Q1":{
                  "labels":{"en":{"value":"Photographed Actor"}},
                  "claims":{"P18":[{"mainsnak":{"datavalue":{"value":"Actor.jpg"}}}]}
                }}}"""
                else -> error("Unexpected entity batch: ${parts.queryParameter("ids")}")
            }
            else -> error("Unexpected Wikidata URL: $url")
        }
    }

    private class MemoryMetadataStore : MetadataStore {
        private val rows = mutableMapOf<String, Metadata>()
        override suspend fun get(cacheKey: String): Metadata? = rows[cacheKey]
        override suspend fun upsert(metadata: Metadata) { rows[metadata.cacheKey] = metadata }
    }
}
