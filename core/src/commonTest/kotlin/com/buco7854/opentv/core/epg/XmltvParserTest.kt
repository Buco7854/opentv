package com.buco7854.opentv.core.epg

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class XmltvParserTest {

    private fun textSource(content: String): TextSource {
        var i = 0
        return TextSource { if (i < content.length) content[i++].code else -1 }
    }

    private fun parse(
        xml: String,
        wanted: Set<String>,
        windowStart: Long = Long.MIN_VALUE,
        windowEnd: Long = Long.MAX_VALUE,
    ): List<XmltvProgramme> {
        val out = mutableListOf<XmltvProgramme>()
        runTest {
            XmltvParser.parse(textSource(xml), wanted, windowStart, windowEnd) { out.addAll(it) }
        }
        return out
    }

    private val doc = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE tv SYSTEM "xmltv.dtd">
        <tv generator-info-name="test">
          <channel id="bbc1.uk"><display-name>BBC One</display-name></channel>
          <!-- a comment <programme> inside -->
          <programme start="20231114210000 +0000" stop="20231114220000 +0000" channel="bbc1.uk">
            <title lang="en">News &amp; Weather</title>
            <desc>Tom &#38; Jerry special.</desc>
          </programme>
          <programme start="20231114220000" stop="20231114230000" channel="bbc1.uk">
            <title><![CDATA[Late <Night> Show]]></title>
          </programme>
          <programme start="20231114210000 +0100" stop="20231114220000 +0100" channel="other.fr">
            <title>Ignored</title>
          </programme>
        </tv>
    """.trimIndent()

    @Test
    fun parses_programmes_with_entities_and_cdata() {
        val programmes = parse(doc, wanted = setOf("bbc1.uk"))
        assertEquals(2, programmes.size)
        assertEquals("News & Weather", programmes[0].title)
        assertEquals("Tom & Jerry special.", programmes[0].description)
        assertEquals("Late <Night> Show", programmes[1].title)
        assertNull(programmes[1].description)
    }

    @Test
    fun times_honor_offsets_and_default_to_utc() {
        val programmes = parse(doc, wanted = setOf("bbc1.uk", "other.fr"))
        // 2023-11-14 21:00 UTC
        assertEquals(1_699_995_600_000, programmes[0].startMs)
        // plain time is UTC: 22:00
        assertEquals(1_699_999_200_000, programmes[1].startMs)
        // +0100 offset: 21:00+01 = 20:00 UTC
        assertEquals(1_699_992_000_000, programmes[2].startMs)
    }

    @Test
    fun unwanted_channels_and_out_of_window_programmes_are_skipped() {
        val onlyBbc = parse(doc, wanted = setOf("bbc1.uk"))
        assertEquals(2, onlyBbc.size)

        val windowed = parse(
            doc,
            wanted = setOf("bbc1.uk"),
            windowStart = 1_699_999_200_000, // second programme only
            windowEnd = Long.MAX_VALUE,
        )
        assertEquals(1, windowed.size)
        assertEquals("Late <Night> Show", windowed[0].title)
    }

    @Test
    fun blank_title_becomes_untitled() {
        val xml = """
            <tv><programme start="20231114210000" stop="20231114220000" channel="c">
            <title> </title></programme></tv>
        """.trimIndent()
        assertEquals("Untitled", parse(xml, setOf("c")).single().title)
    }

    @Test
    fun time_parsing_edge_cases() {
        assertNull(XmltvParser.parseTime(null))
        assertNull(XmltvParser.parseTime("garbage"))
        assertNull(XmltvParser.parseTime("2023111"))
        assertEquals(1_699_995_600_000, XmltvParser.parseTime("20231114210000"))
        assertEquals(1_699_992_000_000, XmltvParser.parseTime("20231114210000 +01:00"))
        assertEquals(1_699_999_200_000, XmltvParser.parseTime("20231114210000 -0100"))
    }
}
