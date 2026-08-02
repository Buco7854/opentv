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

    @Test
    fun a_multilingual_programme_keeps_one_title_not_every_translation() {
        val xml = """
            <tv>
              <programme channel="c1" start="20240101120000 +0000" stop="20240101130000 +0000">
                <title lang="en">News</title>
                <title lang="fr">Journal</title>
                <desc lang="en">Evening bulletin</desc>
                <desc lang="fr">Bulletin du soir</desc>
              </programme>
            </tv>
        """.trimIndent()

        val programme = parse(xml, setOf("c1")).single()

        assertEquals("News", programme.title)
        assertEquals("Evening bulletin", programme.description)
    }

    @Test
    fun an_empty_first_title_falls_through_to_the_next_one() {
        val xml = """
            <tv>
              <programme channel="c1" start="20240101120000 +0000" stop="20240101130000 +0000">
                <title lang="en"></title>
                <title lang="fr">Journal</title>
              </programme>
            </tv>
        """.trimIndent()

        assertEquals("Journal", parse(xml, setOf("c1")).single().title)
    }

    @Test
    fun invalid_intervals_are_dropped_without_losing_valid_programmes() {
        val xml = """
            <tv>
              <programme channel="c1" start="20240101130000 +0000" stop="20240101120000 +0000">
                <title>Backwards</title>
              </programme>
              <programme channel="c1" start="20240101140000 +0000" stop="20240101140000 +0000">
                <title>Zero length</title>
              </programme>
              <programme channel="c1" start="20240101150000 +0000" stop="20240101160000 +0000">
                <title>Valid</title>
              </programme>
            </tv>
        """.trimIndent()

        assertEquals(listOf("Valid"), parse(xml, setOf("c1")).map { it.title })
    }

    @Test
    fun window_and_airing_boundaries_use_half_open_intervals() {
        val noon = requireNotNull(XmltvParser.parseTime("20240101120000 +0000"))
        val one = requireNotNull(XmltvParser.parseTime("20240101130000 +0000"))
        val two = requireNotNull(XmltvParser.parseTime("20240101140000 +0000"))
        val xml = """
            <tv>
              <programme channel="c1" start="20240101110000 +0000" stop="20240101120000 +0000">
                <title>Stops at window</title>
              </programme>
              <programme channel="c1" start="20240101120000 +0000" stop="20240101130000 +0000">
                <title>Inside</title>
              </programme>
              <programme channel="c1" start="20240101140000 +0000" stop="20240101150000 +0000">
                <title>Starts at window end</title>
              </programme>
            </tv>
        """.trimIndent()

        assertEquals(
            listOf("Inside"),
            parse(xml, setOf("c1"), windowStart = noon, windowEnd = two).map { it.title },
        )
        assertEquals(one, parse(xml, setOf("c1"), noon, two).single().endMs)
    }

    @Test
    fun missing_stop_is_skipped_and_parsing_continues() {
        val xml = """
            <tv>
              <programme channel="c1" start="20240101120000 +0000"><title>No stop</title></programme>
              <programme channel="c1" start="20240101130000 +0000" stop="20240101140000 +0000">
                <title>Valid</title>
              </programme>
            </tv>
        """.trimIndent()

        assertEquals(listOf("Valid"), parse(xml, setOf("c1")).map { it.title })
    }

    @Test
    fun explicit_offsets_keep_real_duration_across_dst_transitions() {
        val xml = """
            <tv>
              <programme channel="c1" start="20240331003000 +0000" stop="20240331023000 +0100">
                <title>Spring forward</title>
              </programme>
              <programme channel="c1" start="20241027013000 +0100" stop="20241027013000 +0000">
                <title>Fall back</title>
              </programme>
            </tv>
        """.trimIndent()

        val programmes = parse(xml, setOf("c1"))

        assertEquals(listOf(3_600_000L, 3_600_000L), programmes.map { it.endMs - it.startMs })
    }

    @Test
    fun channel_id_padding_does_not_break_playlist_matching() {
        val xml = """
            <tv>
              <programme channel="  c1  " start="20240101120000 +0000" stop="20240101130000 +0000">
                <title>Matched</title>
              </programme>
            </tv>
        """.trimIndent()

        assertEquals(listOf("Matched"), parse(xml, setOf("c1")).map { it.title })
    }

    @Test
    fun large_guides_are_emitted_in_bounded_batches() = runTest {
        val xml = buildString {
            append("<tv>")
            repeat(1_001) { index ->
                append("<programme channel=\"c1\" start=\"20240101120000 +0000\" ")
                append("stop=\"20240101130000 +0000\"><title>")
                append(index)
                append("</title></programme>")
            }
            append("</tv>")
        }
        val batchSizes = mutableListOf<Int>()

        XmltvParser.parse(
            textSource(xml),
            wantedChannelIds = setOf("c1"),
            windowStartMs = Long.MIN_VALUE,
            windowEndMs = Long.MAX_VALUE,
        ) { batchSizes.add(it.size) }

        assertEquals(listOf(500, 500, 1), batchSizes)
    }
}
