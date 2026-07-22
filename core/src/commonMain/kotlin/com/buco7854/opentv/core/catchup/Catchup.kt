package com.buco7854.opentv.core.catchup

import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Catch-up (timeshift) stream URLs: M3U `catchup-source` templates, or the
 * Xtream `/timeshift/.../ID.ts` endpoint.
 */
object Catchup {

    /** Substitute placeholders in an M3U `catchup-source` template. */
    fun fromTemplate(
        template: String,
        startMs: Long,
        endMs: Long,
        nowMs: Long = Clock.System.now().toEpochMilliseconds(),
        tz: TimeZone = TimeZone.currentSystemDefault(),
    ): String {
        val startSec = startMs / 1000
        val endSec = endMs / 1000
        val durationSec = ((endMs - startMs) / 1000).coerceAtLeast(1)
        val offsetSec = ((nowMs - startMs) / 1000).coerceAtLeast(0)

        var result = template

        // Format-spec placeholders first: ${start:FMT}, {utc:FMT}, etc.
        result = FORMAT_SPEC.replace(result) { match ->
            val token = match.groupValues[1].lowercase()
            val fmt = match.groupValues[2]
            val baseMs = if (token in END_TOKENS) endMs else startMs
            formatTime(baseMs, fmt, tz)
        }

        val nowSec = nowMs / 1000
        val subs = mapOf(
            "utc" to startSec, "start" to startSec, "timestamp" to startSec,
            "utcend" to endSec, "end" to endSec, "stop" to endSec,
            "duration" to durationSec, "offset" to offsetSec,
            "lutc" to nowSec, "now" to nowSec,
        )
        for ((key, value) in subs) {
            result = result.replace("\${$key}", value.toString())
                .replace("{$key}", value.toString())
        }

        // Bare date-component tokens (start time): {Y} {m} {d} {H} {M} {S}.
        val local = localTime(startMs, tz)
        val comps = mapOf(
            "Y" to pad(local.year, 4),
            "m" to pad(local.monthNumber, 2),
            "d" to pad(local.dayOfMonth, 2),
            "H" to pad(local.hour, 2),
            "M" to pad(local.minute, 2),
            "S" to pad(local.second, 2),
        )
        for ((key, value) in comps) {
            result = result.replace("{$key}", value).replace("\${$key}", value)
        }
        return result
    }

    /** Xtream timeshift endpoint for a live stream id. */
    fun xtreamTimeshift(
        base: String,
        user: String,
        pass: String,
        streamId: Long,
        startMs: Long,
        durationMinutes: Int,
        tz: TimeZone = TimeZone.currentSystemDefault(),
    ): String {
        val start = formatTime(startMs, "Y-m-d:H-M", tz)
        return "$base/timeshift/$user/$pass/${durationMinutes.coerceAtLeast(1)}/$start/$streamId.ts"
    }

    private val END_TOKENS = setOf("end", "utcend", "stop")
    private val FORMAT_SPEC = Regex("""\$?\{(utc|utcend|start|end|stop|timestamp):([^}]+)\}""")

    private fun localTime(ms: Long, tz: TimeZone): LocalDateTime =
        Instant.fromEpochMilliseconds(ms).toLocalDateTime(tz)

    private fun pad(value: Int, width: Int) = value.toString().padStart(width, '0')

    /** Lenient formatter mixing strftime (%Y...) and token (YYYY/MM/mm...) styles. */
    private fun formatTime(ms: Long, pattern: String, tz: TimeZone): String {
        val local = localTime(ms, tz)
        val yyyy = pad(local.year, 4)
        val mm = pad(local.monthNumber, 2)
        val dd = pad(local.dayOfMonth, 2)
        val hh = pad(local.hour, 2)
        val min = pad(local.minute, 2)
        val ss = pad(local.second, 2)
        return pattern
            .replace("%Y", yyyy).replace("%m", mm).replace("%d", dd)
            .replace("%H", hh).replace("%M", min).replace("%S", ss)
            // Case-sensitive: MM=month, mm=minute.
            .replace("YYYY", yyyy).replace("yyyy", yyyy)
            .replace("MM", mm).replace("mm", min)
            .replace("DD", dd).replace("dd", dd)
            .replace("HH", hh).replace("hh", hh)
            .replace("SS", ss).replace("ss", ss)
            // Single-letter last: m=month, M=minute.
            .replace("Y", yyyy).replace("m", mm).replace("d", dd)
            .replace("H", hh).replace("M", min).replace("S", ss)
    }
}
