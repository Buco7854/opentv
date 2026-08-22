package com.buco7854.opentv.core.meta

import com.buco7854.opentv.core.net.Urls
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MetadataSourcesTest {
    @Test
    fun source_id_above_javascript_safe_integer_is_preserved() = runTest {
        val providerId = 9_007_199_254_740_993L
        val api = TvMazeApi { url ->
            if (url.endsWith("/cast")) {
                "[]"
            } else {
                """[{"show":{"id":"$providerId","name":"Precise"}}]"""
            }
        }

        assertEquals(providerId, api.fetch("Precise")?.sourceId)
    }

    @Test
    fun invalid_or_oversized_optional_source_id_is_omitted_without_losing_metadata() = runTest {
        val ids = listOf("not-a-number", "9223372036854775808", "01", "0")

        ids.forEach { providerId ->
            val metadata = TvMazeApi {
                """[{"show":{"id":"$providerId","name":"Still available"}}]"""
            }.fetch("Still available")

            assertEquals("Still available", metadata?.title)
            assertNull(metadata?.sourceId)
        }
    }

    @Test
    fun wikidata_movie_cast_requires_one_exact_film_and_accepts_only_commons_photos() = runTest {
        val requestedUrls = mutableListOf<String>()
        val api = WikidataMovieCastApi { url ->
            requestedUrls += url
            wikidataResponse(url)
        }

        val cast = api.fetch("A Film", "2024")

        assertEquals(listOf("First Actor", "Second Actor"), cast.map(CastMember::name))
        assertEquals(
            "https://commons.wikimedia.org/wiki/Special:FilePath/First%20Actor.jpg?width=240",
            cast.first().photo,
        )
        assertEquals(
            "commons.wikimedia.org",
            Urls.parse(assertNotNull(cast.last().photo))?.host,
            "a remote P18 string escaped the fixed Commons origin",
        )
        assertEquals(4, requestedUrls.size)
        requestedUrls.forEach { requested ->
            val parts = assertNotNull(Urls.parse(requested))
            assertEquals("www.wikidata.org", parts.host)
            assertNull(parts.queryParameter("query"), "the slow SPARQL endpoint was used")
        }

        assertEquals(emptyList(), api.fetch("A Film", "2023"), "release year was ignored")
    }

    @Test
    fun wikidata_movie_cast_rejects_ambiguous_titles() = runTest {
        var requests = 0
        val api = WikidataMovieCastApi { url ->
            requests++
            val query = assertNotNull(Urls.parse(url)).let { parts ->
                parts.queryParameter("action") to parts.queryParameter("ids")
            }
            when (query.first) {
                "wbsearchentities" -> """{"search":[{"id":"Q10"},{"id":"Q20"}]}"""
                "wbgetentities" -> """{"entities":{
                  "Q10":{"labels":{"en":{"value":"Shared title"}},"claims":{"P161":[
                    {"mainsnak":{"datavalue":{"value":{"id":"Q1"}}}}
                  ]}},
                  "Q20":{"labels":{"en":{"value":"Shared title"}},"claims":{"P161":[
                    {"mainsnak":{"datavalue":{"value":{"id":"Q2"}}}}
                  ]}}
                }}"""
                else -> error("Unexpected Wikidata URL: $url")
            }
        }

        assertEquals(emptyList(), api.fetch("Shared title", null))
        assertEquals(3, requests, "actors were fetched before resolving the ambiguity")
    }

    private fun wikidataResponse(url: String): String {
        val parts = assertNotNull(Urls.parse(url))
        return when (parts.queryParameter("action")) {
            "wbsearchentities" -> """{"search":[{"id":"Q10"}]}"""
            "wbgetentities" -> when (parts.queryParameter("ids")) {
                "Q10" -> """{"entities":{"Q10":{
                  "labels":{"en":{"value":"A Film"}},
                  "aliases":{"fr":[{"value":"Un film"}]},
                  "claims":{
                    "P577":[{"mainsnak":{"datavalue":{"value":{"time":"+2024-02-03T00:00:00Z"}}}}],
                    "P161":[
                      {"mainsnak":{"datavalue":{"value":{"id":"Q2"}}},
                       "qualifiers":{"P1545":[{"datavalue":{"value":"2"}}]}},
                      {"mainsnak":{"datavalue":{"value":{"id":"Q1"}}},
                       "qualifiers":{"P1545":[{"datavalue":{"value":"1"}}]}}
                    ]
                  }
                }}}"""
                "Q1|Q2" -> """{"entities":{
                  "Q1":{"labels":{"en":{"value":"First Actor"}},"claims":{"P18":[
                    {"mainsnak":{"datavalue":{"value":"First Actor.jpg"}}}
                  ]}},
                  "Q2":{"labels":{"en":{"value":"Second Actor"}},"claims":{"P18":[
                    {"mainsnak":{"datavalue":{"value":"https://evil.example/portrait.jpg"}}}
                  ]}}
                }}"""
                else -> error("Unexpected entity batch: ${parts.queryParameter("ids")}")
            }
            else -> error("Unexpected Wikidata URL: $url")
        }
    }
}
