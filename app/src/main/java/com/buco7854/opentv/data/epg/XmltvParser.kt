package com.buco7854.opentv.data.epg

import android.util.Xml
import com.buco7854.opentv.data.db.ProgrammeEntity
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Streaming XMLTV parser. To keep both memory and storage small it only keeps
 * programmes for channel ids that actually exist in the playlist, and only
 * inside a time window (a couple of hours back to ~2 days ahead).
 */
object XmltvParser {

    fun parse(
        input: InputStream,
        playlistId: Long,
        wantedChannelIds: Set<String>,
        windowStartMs: Long,
        windowEndMs: Long,
        onBatch: (List<ProgrammeEntity>) -> Unit,
    ) {
        val zoned = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
        val plain = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        fun parseTime(value: String?): Long? {
            if (value.isNullOrBlank()) return null
            return try {
                if (value.contains(' ') || value.contains('+') || value.contains('-')) {
                    zoned.parse(value)?.time
                } else {
                    plain.parse(value)?.time
                }
            } catch (_: ParseException) {
                null
            }
        }

        val parser = Xml.newPullParser()
        parser.setInput(input, null)

        val batch = ArrayList<ProgrammeEntity>(500)
        var channel: String? = null
        var start: Long? = null
        var end: Long? = null
        var title: String? = null
        var desc: String? = null
        var inProgramme = false
        var currentTag: String? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "programme" -> {
                        inProgramme = true
                        channel = parser.getAttributeValue(null, "channel")
                        start = parseTime(parser.getAttributeValue(null, "start"))
                        end = parseTime(parser.getAttributeValue(null, "stop"))
                        title = null
                        desc = null
                    }
                    "title", "desc" -> if (inProgramme) currentTag = parser.name
                }
                XmlPullParser.TEXT -> when (currentTag) {
                    "title" -> title = (title ?: "") + parser.text
                    "desc" -> desc = (desc ?: "") + parser.text
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "title", "desc" -> currentTag = null
                    "programme" -> {
                        val ch = channel
                        val s = start
                        val e = end
                        if (inProgramme && ch != null && s != null && e != null &&
                            ch in wantedChannelIds && e > windowStartMs && s < windowEndMs
                        ) {
                            batch.add(
                                ProgrammeEntity(
                                    playlistId = playlistId,
                                    tvgId = ch,
                                    title = title?.trim().orEmpty().ifEmpty { "Untitled" },
                                    description = desc?.trim()?.takeIf { it.isNotEmpty() },
                                    startMs = s,
                                    endMs = e,
                                )
                            )
                            if (batch.size >= 500) {
                                onBatch(ArrayList(batch))
                                batch.clear()
                            }
                        }
                        inProgramme = false
                    }
                }
            }
            event = parser.next()
        }
        if (batch.isNotEmpty()) onBatch(batch)
    }
}
