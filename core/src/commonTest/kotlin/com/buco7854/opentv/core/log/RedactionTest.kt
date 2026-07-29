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
    fun `bare xtream stream paths with a query or fragment are masked`() {
        val bare = ProviderSecrets.redact(
            "http://h.example/bob/hunter2/441.ts?retry=1 " +
                "http://h.example/alice/secret99/12#cue " +
                "http://h.example/carol/password3/99.ts: upstream failed",
        )
        assertFalse(bare.contains("bob"))
        assertFalse(bare.contains("hunter2"))
        assertFalse(bare.contains("alice"))
        assertFalse(bare.contains("secret99"))
        assertFalse(bare.contains("carol"))
        assertFalse(bare.contains("password3"))
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

    @Test
    fun `hub bearer tokens are masked`() {
        val header = ProviderSecrets.redact("HTTP 401 (Authorization: Bearer abc.def-123)")
        assertFalse(header.contains("abc.def-123"))
        assertTrue(header.contains("authorization: •••"))
    }

    @Test
    fun `quoted hub bearer tokens are masked`() {
        val header = ProviderSecrets.redact(
            """headers={"Authorization": "Bearer quoted.session-token"} """ +
                """map={Authorization=[Bearer listed.session-token]}""",
        )
        assertFalse(header.contains("quoted.session-token"))
        assertFalse(header.contains("listed.session-token"))
        assertTrue(header.contains("authorization: •••"))
    }

    @Test
    fun `hub download file capability is masked`() {
        // The pull URL is persisted and can reach a log line; its token grants
        // file access until it expires.
        val url = ProviderSecrets.redact(
            "failed https://hub.lan/api/v1/downloads/7/file?token=f.AbCdEf123&save=1"
        )
        assertFalse(url.contains("f.AbCdEf123"))
        assertTrue(url.contains("token=•••"))
    }

    @Test
    fun `hub capability query tokens are masked`() {
        val url = ProviderSecrets.redact(
            "failed ws://hub.lan/api/v1/playback/L1/ws?ws_token=eyJhbGciOi " +
                "and /stream?u=streamCapability123&sid=session42&g=grant42",
        )
        assertFalse(url.contains("eyJhbGciOi"))
        assertFalse(url.contains("streamCapability123"))
        assertFalse(url.contains("session42"))
        assertFalse(url.contains("grant42"))
        assertTrue(url.contains("ws_token=•••"))
        assertTrue(url.contains("u=•••"))
        assertTrue(url.contains("sid=•••"))
        assertTrue(url.contains("g=•••"))
    }

    @Test
    fun `device link and serialized session tokens are masked`() {
        val value = ProviderSecrets.redact(
            """open https://hub.lan/link#t=link-fragment """ +
                """{"linkToken":"link-json","pollToken":"poll-json","sessionToken":"session-json"}""",
        )
        assertFalse(value.contains("link-fragment"))
        assertFalse(value.contains("link-json"))
        assertFalse(value.contains("poll-json"))
        assertFalse(value.contains("session-json"))
    }

    @Test
    fun `serialized provider credentials are masked`() {
        val value = ProviderSecrets.redact(
            """{"username":"provider-user","password":"provider-pass","api_key":"provider-key"}""",
        )
        assertFalse(value.contains("provider-user"))
        assertFalse(value.contains("provider-pass"))
        assertFalse(value.contains("provider-key"))
    }
}
