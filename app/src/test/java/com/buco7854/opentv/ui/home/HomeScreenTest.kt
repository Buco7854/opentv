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

    @Test
    fun anUnreachableServerIsNotAFirstRun() {
        // Someone whose playlists all live on one server, on a morning the server does
        // not answer. The welcome screen would tell them they have no playlists and
        // invite them to add one they added long ago; say the server is unreachable.
        assertEquals(
            HomeState.UNREACHABLE,
            homeState(emptyList(), emptyList(), catalogSourcesLoading = false, unreachableHubs = 1),
        )
        assertEquals(
            HomeState.WELCOME,
            homeState(emptyList(), emptyList(), catalogSourcesLoading = false, unreachableHubs = 0),
        )
    }

    @Test
    fun oneFailedServerDoesNotHideAnotherThatAnswered() {
        // Partial success is still content: hiding it behind a retry would lose the
        // playlists we do have, which is worse than the failure it reports.
        assertEquals(
            HomeState.CONTENT,
            homeState(
                emptyList(),
                listOf(CatalogSourceEntry(SourceId.Hub(4, 9), "Remote")),
                catalogSourcesLoading = false,
                unreachableHubs = 1,
            ),
        )
        assertEquals(
            HomeState.CONTENT,
            homeState(
                listOf(playlist(1)),
                emptyList(),
                catalogSourcesLoading = false,
                unreachableHubs = 1,
            ),
        )
    }

    @Test
    fun nothingIsDecidedBeforeBothSidesHaveReported() {
        assertEquals(
            HomeState.LOADING,
            homeState(null, emptyList(), catalogSourcesLoading = false, unreachableHubs = 1),
        )
        assertEquals(
            HomeState.LOADING,
            homeState(emptyList(), emptyList(), catalogSourcesLoading = true, unreachableHubs = 0),
        )
    }

    private fun playlist(id: Long) = Playlist(id = id, name = "$id", url = null)
}
