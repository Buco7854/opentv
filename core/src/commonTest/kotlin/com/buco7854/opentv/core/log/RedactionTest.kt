package com.buco7854.opentv.core.log

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RedactionTest {

    @Test
    fun `query credentials are masked`() {
        val redacted = ProviderSecrets.redact(
            "HTTP 403 for http://host/get.php?username=bob&password=hunter2&type=m3u"
        )
        assertFalse(redacted.contains("bob"))
        assertFalse(redacted.contains("hunter2"))
        assertTrue(redacted.contains("username=•••"))
        assertTrue(redacted.contains("password=•••"))
    }

    @Test
    fun `xtream path credentials are masked in every stream kind`() {
        // ffmpeg echoes the input URL in its errors, and that line is served to the viewer.
        val ffmpeg = ProviderSecrets.redact(
            "http://host:8080/movie/bob/hunter2/8812.mkv: Server returned 404 Not Found"
        )
        assertFalse(ffmpeg.contains("bob"))
        assertFalse(ffmpeg.contains("hunter2"))
        assertTrue(ffmpeg.contains("8812.mkv"))

        listOf("live", "movie", "movies", "series", "timeshift").forEach { kind ->
            val masked = ProviderSecrets.redact("http://h:8080/$kind/alice/secret99/1/x.ts")
            assertFalse(masked.contains("alice"), "leaked username via /$kind/")
            assertFalse(masked.contains("secret99"), "leaked password via /$kind/")
        }
    }

    @Test
    fun `bare xtream stream paths are masked`() {
        val bare = ProviderSecrets.redact("could not open http://h.example/bob/hunter2/441.ts")
        assertFalse(bare.contains("bob"))
        assertFalse(bare.contains("hunter2"))
        assertTrue(bare.contains("441.ts"))
    }

    @Test
    fun `percent-encoded credentials stay one segment and are masked`() {
        val encoded = ProviderSecrets.redact("http://h:8080/live/a%2Fb/p%20ss%231/42.ts")
        assertFalse(encoded.contains("a%2Fb"))
        assertFalse(encoded.contains("p%20ss%231"))
        assertTrue(encoded.contains("42.ts"))
    }

    @Test
    fun `ordinary urls and messages are left alone`() {
        val epg = "http://epg.example/guide/all.xml.gz"
        assertEquals(epg, ProviderSecrets.redact(epg))
        assertEquals("ffmpeg produced no output", ProviderSecrets.redact("ffmpeg produced no output"))
    }
}
