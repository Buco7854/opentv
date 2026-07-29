package com.buco7854.opentv.ui.favorites

import com.buco7854.opentv.core.model.ChannelKind
import com.buco7854.opentv.source.CatalogItem
import com.buco7854.opentv.source.ContentRef
import com.buco7854.opentv.source.SourceId
import com.buco7854.opentv.source.FavoritesSection
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AllFavoritesIdentityTest {
    @Test
    fun collidingHashCodesRemainDistinctComposeAndSelectionKeys() {
        check("FB".hashCode() == "Ea".hashCode())
        val source = SourceId.LocalPlaylist(1)
        val first = item(ContentRef.LocalUrl("FB", 0))
        val second = item(ContentRef.LocalUrl("Ea", 0))
        check(first.ref.hashCode() == second.ref.hashCode())

        assertNotEquals(
            favoriteItemKey(source, first),
            favoriteItemKey(source, second),
        )
    }

    @Test
    fun missingFilteredSourceFallsBackToAllSources() {
        val missing = SourceId.LocalPlaylist(1)
        val remaining = SourceId.LocalPlaylist(2)

        assertEquals(
            null,
            retainedFavoriteFilter(
                missing,
                listOf(FavoritesSection(remaining, "Remaining", emptyList(), false, null)),
            ),
        )
    }

    private fun item(ref: ContentRef) = CatalogItem(
        ref = ref,
        title = ref.toString(),
        imageUrl = null,
        kind = ChannelKind.LIVE,
        group = "Live",
    )
}
