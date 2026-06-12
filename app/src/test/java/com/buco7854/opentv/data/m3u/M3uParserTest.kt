package com.buco7854.opentv.data.m3u

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader

class M3uParserTest {

    private fun parse(content: String): Pair<M3uHeader?, List<M3uEntry>> {
        var header: M3uHeader? = null
        val entries = mutableListOf<M3uEntry>()
        M3uParser.parse(BufferedReader(StringReader(content)), onHeader = { header = it }) {
            entries.add(it)
        }
        return header to entries
    }

    @Test
    fun `parses attributes group and url`() {
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
    fun `names containing commas are not truncated`() {
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
    fun `extgrp applies when no group-title and unknown directives are skipped`() {
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
    fun `missing group falls back to Uncategorized`() {
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
    fun `separator rows are dropped`() {
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
    fun `url line without preceding extinf is ignored`() {
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
