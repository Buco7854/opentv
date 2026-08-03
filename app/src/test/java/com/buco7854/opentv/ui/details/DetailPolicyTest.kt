package com.buco7854.opentv.ui.details

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailPolicyTest {
    @Test
    fun `a series with nothing to list says so instead of drawing nothing`() {
        assertTrue(showsNoEpisodes(loading = false, episodes = 0, episodeTotal = 0))
    }

    @Test
    fun `a page still filling is not an empty series`() {
        // The server sends the whole season list with an empty first page, so the rows on
        // screen cannot tell "still arriving" from "nothing here". Only a total of zero can.
        assertFalse(showsNoEpisodes(loading = true, episodes = 0, episodeTotal = 0))
        assertFalse(showsNoEpisodes(loading = false, episodes = 0, episodeTotal = 80))
        assertFalse(showsNoEpisodes(loading = false, episodes = 12, episodeTotal = 12))
    }
}
