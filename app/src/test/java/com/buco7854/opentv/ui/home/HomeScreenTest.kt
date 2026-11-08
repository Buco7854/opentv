package com.buco7854.opentv.ui.home

import com.buco7854.opentv.core.model.Playlist
import com.buco7854.opentv.source.SourceId
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeScreenTest {
    @Test
    fun `local sources are available before remote discovery completes`() {
        val rows = homeSources(listOf(playlist(1, "Local", 42)), emptyList())
        assertEquals(listOf(SourceId.LocalPlaylist(1)), rows.map { it.sourceId })
        assertEquals(42, rows.single().itemCount)
    }

    @Test
    fun `latest local metadata wins over an older discovery snapshot without duplicate rows`() {
        val rows = homeSources(
            listOf(playlist(1, "Renamed", 42)),
            listOf(CatalogSourceEntry(SourceId.LocalPlaylist(1), "Old", itemCount = 12)),
        )
        assertEquals(1, rows.size)
        assertEquals("Renamed", rows.single().title)
        assertEquals(42, rows.single().itemCount)
    }

    @Test
    fun `a hub source keeps its server identity and actual count`() {
        val source = CatalogSourceEntry(SourceId.Hub(2, 7), "Movies", "Home server", 120)
        assertEquals(listOf(source), homeSources(emptyList(), listOf(source)))
    }

    @Test
    fun `local and remote playlists with the same numeric id remain separate sources`() {
        val rows = homeSources(
            listOf(playlist(7, "Local", 4)),
            listOf(CatalogSourceEntry(SourceId.Hub(2, 7), "Remote", "Server", 8)),
        )
        assertEquals(2, rows.size)
        assertEquals(listOf(4, 8), rows.map { it.itemCount })
    }

    @Test
    fun `a deleted local playlist cannot survive in an older discovery snapshot`() {
        assertEquals(
            emptyList<CatalogSourceEntry>(),
            homeSources(emptyList(), listOf(CatalogSourceEntry(SourceId.LocalPlaylist(1), "Deleted"))),
        )
    }

    private fun playlist(id: Long, name: String, count: Int) =
        Playlist(id = id, name = name, url = null, channelCount = count)
}
