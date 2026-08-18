package com.buco7854.opentv.ui.shell

import com.buco7854.opentv.R
import com.buco7854.opentv.source.PlaylistEpgRefreshOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppShellPolicyTest {
    @Test
    fun `source panel remains loading until local and hub sources have answered`() {
        assertTrue(panelSourcesLoading(busy = false, localPlaylistsLoaded = false, catalogSourcesLoading = false))
        assertTrue(panelSourcesLoading(busy = false, localPlaylistsLoaded = true, catalogSourcesLoading = true))
        assertTrue(panelSourcesLoading(busy = true, localPlaylistsLoaded = true, catalogSourcesLoading = false))
        assertFalse(panelSourcesLoading(busy = false, localPlaylistsLoaded = true, catalogSourcesLoading = false))
    }

    @Test
    fun `playlist refresh copy reflects the server EPG result`() {
        assertEquals(
            R.string.playlist_refreshed,
            playlistRefreshMessage(PlaylistEpgRefreshOutcome.SUCCEEDED),
        )
        assertEquals(
            R.string.playlist_refreshed_guide_failed,
            playlistRefreshMessage(PlaylistEpgRefreshOutcome.FAILED),
        )
        assertEquals(
            R.string.playlist_refreshed_without_guide,
            playlistRefreshMessage(PlaylistEpgRefreshOutcome.NOT_CONFIGURED),
        )
    }
}
