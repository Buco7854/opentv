package com.buco7854.opentv.source

import com.buco7854.opentv.core.model.ChannelKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HubCatalogCacheTest {
    @Test
    fun `clearing a hub drops only that hub's cached groups and items`() {
        val cache = HubCatalogCache()
        val hubA = SourceId.Hub(hubId = 1, playlistId = 10)
        val hubB = SourceId.Hub(hubId = 2, playlistId = 20)

        cache.putGroups(hubA, ChannelKind.LIVE, listOf(CatalogGroup("News", 3)))
        cache.putItems(hubA, ChannelKind.LIVE, "News", listOf(item("a")))
        cache.putGroups(hubB, ChannelKind.LIVE, listOf(CatalogGroup("Sports", 1)))
        cache.putItems(hubB, ChannelKind.LIVE, "Sports", listOf(item("b")))

        cache.clearHub(hubA.hubId)

        assertNull(cache.groups(hubA, ChannelKind.LIVE))
        assertNull(cache.items(hubA, ChannelKind.LIVE, "News"))
        assertEquals(listOf(CatalogGroup("Sports", 1)), cache.groups(hubB, ChannelKind.LIVE))
        assertEquals(listOf(item("b")), cache.items(hubB, ChannelKind.LIVE, "Sports"))
    }

    private fun item(contentId: String) = CatalogItem(
        ref = ContentRef.HubContent(contentId),
        title = contentId,
        imageUrl = null,
        kind = ChannelKind.LIVE,
        group = null,
    )
}
