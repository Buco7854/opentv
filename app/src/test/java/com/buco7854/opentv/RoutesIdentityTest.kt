package com.buco7854.opentv

import android.app.Application
import android.net.Uri
import com.buco7854.opentv.core.model.ChannelKind
import com.buco7854.opentv.source.CatalogItem
import com.buco7854.opentv.source.ContentRef
import com.buco7854.opentv.source.SourceId
import com.buco7854.opentv.source.decode
import com.buco7854.opentv.source.encode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class RoutesIdentityTest {
    @Test
    fun hubReauthenticationRouteCarriesTheExistingRowId() {
        val route = Uri.parse("https://navigation.test/${Routes.hubSignIn(42)}")

        assertEquals("hub/connect", route.path?.removePrefix("/"))
        assertEquals("42", route.getQueryParameter("hubId"))
        assertEquals(Routes.HUB_SIGN_IN, Routes.hubSignIn(null))
    }

    @Test
    fun movieArgumentsSurviveTheNavigationPercentEncodingLayer() {
        val source = SourceId.Hub(Long.MIN_VALUE, Long.MAX_VALUE)
        val refs = listOf(
            ContentRef.LocalUrl("/?#%: spaces/é/\uD83D\uDCFA", Long.MIN_VALUE),
            ContentRef.LocalUrl("", 0),
            ContentRef.HubContent("/?#%: spaces/é/\uD83D\uDCFA"),
            ContentRef.HubContent("%2F"),
            ContentRef.HubContent("/"),
        )

        refs.forEach { ref ->
            val segments = Uri.parse("https://navigation.test/${Routes.movie(source, ref)}")
                .pathSegments
            assertEquals(source, SourceId.decode(segments[1]))
            assertEquals(ref, ContentRef.decode(segments[2]))
        }
    }

    @Test
    fun invalidAndEmptyHubArgumentsAreRejected() {
        assertNull(ContentRef.decode("h:"))
        assertNull(ContentRef.decode("h:%2f"))
        assertNull(ContentRef.decode("h:%ZZ"))
    }

    @Test
    fun seriesProviderKeysSurviveNavigationQueryEncoding() {
        val key = "/?#%: spaces/é/\uD83D\uDCFA/%2F"
        val item = CatalogItem(
            ref = ContentRef.HubContent("stable/%2F"),
            title = "fallback",
            imageUrl = null,
            kind = ChannelKind.SERIES,
            group = null,
            seriesKey = key,
            seriesId = Long.MAX_VALUE.toString(),
        )

        val m3u = Uri.parse("https://navigation.test/${Routes.series(SourceId.Hub(3, 7), item)}")
        assertEquals(key, m3u.getQueryParameter("k"))

        val xtream =
            Uri.parse("https://navigation.test/${Routes.xtreamSeries(SourceId.Hub(3, 7), item)}")
        assertEquals(key, xtream.getQueryParameter("k"))
        assertEquals(Long.MAX_VALUE.toString(), xtream.getQueryParameter("i"))
    }

    @Test
    fun opaqueSeriesProviderIdsSurviveNavigationQueryEncoding() {
        val providerId = "/?#%: spaces/é/\uD83D\uDCFA/%2F"
        val item = CatalogItem(
            ref = ContentRef.HubContent("stable"),
            title = "fallback",
            imageUrl = null,
            kind = ChannelKind.SERIES,
            group = null,
            seriesKey = "series",
            seriesId = providerId,
        )

        val route = Uri.parse(
            "https://navigation.test/${Routes.xtreamSeries(SourceId.Hub(3, 7), item)}",
        )

        assertEquals(providerId, route.getQueryParameter("i"))
    }

    @Test
    fun sourceLessDestinationsKeepTheLastHubForDockNavigation() {
        val hub = SourceId.Hub(3, 7)

        assertEquals(hub, activeCatalogSource(null, hub.encode(), activePlaylistId = 11))
        assertEquals(
            SourceId.LocalPlaylist(11),
            activeCatalogSource(null, null, activePlaylistId = 11),
        )
    }
}
