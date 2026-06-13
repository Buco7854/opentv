package com.buco7854.opentv.data.catchup

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Builds a catch-up (timeshift) stream URL for a past programme.
 *
 * Two worlds:
 *  - M3U playlists declare a `catchup-source` template with placeholders
 *    (`${start}`, `{utc}`, `${start:YYYY-MM-DD-HH-mm}`, ...). This is the
 *    interoperable standard used by TiviMate and friends.
 *  - Xtream panels expose a `/timeshift/USER/PASS/DURATION/Y-m-d:H-i/ID.ts`
 *    endpoint built from the live stream id.
 */
object Catchup {

    /** Substitute placeholders in an M3U `catchup-source` template. */
    fun fromTemplate(template: String, startMs: Long, endMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        val startSec = startMs / 1000
        val endSec = endMs / 1000
        val durationSec = ((endMs - startMs) / 1000).coerceAtLeast(1)
        val offsetSec = ((nowMs - startMs) / 1000).coerceAtLeast(0)

        var result = template

        // Format-spec placeholders first: ${start:FMT}, {utc:FMT}, ${end:FMT}, etc.
        result = FORMAT_SPEC.replace(result) { match ->
            val token = match.groupValues[1].lowercase(Locale.ROOT)
            val fmt = match.groupValues[2]
            val baseMs = if (token in END_TOKENS) endMs else startMs
            formatTime(baseMs, fmt)
        }

        // Bare numeric placeholders.
        val subs = mapOf(
            "utc" to startSec, "start" to startSec, "timestamp" to startSec,
            "utcend" to endSec, "end" to endSec, "stop" to endSec,
            "duration" to durationSec, "offset" to offsetSec,
        )
        for ((key, value) in subs) {
            result = result.replace("\${$key}", value.toString())
                .replace("{$key}", value.toString())
        }

        // Bare date-component tokens (start time): {Y} {m} {d} {H} {M} {S}.
        val cal = Calendar.getInstance(TimeZone.getDefault()).apply { timeInMillis = startMs }
        val comps = mapOf(
            "Y" to "%04d".format(cal.get(Calendar.YEAR)),
            "m" to "%02d".format(cal.get(Calendar.MONTH) + 1),
            "d" to "%02d".format(cal.get(Calendar.DAY_OF_MONTH)),
            "H" to "%02d".format(cal.get(Calendar.HOUR_OF_DAY)),
            "M" to "%02d".format(cal.get(Calendar.MINUTE)),
            "S" to "%02d".format(cal.get(Calendar.SECOND)),
        )
        for ((key, value) in comps) {
            result = result.replace("{$key}", value).replace("\${$key}", value)
        }
        return result
    }

    /** Xtream timeshift endpoint for a live stream id. */
    fun xtreamTimeshift(base: String, user: String, pass: String, streamId: Long, startMs: Long, durationMinutes: Int): String {
        val start = formatTime(startMs, "Y-m-d:H-M")
        return "$base/timeshift/$user/$pass/${durationMinutes.coerceAtLeast(1)}/$start/$streamId.ts"
    }

    private val END_TOKENS = setOf("end", "utcend", "stop")
    private val FORMAT_SPEC = Regex("""\$?\{(utc|utcend|start|end|stop|timestamp):([^}]+)\}""")

    /**
     * Formats [ms] using a lenient pattern mixing strftime (%Y %m %d %H %M %S)
     * and token (Y/YYYY, m/MM, d/DD, H/HH, M/MM-as-minute, S/SS) styles.
     */
    private fun formatTime(ms: Long, pattern: String): String {
        val cal = Calendar.getInstance(TimeZone.getDefault()).apply { timeInMillis = ms }
        val y = cal.get(Calendar.YEAR)
        val mo = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)
        val h = cal.get(Calendar.HOUR_OF_DAY)
        val mi = cal.get(Calendar.MINUTE)
        val s = cal.get(Calendar.SECOND)
        val yyyy = "%04d".format(y)
        val mm = "%02d".format(mo)
        val dd = "%02d".format(d)
        val hh = "%02d".format(h)
        val min = "%02d".format(mi)
        val ss = "%02d".format(s)
        return pattern
            // strftime tokens.
            .replace("%Y", yyyy).replace("%m", mm).replace("%d", dd)
            .replace("%H", hh).replace("%M", min).replace("%S", ss)
            // Multi-char Java-style tokens (case-sensitive: MM=month, mm=minute).
            .replace("YYYY", yyyy).replace("yyyy", yyyy)
            .replace("MM", mm).replace("mm", min)
            .replace("DD", dd).replace("dd", dd)
            .replace("HH", hh).replace("hh", hh)
            .replace("SS", ss).replace("ss", ss)
            // Single-letter M3U tokens last (m=month, M=minute).
            .replace("Y", yyyy).replace("m", mm).replace("d", dd)
            .replace("H", hh).replace("M", min).replace("S", ss)
    }
}
