package com.buco7854.opentv.core.xtream

import kotlinx.coroutines.test.runTest
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
    fun stream_urls_escape_credentials_that_a_path_segment_cannot_carry() {
        val awkward = XtreamCredentials("http://host.example:8080", "a/b", "p ss#1")
        // Raw, "a/b" would add a path segment and "#1" would start a fragment.
        assertEquals(
            "http://host.example:8080/live/a%2Fb/p%20ss%231/42.ts",
            Xtream.liveUrl(awkward, 42),
        )
        // Everything a segment may legally hold is left byte-identical.
        val legal = XtreamCredentials("http://host.example:8080", "a.b-c~d", "p+s=w!")
        assertEquals(
            "http://host.example:8080/live/a.b-c~d/p+s=w!/42.ts",
            Xtream.liveUrl(legal, 42),
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

    @Test
    fun xmltv_url_encodes_credentials() {
        val awkward = XtreamCredentials("http://host.example:8080", "a&b", "p ss#1")
        assertEquals(
            "http://host.example:8080/xmltv.php?username=a%26b&password=p%20ss%231",
            Xtream.xmltvUrl(awkward),
        )
    }

    @Test
    fun epg_text_decodes_real_base64_and_leaves_plain_words_alone() = runTest {
        val body = """
            {"epg_listings":[
              {"start_timestamp":"1700000000","stop_timestamp":"1700003600",
               "title":"TGUgSm91cm5hbCBkdSBTb2ly","description":"TcOpdMOpbw=="},
              {"start_timestamp":"1700003600","stop_timestamp":"1700007200",
               "title":"Documentaire","description":"News"}
            ]}
        """.trimIndent()
        val api = XtreamApi { body }

        val entries = api.fetchChannelEpg(creds, 42)

        assertEquals("Le Journal du Soir", entries[0].title)
        assertEquals("M\u00e9t\u00e9o", entries[0].description)
        assertEquals("Documentaire", entries[1].title)
        assertEquals("News", entries[1].description)
    }
}
