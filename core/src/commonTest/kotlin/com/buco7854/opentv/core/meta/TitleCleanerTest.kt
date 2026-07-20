package com.buco7854.opentv.core.meta

import kotlin.test.Test
import kotlin.test.assertEquals

class TitleCleanerTest {

    @Test
    fun strips_provider_decorations_and_extracts_year() {
        val (title, year) = TitleCleaner.clean("FR - Oppenheimer (2023) [1080p x265]")
        assertEquals("Oppenheimer", title)
        assertEquals("2023", year)
    }

    @Test
    fun plain_title_passes_through() {
        val (title, year) = TitleCleaner.clean("Breaking Bad")
        assertEquals("Breaking Bad", title)
        assertEquals(null, year)
    }

    @Test
    fun quality_tags_and_separators_are_removed() {
        val (title, _) = TitleCleaner.clean("The.Matrix.1999.4K.HDR.WEB-DL")
        assertEquals("The Matrix", title)
    }
}
