package com.buco7854.opentv.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogModelsTest {
    @Test
    fun `a series nobody counted does not claim to have no episodes`() {
        // Every panel series in a listing carries zero, because counting its episodes
        // would mean fetching them from the provider first. Rendering that produced
        // "0 episodes" under each one, which is a measurement we never took.
        assertNull(seriesEpisodeCount(0))
        assertNull(seriesEpisodeCount(null))
    }

    @Test
    fun `a real count still shows`() {
        assertEquals(1, seriesEpisodeCount(1))
        assertEquals(137, seriesEpisodeCount(137))
    }
}
