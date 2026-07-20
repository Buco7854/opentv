package com.buco7854.opentv.core.m3u

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class M3uParserTest {

    private fun parse(content: String): Pair<M3uHeader?, List<M3uEntry>> {
        var header: M3uHeader? = null
        val entries = mutableListOf<M3uEntry>()
        runTest {
            M3uParser.parse(content.lineSequence(), onHeader = { header = it }) {
                entries.add(it)
            }
        }
        return header to entries
    }

    @Test
    fun parses_attributes_group_and_url() {
        val (header, entries) = parse(
            """
            #EXTM3U url-tvg="http://epg.example/guide.xml.gz"
            #EXTINF:-1 tvg-id="bbc1.uk" tvg-name="BBC One" tvg-logo="http://x/l.png" group-title="UK | News",BBC One HD
            http://host/live/u/p/1.ts
            """.trimIndent()
        )
        assertEquals("http://epg.example/guide.xml.gz", header?.epgUrl)
        assertEquals(1, entries.size)
        val entry = entries[0]
        assertEquals("BBC One HD", entry.name)
        assertEquals("bbc1.uk", entry.tvgId)
        assertEquals("http://x/l.png", entry.logo)
        assertEquals("UK | News", entry.groupTitle)
        assertEquals("http://host/live/u/p/1.ts", entry.url)
    }

    @Test
    fun names_containing_commas_are_not_truncated() {
        val (_, entries) = parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="x" group-title="UK, Ireland | News",News, Weather & Sport
            http://host/n.ts
            #EXTINF:-1,Late Night, Live
            http://host/l.ts
            """.trimIndent()
        )
        assertEquals("News, Weather & Sport", entries[0].name)
        assertEquals("UK, Ireland | News", entries[0].groupTitle)
        assertEquals("Late Night, Live", entries[1].name)
    }

    @Test
    fun extgrp_applies_when_no_group_title_and_unknown_directives_are_skipped() {
        val (_, entries) = parse(
            """
            #EXTM3U
            #EXTINF:-1,Channel A
            #EXTGRP:Sports
            #EXTVLCOPT:http-user-agent=Foo
            http://host/a.ts
            """.trimIndent()
        )
        assertEquals(1, entries.size)
        assertEquals("Sports", entries[0].groupTitle)
    }

    @Test
    fun missing_group_falls_back_to_uncategorized() {
        val (_, entries) = parse(
            """
            #EXTM3U
            #EXTINF:-1,Channel B
            http://host/b.ts
            """.trimIndent()
        )
        assertEquals("Uncategorized", entries[0].groupTitle)
    }

    @Test
    fun separator_rows_are_dropped() {
        val (_, entries) = parse(
            """
            #EXTM3U
            #EXTINF:-1 group-title="Sports",##### SPORTS #####
            http://host/sep.ts
            #EXTINF:-1 group-title="Sports",ESPN
            http://host/espn.ts
            """.trimIndent()
        )
        assertEquals(listOf("ESPN"), entries.map { it.name })
    }

    @Test
    fun catchup_append_joins_source_to_stream_url() {
        val (_, entries) = parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="a" catchup="append" catchup-days="3" catchup-source="?utc={utc}",Chan A
            http://host/a.m3u8
            """.trimIndent()
        )
        assertEquals("http://host/a.m3u8?utc={utc}", entries[0].catchupSource)
        assertEquals(3, entries[0].catchupDays)
    }

    @Test
    fun catchup_shift_synthesizes_utc_lutc_template() {
        val (_, entries) = parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="a" catchup="shift" catchup-days="2",Chan A
            http://host/a.m3u8
            #EXTINF:-1 tvg-id="b" catchup="shift",Chan B
            http://host/b.ts?token=x
            """.trimIndent()
        )
        assertEquals("http://host/a.m3u8?utc={utc}&lutc={lutc}", entries[0].catchupSource)
        assertEquals("http://host/b.ts?token=x&utc={utc}&lutc={lutc}", entries[1].catchupSource)
    }

    @Test
    fun catchup_flussonic_synthesizes_archive_template() {
        val (_, entries) = parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="a" catchup="flussonic" catchup-days="5",Chan A
            http://host/chan/index.m3u8
            #EXTINF:-1 tvg-id="b" catchup="flussonic",Chan B
            http://host/chan/mpegts
            """.trimIndent()
        )
        assertEquals("http://host/chan/archive-{utc}-{duration}.m3u8", entries[0].catchupSource)
        assertEquals("http://host/chan/timeshift_abs-{utc}.ts", entries[1].catchupSource)
    }

    @Test
    fun explicit_catchup_source_wins_over_mode_synthesis() {
        val (_, entries) = parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="a" catchup="flussonic" catchup-source="http://o/{utc}.ts",Chan A
            http://host/chan/index.m3u8
            """.trimIndent()
        )
        assertEquals("http://o/{utc}.ts", entries[0].catchupSource)
    }

    @Test
    fun url_line_without_preceding_extinf_is_ignored() {
        val (header, entries) = parse(
            """
            #EXTM3U
            http://host/orphan.ts
            """.trimIndent()
        )
        assertNull(header?.epgUrl)
        assertEquals(0, entries.size)
    }
}
