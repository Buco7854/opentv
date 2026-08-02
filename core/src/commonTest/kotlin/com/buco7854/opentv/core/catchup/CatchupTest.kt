package com.buco7854.opentv.core.catchup

import kotlinx.datetime.TimeZone
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CatchupTest {

    // Fixed instant: 2023-11-14 22:13:20 UTC = 1700000000 s.
    private val start = 1_700_000_000_000L
    private val end = start + 90 * 60_000L // +90 min
    private val now = end + 60 * 60_000L
    private val utc = TimeZone.UTC

    private fun fromTemplate(template: String) =
        Catchup.fromTemplate(template, start, end, now, utc)

    @Test
    fun unix_start_and_duration_placeholders() {
        val out = fromTemplate("http://h/stream?utc=\${start}&dur=\${duration}")
        assertEquals("http://h/stream?utc=1700000000&dur=5400", out)
    }

    @Test
    fun brace_style_utc_and_utcend() {
        val out = fromTemplate("http://h/{utc}-{utcend}.ts")
        assertEquals("http://h/1700000000-1700005400.ts", out)
    }

    @Test
    fun format_spec_start_with_date_pattern() {
        val out = fromTemplate("http://h/\${start:YYYY-MM-DD-HH-mm}.m3u8")
        assertEquals("http://h/2023-11-14-22-13.m3u8", out)
    }

    @Test
    fun strftime_style_tokens() {
        val out = fromTemplate("http://h/{utc:%Y%m%d%H%M%S}.ts")
        assertEquals("http://h/20231114221320.ts", out)
    }

    @Test
    fun bare_date_components() {
        val out = fromTemplate("http://h/{Y}/{m}/{d}/{H}{M}.ts")
        assertEquals("http://h/2023/11/14/2213.ts", out)
    }

    @Test
    fun lutc_and_now_are_current_time() {
        val out = fromTemplate("http://h/?utc={utc}&lutc={lutc}&n=\${now}")
        val nowSec = now / 1000
        assertEquals("http://h/?utc=1700000000&lutc=$nowSec&n=$nowSec", out)
    }

    @Test
    fun offset_is_seconds_since_start() {
        val out = fromTemplate("http://h/?o=\${offset}")
        // now is 90min + 60min after start = 150 min = 9000 s.
        assertEquals("http://h/?o=9000", out)
    }

    @Test
    fun xtream_timeshift_shape() {
        val out = Catchup.xtreamTimeshift("http://host:8080", "u", "p", 42, start, 90, utc)
        assertEquals("http://host:8080/timeshift/u/p/90/2023-11-14:22-13/42.ts", out)
    }

    @Test
    fun xtream_timeshift_start_is_the_panel_wall_clock() {
        // Panel two hours ahead: START in the URL is the panel's wall clock
        // (22:13 UTC -> 00:13). Wrong tz here makes the archive start at the wrong moment.
        val plus2 = TimeZone.of("UTC+02:00")
        val out = Catchup.xtreamTimeshift("http://host:8080", "u", "p", 42, start, 90, plus2)
        assertEquals("http://host:8080/timeshift/u/p/90/2023-11-15:00-13/42.ts", out)
    }

    @Test
    fun template_without_placeholders_is_returned_verbatim() {
        val out = fromTemplate("http://h/fixed.ts")
        assertTrue(out.startsWith("http://h/"))
    }

    @Test
    fun named_panel_timezone_applies_dst_to_xtream_wall_clock() {
        val london = TimeZone.of("Europe/London")
        val beforeJump = Instant.parse("2024-03-31T00:30:00Z").toEpochMilliseconds()
        val afterJump = Instant.parse("2024-03-31T01:30:00Z").toEpochMilliseconds()

        assertTrue(
            Catchup.xtreamTimeshift("http://host", "u", "p", 42, beforeJump, 60, london)
                .contains("/2024-03-31:00-30/"),
        )
        assertTrue(
            Catchup.xtreamTimeshift("http://host", "u", "p", 42, afterJump, 60, london)
                .contains("/2024-03-31:02-30/"),
        )
    }

    @Test
    fun xtream_wall_clock_protocol_is_ambiguous_during_fall_back() {
        val london = TimeZone.of("Europe/London")
        val firstOneThirty = Instant.parse("2024-10-27T00:30:00Z").toEpochMilliseconds()
        val secondOneThirty = Instant.parse("2024-10-27T01:30:00Z").toEpochMilliseconds()

        val first = Catchup.xtreamTimeshift("http://host", "u", "p", 42, firstOneThirty, 60, london)
        val second = Catchup.xtreamTimeshift("http://host", "u", "p", 42, secondOneThirty, 60, london)

        assertEquals(first, second)
    }
}
