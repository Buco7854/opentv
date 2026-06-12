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
