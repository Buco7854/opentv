package com.buco7854.opentv.data.catchup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class CatchupTest {

    // Fixed instant: 2023-11-14 22:13:20 UTC = 1700000000 s.
    private val start = 1_700_000_000_000L
    private val end = start + 90 * 60_000L // +90 min
    private val now = end + 60 * 60_000L

    init {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @Test
    fun `unix start and duration placeholders`() {
        val out = Catchup.fromTemplate(
            "http://h/stream?utc=\${start}&dur=\${duration}", start, end, now,
        )
        assertEquals("http://h/stream?utc=1700000000&dur=5400", out)
    }

    @Test
    fun `brace style utc and utcend`() {
        val out = Catchup.fromTemplate("http://h/{utc}-{utcend}.ts", start, end, now)
        assertEquals("http://h/1700000000-1700005400.ts", out)
    }

    @Test
    fun `format spec start with date pattern`() {
        val out = Catchup.fromTemplate("http://h/\${start:YYYY-MM-DD-HH-mm}.m3u8", start, end, now)
        assertEquals("http://h/2023-11-14-22-13.m3u8", out)
    }

    @Test
    fun `strftime style tokens`() {
        val out = Catchup.fromTemplate("http://h/{utc:%Y%m%d%H%M%S}.ts", start, end, now)
        assertEquals("http://h/20231114221320.ts", out)
    }

    @Test
    fun `bare date components`() {
        val out = Catchup.fromTemplate("http://h/{Y}/{m}/{d}/{H}{M}.ts", start, end, now)
        assertEquals("http://h/2023/11/14/2213.ts", out)
    }

    @Test
    fun `offset is seconds since start`() {
        val out = Catchup.fromTemplate("http://h/?o=\${offset}", start, end, now)
        // now is 90min + 60min after start = 150 min = 9000 s.
        assertEquals("http://h/?o=9000", out)
    }

    @Test
    fun `xtream timeshift shape`() {
        val out = Catchup.xtreamTimeshift("http://host:8080", "u", "p", 42, start, 90)
        assertEquals("http://host:8080/timeshift/u/p/90/2023-11-14:22-13/42.ts", out)
    }

    @Test
    fun `template without placeholders is returned verbatim`() {
        val out = Catchup.fromTemplate("http://h/fixed.ts", start, end, now)
        assertTrue(out.startsWith("http://h/"))
    }
}
