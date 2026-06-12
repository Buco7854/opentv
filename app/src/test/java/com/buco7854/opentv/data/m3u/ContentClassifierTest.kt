package com.buco7854.opentv.data.m3u

import com.buco7854.opentv.data.db.ChannelKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentClassifierTest {

    private fun kind(name: String, url: String, group: String? = null): Int =
        ContentClassifier.classify(name, url, group).kind

    // --- Xtream URL paths are authoritative ---

    @Test
    fun `xtream movie path wins`() {
        assertEquals(ChannelKind.MOVIE, kind("Some Title", "http://host:80/movie/user/pass/1234.mkv"))
    }

    @Test
    fun `xtream series path wins even without episode marker`() {
        assertEquals(ChannelKind.SERIES, kind("Breaking Bad", "http://host/series/user/pass/99.mp4"))
    }

    @Test
    fun `xtream live path wins over vod-looking name`() {
        assertEquals(ChannelKind.LIVE, kind("Die Hard (1988)", "http://host/live/user/pass/42.ts"))
    }

    // --- The classic failure mode: series served over .ts streams ---

    @Test
    fun `episode marker beats ts extension`() {
        assertEquals(
            ChannelKind.SERIES,
            kind("Breaking Bad S01E05", "http://host/user/pass/8812.ts"),
        )
    }

    @Test
    fun `season episode with EP spelling`() {
        val c = ContentClassifier.classify("Dark S02 EP 3", "http://host/8812.mkv", null)
        assertEquals(ChannelKind.SERIES, c.kind)
        assertEquals(2, c.season)
        assertEquals(3, c.episode)
        assertEquals("Dark", c.seriesKey)
    }

    @Test
    fun `wordy season episode marker`() {
        val c = ContentClassifier.classify("Lupin Season 1 Episode 4", "http://host/lupin.ts", null)
        assertEquals(ChannelKind.SERIES, c.kind)
        assertEquals(1, c.season)
        assertEquals(4, c.episode)
    }

    // --- Weak markers must not steal live channels ---

    @Test
    fun `1x05 with vod extension is series`() {
        val c = ContentClassifier.classify("Friends 1x05", "http://host/f.mp4", null)
        assertEquals(ChannelKind.SERIES, c.kind)
        assertEquals(1, c.season)
        assertEquals(5, c.episode)
        assertEquals("Friends", c.seriesKey)
    }

    @Test
    fun `weak marker alone on a ts stream stays live`() {
        // e.g. a live event feed named "UFC 2x30" should not become a series
        assertEquals(ChannelKind.LIVE, kind("Event 2x30", "http://host/ev.ts"))
    }

    @Test
    fun `weak marker plus series group is series`() {
        assertEquals(ChannelKind.SERIES, kind("Friends 1x05", "http://host/f.ts", "SERIES | EN"))
    }

    // --- Group-title hints ---

    @Test
    fun `vod group with extensionless url is movie`() {
        assertEquals(ChannelKind.MOVIE, kind("Inception", "http://host/stream/9911", "VOD | Action"))
    }

    @Test
    fun `series group without marker is series grouped by name`() {
        val c = ContentClassifier.classify("Cobra Kai", "http://host/ck/77", "SERIES FR")
        assertEquals(ChannelKind.SERIES, c.kind)
        assertEquals("Cobra Kai", c.seriesKey)
    }

    @Test
    fun `french film group is movie`() {
        assertEquals(ChannelKind.MOVIE, kind("Intouchables", "http://host/4", "| FR | FILMS"))
    }

    // --- Extension fallback ---

    @Test
    fun `plain ts in a news group is live`() {
        assertEquals(ChannelKind.LIVE, kind("CNN International", "http://host/cnn.ts", "News"))
    }

    @Test
    fun `mp4 without other hints is movie`() {
        assertEquals(ChannelKind.MOVIE, kind("Whiplash", "http://host/w.mp4"))
    }

    @Test
    fun `m3u8 url is live`() {
        assertEquals(ChannelKind.LIVE, kind("Sky Sports", "http://host/sky/index.m3u8"))
    }

    // --- Year-tag fallback ---

    @Test
    fun `trailing year tag with unknown extension is movie`() {
        assertEquals(ChannelKind.MOVIE, kind("Oppenheimer (2023)", "http://host/stream/777"))
    }

    @Test
    fun `no signals at all defaults to live`() {
        assertEquals(ChannelKind.LIVE, kind("BBC One", "http://host/stream/1"))
    }

    // --- Separators ---

    @Test
    fun `decorative separators are detected`() {
        assertTrue(ContentClassifier.isSeparator("#### SPORTS ####"))
        assertTrue(ContentClassifier.isSeparator("===== FR | CINEMA ====="))
        assertTrue(ContentClassifier.isSeparator("----------------"))
        assertTrue(ContentClassifier.isSeparator("●●● VOD ●●●"))
    }

    @Test
    fun `real channel names are not separators`() {
        assertFalse(ContentClassifier.isSeparator("BBC One"))
        assertFalse(ContentClassifier.isSeparator("M6"))
        assertFalse(ContentClassifier.isSeparator("Rock-FM 95.5"))
    }

    // --- Series key normalization ---

    @Test
    fun `series key strips separators and collapses spaces`() {
        val c = ContentClassifier.classify("Better  Call Saul - S03E07 - Expenses", "http://h/x.mkv", null)
        assertEquals("Better Call Saul", c.seriesKey)
        assertEquals(3, c.season)
        assertEquals(7, c.episode)
    }
}
