package com.buco7854.opentv.hub

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HubEndpointsTest {

    @Test
    fun normalizeAcceptsPastedVariants() {
        assertEquals("http://tv.lan:8080", HubEndpoints.normalizeBaseUrl("http://tv.lan:8080/"))
        assertEquals("http://tv.lan:8080", HubEndpoints.normalizeBaseUrl("http://tv.lan:8080/api/v1"))
        assertEquals("https://tv.example", HubEndpoints.normalizeBaseUrl("  https://tv.example/api/v1/  "))
    }

    @Test
    fun listingQueriesEncodeGroupAndFilter() {
        assertEquals(
            "https://tv.example/api/v1/playlists/3/channels?kind=0&group=News%20%26%20Sport&offset=50&limit=50&filter=100%25",
            HubEndpoints.channels("https://tv.example", 3, 0, "News & Sport", 50, 50, "100%"),
        )
        assertEquals(
            "https://tv.example/api/v1/playlists/3/series-groups?group=Drama&offset=0&limit=50",
            HubEndpoints.seriesGroups("https://tv.example", 3, "Drama", 0, 50, ""),
        )
    }

    @Test
    fun playbackSocketSwapsScheme() {
        assertEquals(
            "wss://tv.example/api/v1/playback/lease-1/ws?ws_token=tok",
            HubEndpoints.playbackSocket("https://tv.example", "lease-1", "tok"),
        )
        assertEquals(
            "ws://tv.lan:8080/api/v1/playback/lease-1/ws?ws_token=tok",
            HubEndpoints.playbackSocket("http://tv.lan:8080", "lease-1", "tok"),
        )
    }

    @Test
    fun contentIdsTravelAsPathSegments() {
        assertEquals(
            "https://tv.example/api/v1/content/ab%2Fcd/guide",
            HubEndpoints.contentGuide("https://tv.example", "ab/cd"),
        )
    }

    @Test
    fun providerSeriesIdsTravelAsEncodedPathSegments() {
        assertEquals(
            "https://tv.example/api/v1/playlists/7/xseries/series%2F%3F%23%20%C3%A9",
            HubEndpoints.xtreamSeriesDetail(
                "https://tv.example",
                7,
                "series/?# é",
            ),
        )
    }

    @Test
    fun imageCapabilityIsEncodedAsQueryData() {
        assertEquals(
            "https://tv.example/api/v1/img?u=opaque%2Ftoken%3Fwith%3Ddelimiters",
            HubEndpoints.image("https://tv.example", "opaque/token?with=delimiters"),
        )
    }

    @Test
    fun downloadFileUsesOnlyTheEncodedCapability() {
        assertEquals(
            "https://tv.example/api/v1/downloads/user%2Fdownload/file" +
                "?token=opaque%2Ftoken%3Fwith%3Ddelimiters",
            HubEndpoints.downloadFile(
                "https://tv.example",
                "user/download",
                "opaque/token?with=delimiters",
            ),
        )
    }

    @Test
    fun sameOriginAcceptsTheHubsOwnPages() {
        assertTrue(HubEndpoints.isSameOrigin("https://tv.example", "https://tv.example/link#t=abc"))
        assertTrue(HubEndpoints.isSameOrigin("https://tv.example/api/v1", "https://tv.example/link"))
        assertTrue(HubEndpoints.isSameOrigin("http://tv.lan:8080", "http://tv.lan:8080/link"))
        // Implicit vs explicit default port is the same origin.
        assertTrue(HubEndpoints.isSameOrigin("https://tv.example", "https://tv.example:443/link"))
    }

    @Test
    fun sameOriginRejectsRedirectionElsewhere() {
        // A hub must not steer the browser off its own origin.
        assertFalse(HubEndpoints.isSameOrigin("https://tv.example", "https://evil.example/link"))
        assertFalse(HubEndpoints.isSameOrigin("https://tv.example", "http://tv.example/link"))
        assertFalse(HubEndpoints.isSameOrigin("https://tv.example", "https://tv.example.evil.com/link"))
        assertFalse(HubEndpoints.isSameOrigin("http://tv.lan:8080", "http://tv.lan:9999/link"))
        assertFalse(HubEndpoints.isSameOrigin("https://tv.example", "javascript:alert(1)"))
        assertFalse(HubEndpoints.isSameOrigin("https://tv.example", "not a url"))
    }
}
