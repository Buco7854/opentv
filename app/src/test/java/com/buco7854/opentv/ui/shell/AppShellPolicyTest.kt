package com.buco7854.opentv.ui.shell

import com.buco7854.opentv.R
import com.buco7854.opentv.source.PlaylistEpgRefreshOutcome
import org.junit.Assert.assertEquals
import org.junit.Test

class AppShellPolicyTest {
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
