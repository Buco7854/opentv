package com.buco7854.opentv.core.xtream

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class XtreamTest {

    private val creds = XtreamCredentials("http://host.example:8080", "alice", "secret")

    @Test
    fun normalize_server_adds_scheme_and_explicit_port() {
        assertEquals("http://host.example:8080", Xtream.normalizeServer("host.example:8080"))
        assertEquals("http://host.example:80", Xtream.normalizeServer("http://host.example/"))
        assertEquals("https://host.example:443", Xtream.normalizeServer("https://host.example"))
        assertNull(Xtream.normalizeServer(""))
        assertNull(Xtream.normalizeServer("ht tp://bad"))
    }

    @Test
    fun stream_url_builders() {
        assertEquals("http://host.example:8080/live/alice/secret/42.ts", Xtream.liveUrl(creds, 42))
        assertEquals("http://host.example:8080/movie/alice/secret/7.mkv", Xtream.vodUrl(creds, 7, "mkv"))
        assertEquals("http://host.example:8080/movie/alice/secret/7.mp4", Xtream.vodUrl(creds, 7, ""))
        assertEquals(
            "http://host.example:8080/series/alice/secret/991.mp4",
            Xtream.episodeUrl(creds, "991", "mp4"),
        )
    }

    @Test
    fun catchup_url_shape() {
        val url = Xtream.catchupUrl(creds, 42, startMs = 1_700_000_000_000, durationMinutes = 90)
        assertTrue(url.startsWith("http://host.example:8080/timeshift/alice/secret/90/"))
        assertTrue(url.endsWith("/42.ts"))
        // start segment is yyyy-MM-dd:HH-mm
        val start = url.removePrefix("http://host.example:8080/timeshift/alice/secret/90/")
            .removeSuffix("/42.ts")
        assertTrue(start.matches(Regex("""\d{4}-\d{2}-\d{2}:\d{2}-\d{2}""")))
    }

    @Test
    fun detect_parses_get_php_urls_only() {
        val detected = Xtream.detect("http://host.example:8080/get.php?username=alice&password=secret&type=m3u_plus")
        assertEquals("http://host.example:8080", detected?.base)
        assertEquals("alice", detected?.user)
        assertEquals("secret", detected?.pass)
        assertNull(Xtream.detect("http://host.example/playlist.m3u8"))
    }
}
