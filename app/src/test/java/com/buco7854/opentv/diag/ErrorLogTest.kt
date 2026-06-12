package com.buco7854.opentv.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ErrorLogTest {

    @Test
    fun `credentials are redacted from messages`() {
        val redacted = ErrorLog.redact("HTTP 403 for http://host/get.php?username=bob&password=hunter2&type=m3u")
        assertFalse(redacted.contains("bob"))
        assertFalse(redacted.contains("hunter2"))
        assertTrue(redacted.contains("username=•••"))
        assertTrue(redacted.contains("password=•••"))
    }

    @Test
    fun `xtream path credentials are redacted`() {
        // The dominant Xtream stream-URL shape embeds credentials in the path;
        // Media3 exceptions include the full URI in their messages.
        val live = ErrorLog.redact("uri=http://host:8080/live/bob/hunter2/8812.ts")
        assertFalse(live.contains("bob"))
        assertFalse(live.contains("hunter2"))
        assertTrue(live.contains("8812.ts"))

        val movie = ErrorLog.redact("http://host/movie/alice/secret99/123.mkv failed")
        assertFalse(movie.contains("alice"))
        assertFalse(movie.contains("secret99"))

        val bare = ErrorLog.redact("None of the extractors read uri=http://h.example/bob/hunter2/441.ts")
        assertFalse(bare.contains("bob"))
        assertFalse(bare.contains("hunter2"))
        assertTrue(bare.contains("441.ts"))
    }

    @Test
    fun `redaction leaves ordinary urls alone`() {
        val epg = "http://epg.example/guide/all.xml.gz"
        assertEquals(epg, ErrorLog.redact(epg))
    }

    @Test
    fun `describe falls back to exception class name`() {
        assertEquals("IOException", ErrorLog.describe(IOException()))
        assertEquals("boom", ErrorLog.describe(IOException("boom")))
    }

    @Test
    fun `log records entry with redacted stack trace`() {
        ErrorLog.clear()
        ErrorLog.log("Test", IOException("failed for ?username=alice&password=secret"))
        val entry = ErrorLog.entries.value.first()
        assertEquals("Test", entry.tag)
        assertFalse(entry.message.contains("secret"))
        assertFalse(entry.stackTrace.orEmpty().contains("secret"))
        ErrorLog.clear()
    }
}
