package com.buco7854.opentv.ui.home

import com.buco7854.opentv.core.model.Playlist
import com.buco7854.opentv.source.SourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeScreenTest {
    @Test
    fun localPlaylistKeepsLegacyActiveThenFirstSelection() {
        val playlists = listOf(playlist(1), playlist(2))

        assertEquals(
            SourceId.LocalPlaylist(2),
            homeSource(playlists, 2, emptyList(), catalogSourcesLoading = false),
        )
        assertEquals(
            SourceId.LocalPlaylist(1),
            homeSource(playlists, 99, emptyList(), catalogSourcesLoading = false),
        )
    }

    @Test
    fun hubOnlyInstallWaitsForDiscoveryThenOpensTheHubPlaylist() {
        val hub = SourceId.Hub(4, 9)
        val sources = listOf(CatalogSourceEntry(hub, "Remote"))

        assertNull(homeSource(emptyList(), -1, sources, catalogSourcesLoading = true))
        assertEquals(
            hub,
            homeSource(emptyList(), -1, sources, catalogSourcesLoading = false),
        )
    }

    private fun playlist(id: Long) = Playlist(id = id, name = "$id", url = null)
}
