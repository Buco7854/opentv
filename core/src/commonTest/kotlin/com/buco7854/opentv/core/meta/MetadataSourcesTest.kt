package com.buco7854.opentv.core.meta

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
