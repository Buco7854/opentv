package com.buco7854.opentv.core.m3u

import com.buco7854.opentv.core.model.ChannelKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContentClassifierTest {

    private fun kind(name: String, url: String, group: String? = null): Int =
        ContentClassifier.classify(name, url, group).kind

    @Test
    fun xtream_movie_path_wins() {
        assertEquals(ChannelKind.MOVIE, kind("Some Title", "http://host:80/movie/user/pass/1234.mkv"))
    }

    @Test
    fun xtream_series_path_wins_even_without_episode_marker() {
        assertEquals(ChannelKind.SERIES, kind("Breaking Bad", "http://host/series/user/pass/99.mp4"))
    }

    @Test
    fun xtream_live_path_wins_over_vod_looking_name() {
        assertEquals(ChannelKind.LIVE, kind("Die Hard (1988)", "http://host/live/user/pass/42.ts"))
    }

    @Test
    fun episode_marker_beats_ts_extension() {
        assertEquals(
            ChannelKind.SERIES,
            kind("Breaking Bad S01E05", "http://host/user/pass/8812.ts"),
        )
    }

    @Test
    fun season_episode_with_ep_spelling() {
        val c = ContentClassifier.classify("Dark S02 EP 3", "http://host/8812.mkv", null)
        assertEquals(ChannelKind.SERIES, c.kind)
        assertEquals(2, c.season)
        assertEquals(3, c.episode)
        assertEquals("Dark", c.seriesKey)
    }

    @Test
    fun wordy_season_episode_marker() {
        val c = ContentClassifier.classify("Lupin Season 1 Episode 4", "http://host/lupin.ts", null)
        assertEquals(ChannelKind.SERIES, c.kind)
        assertEquals(1, c.season)
        assertEquals(4, c.episode)
    }

    @Test
    fun cross_marker_with_vod_extension_is_series() {
        val c = ContentClassifier.classify("Friends 1x05", "http://host/f.mp4", null)
        assertEquals(ChannelKind.SERIES, c.kind)
        assertEquals(1, c.season)
        assertEquals(5, c.episode)
        assertEquals("Friends", c.seriesKey)
    }

    @Test
    fun weak_marker_alone_on_a_ts_stream_stays_live() {
        assertEquals(ChannelKind.LIVE, kind("Event 2x30", "http://host/ev.ts"))
    }

    @Test
    fun weak_marker_plus_series_group_is_series() {
        assertEquals(ChannelKind.SERIES, kind("Friends 1x05", "http://host/f.ts", "SERIES | EN"))
    }

    @Test
    fun vod_group_with_extensionless_url_is_movie() {
        assertEquals(ChannelKind.MOVIE, kind("Inception", "http://host/stream/9911", "VOD | Action"))
    }

    @Test
    fun series_group_without_marker_is_series_grouped_by_name() {
        val c = ContentClassifier.classify("Cobra Kai", "http://host/ck/77", "SERIES FR")
        assertEquals(ChannelKind.SERIES, c.kind)
        assertEquals("Cobra Kai", c.seriesKey)
    }

    @Test
    fun french_film_group_is_movie() {
        assertEquals(ChannelKind.MOVIE, kind("Intouchables", "http://host/4", "| FR | FILMS"))
    }

    @Test
    fun plain_ts_in_a_news_group_is_live() {
        assertEquals(ChannelKind.LIVE, kind("CNN International", "http://host/cnn.ts", "News"))
    }

    @Test
    fun mp4_without_other_hints_is_movie() {
        assertEquals(ChannelKind.MOVIE, kind("Whiplash", "http://host/w.mp4"))
    }

    @Test
    fun m3u8_url_is_live() {
        assertEquals(ChannelKind.LIVE, kind("Sky Sports", "http://host/sky/index.m3u8"))
    }

    @Test
    fun trailing_year_tag_with_unknown_extension_is_movie() {
        assertEquals(ChannelKind.MOVIE, kind("Oppenheimer (2023)", "http://host/stream/777"))
    }

    @Test
    fun no_signals_at_all_defaults_to_live() {
        assertEquals(ChannelKind.LIVE, kind("BBC One", "http://host/stream/1"))
    }

    @Test
    fun decorative_separators_are_detected() {
        assertTrue(ContentClassifier.isSeparator("#### SPORTS ####"))
        assertTrue(ContentClassifier.isSeparator("===== FR | CINEMA ====="))
        assertTrue(ContentClassifier.isSeparator("----------------"))
        assertTrue(ContentClassifier.isSeparator("●●● VOD ●●●"))
    }

    @Test
    fun real_channel_names_are_not_separators() {
        assertFalse(ContentClassifier.isSeparator("BBC One"))
        assertFalse(ContentClassifier.isSeparator("M6"))
        assertFalse(ContentClassifier.isSeparator("Rock-FM 95.5"))
    }

    @Test
    fun series_key_strips_separators_and_collapses_spaces() {
        val c = ContentClassifier.classify("Better  Call Saul - S03E07 - Expenses", "http://h/x.mkv", null)
        assertEquals("Better Call Saul", c.seriesKey)
        assertEquals(3, c.season)
        assertEquals(7, c.episode)
    }
}
