package com.buco7854.opentv.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceIdentityTest {
    @Test
    fun sourceIdsRoundTrip() {
        val values = listOf(
            SourceId.LocalPlaylist(42),
            SourceId.Hub(7, 99),
            SourceId.Hub(Long.MIN_VALUE, Long.MAX_VALUE),
            SourceId.HubConnection(7),
        )
        values.forEach { assertEquals(it, SourceId.decode(it.encode())) }
    }

    @Test
    fun sourceIdsRejectMalformedInput() {
        listOf("", "p:", "p:01", "p:1:2", "h:1", "h:1:", "h:+1:2", "hc:", "hc:01", "x:1")
            .forEach { assertNull(it, SourceId.decode(it)) }
    }

    @Test
    fun contentRefsRoundTripWithoutTreatingDelimitersAsStructure() {
        val values = listOf(
            ContentRef.LocalUrl("https://provider.test/live/a:b?x=1&y=two words", 12),
            ContentRef.LocalUrl("xs:900", 0),
            ContentRef.HubContent("playlist/1:stable?content"),
            ContentRef.LocalUrl("https://例.test/é", Long.MIN_VALUE),
            ContentRef.LocalUrl("/?#%: \uD83D\uDCFA", Long.MAX_VALUE),
            ContentRef.LocalUrl("", 0),
            ContentRef.HubContent("/?#%: \uD83D\uDCFA"),
        )
        values.forEach { assertEquals(it, ContentRef.decode(it.encode())) }
    }

    @Test
    fun contentRefsRejectMalformedAndNonCanonicalInput() {
        listOf("", "l:1", "l:01:x", "l:x:value", "h:", "h:%ZZ", "h:a/b", "p:1")
            .forEach { assertNull(it, ContentRef.decode(it)) }
    }
}
