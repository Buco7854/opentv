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

    @Test
    fun a_subtitled_title_keeps_its_prefix() {
        assertEquals("CSI: Miami" to null, TitleCleaner.clean("CSI: Miami"))
        assertEquals("ER: Season 1" to null, TitleCleaner.clean("ER: Season 1"))
        assertEquals("Oppenheimer" to null, TitleCleaner.clean("VF - Oppenheimer"))
    }

    @Test
    fun a_numeric_title_survives_and_a_year_in_the_name_is_not_a_release_year() {
        assertEquals("1917" to null, TitleCleaner.clean("1917"))
        assertEquals("2012" to null, TitleCleaner.clean("2012"))
        assertEquals("Blade Runner 2049" to null, TitleCleaner.clean("Blade Runner 2049"))
        assertEquals("Blade Runner" to "1982", TitleCleaner.clean("Blade Runner (1982)"))
    }
}
