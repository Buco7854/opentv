package com.buco7854.opentv.data.tmdb

import org.junit.Assert.assertEquals
import org.junit.Test

class TitleCleanerTest {

    @Test
    fun `strips provider decorations and extracts year`() {
        val (title, year) = TitleCleaner.clean("FR - Oppenheimer (2023) [1080p x265]")
        assertEquals("Oppenheimer", title)
        assertEquals("2023", year)
    }

    @Test
    fun `plain title passes through`() {
        val (title, year) = TitleCleaner.clean("Breaking Bad")
        assertEquals("Breaking Bad", title)
        assertEquals(null, year)
    }

    @Test
    fun `quality tags and separators are removed`() {
        val (title, _) = TitleCleaner.clean("The.Matrix.1999.4K.HDR.WEB-DL")
        assertEquals("The Matrix", title)
    }
}
