package com.buco7854.opentv.core.epg

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.toInstant

/** Character-at-a-time input so huge guide files stream on any platform. Returns -1 at end. */
fun interface TextSource {
    fun nextChar(): Int
}

class XmltvProgramme(
    val channel: String,
    val title: String,
    val description: String?,
    val startMs: Long,
    val endMs: Long,
)

/**
 * Streaming XMLTV parser. Keeps only wanted channel ids within a time window
 * to bound memory/storage. Hand-rolled tokenizer so the same code runs on
 * every Kotlin target.
 */
object XmltvParser {

    suspend fun parse(
        source: TextSource,
        wantedChannelIds: Set<String>,
        windowStartMs: Long,
        windowEndMs: Long,
        onBatch: suspend (List<XmltvProgramme>) -> Unit,
    ) {
        val batch = ArrayList<XmltvProgramme>(500)
        var channel: String? = null
        var start: Long? = null
        var end: Long? = null
        var title: StringBuilder? = null
        var desc: StringBuilder? = null
        var inProgramme = false
        var capture: StringBuilder? = null

        Tokenizer(source).forEachToken { token ->
            when (token) {
                is Token.StartTag -> when (token.name) {
                    "programme" -> {
                        inProgramme = !token.selfClosing
                        channel = token.attributes["channel"]
                        start = parseTime(token.attributes["start"])
                        end = parseTime(token.attributes["stop"])
                        title = null
                        desc = null
                    }
                    "title" -> if (inProgramme && !token.selfClosing) {
                        title = title ?: StringBuilder()
                        capture = title
                    }
                    "desc" -> if (inProgramme && !token.selfClosing) {
                        desc = desc ?: StringBuilder()
                        capture = desc
                    }
                    else -> {}
                }
                is Token.Text -> capture?.append(token.text)
                is Token.EndTag -> when (token.name) {
                    "title", "desc" -> capture = null
                    "programme" -> {
                        val ch = channel
                        val s = start
                        val e = end
                        if (inProgramme && ch != null && s != null && e != null &&
                            ch in wantedChannelIds && e > windowStartMs && s < windowEndMs
                        ) {
                            batch.add(
                                XmltvProgramme(
                                    channel = ch,
                                    title = title?.toString()?.trim().orEmpty().ifEmpty { "Untitled" },
                                    description = desc?.toString()?.trim()?.takeIf { it.isNotEmpty() },
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
                        capture = null
                    }
                    else -> {}
                }
            }
        }
        if (batch.isNotEmpty()) onBatch(batch)
    }

    /** XMLTV times: "yyyyMMddHHmmss +HHMM" (offset optional, defaults UTC). */
    internal fun parseTime(value: String?): Long? {
        val text = value?.trim() ?: return null
        if (text.length < 14) return null
        val digits = text.take(14)
        if (digits.any { it !in '0'..'9' }) return null
        val local = try {
            LocalDateTime(
                year = digits.substring(0, 4).toInt(),
                monthNumber = digits.substring(4, 6).toInt(),
                dayOfMonth = digits.substring(6, 8).toInt(),
                hour = digits.substring(8, 10).toInt(),
                minute = digits.substring(10, 12).toInt(),
                second = digits.substring(12, 14).toInt(),
            )
        } catch (_: IllegalArgumentException) {
            return null
        }
        val suffix = text.substring(14).trim().replace(":", "")
        val offset = when {
            suffix.isEmpty() -> UtcOffset.ZERO
            suffix.length == 5 && (suffix[0] == '+' || suffix[0] == '-') -> {
                val hours = suffix.substring(1, 3).toIntOrNull() ?: return null
                val minutes = suffix.substring(3, 5).toIntOrNull() ?: return null
                val sign = if (suffix[0] == '-') -1 else 1
                try {
                    UtcOffset(hours = sign * hours, minutes = sign * minutes)
                } catch (_: IllegalArgumentException) {
                    return null
                }
            }
            else -> return null
        }
        return local.toInstant(offset).toEpochMilliseconds()
    }

    private sealed class Token {
        class StartTag(val name: String, val attributes: Map<String, String>, val selfClosing: Boolean) : Token()
        class EndTag(val name: String) : Token()
        class Text(val text: String) : Token()
    }

    private class Tokenizer(private val source: TextSource) {

        private var pushback = -2 // -2 = empty

        private fun read(): Int {
            if (pushback != -2) {
                val c = pushback
                pushback = -2
                return c
            }
            return source.nextChar()
        }

        private fun unread(c: Int) {
            pushback = c
        }

        inline fun forEachToken(handle: (Token) -> Unit) {
            val text = StringBuilder()
            while (true) {
                val c = read()
                if (c == -1) break
                if (c == '<'.code) {
                    if (text.isNotEmpty()) {
                        handle(Token.Text(decodeEntities(text.toString())))
                        text.clear()
                    }
                    readMarkup()?.let(handle)
                } else {
                    text.append(c.toChar())
                }
            }
            if (text.isNotEmpty()) handle(Token.Text(decodeEntities(text.toString())))
        }

        /** Called after '<'; returns a tag token, or null for comments/PIs/doctype. */
        fun readMarkup(): Token? {
            val first = read()
            when (first) {
                -1 -> return null
                '!'.code -> {
                    // Comment, CDATA or DOCTYPE.
                    val second = read()
                    if (second == '-'.code) {
                        skipUntil("-->")
                        return null
                    }
                    if (second == '['.code) {
                        val keyword = StringBuilder()
                        var k = read()
                        while (k != -1 && k != '['.code) {
                            keyword.append(k.toChar())
                            k = read()
                        }
                        if (keyword.toString() == "CDATA") {
                            val content = StringBuilder()
                            collectUntil("]]>", content)
                            return Token.Text(content.toString())
                        }
                        skipUntil("]>")
                        return null
                    }
                    skipUntil(">")
                    return null
                }
                '?'.code -> {
                    skipUntil("?>")
                    return null
                }
                '/'.code -> {
                    val name = StringBuilder()
                    var c = read()
                    while (c != -1 && c != '>'.code) {
                        if (!c.toChar().isWhitespace()) name.append(c.toChar())
                        c = read()
                    }
                    return Token.EndTag(name.toString())
                }
                else -> {
                    unread(first)
                    return readStartTag()
                }
            }
        }

        fun readStartTag(): Token? {
            val name = StringBuilder()
            var c = read()
            while (c != -1 && c != '>'.code && c != '/'.code && !c.toChar().isWhitespace()) {
                name.append(c.toChar())
                c = read()
            }
            val attributes = HashMap<String, String>()
            var selfClosing = false
            loop@ while (c != -1 && c != '>'.code) {
                when {
                    c == '/'.code -> selfClosing = true
                    c.toChar().isWhitespace() -> {}
                    else -> {
                        val attrName = StringBuilder()
                        while (c != -1 && c != '='.code && c != '>'.code && !c.toChar().isWhitespace()) {
                            attrName.append(c.toChar())
                            c = read()
                        }
                        while (c != -1 && c.toChar().isWhitespace()) c = read()
                        if (c == '='.code) {
                            c = read()
                            while (c != -1 && c.toChar().isWhitespace()) c = read()
                            if (c == '"'.code || c == '\''.code) {
                                val quote = c
                                val value = StringBuilder()
                                c = read()
                                while (c != -1 && c != quote) {
                                    value.append(c.toChar())
                                    c = read()
                                }
                                attributes[attrName.toString()] = decodeEntities(value.toString())
                            }
                        } else {
                            continue@loop // valueless attribute; c already holds the next char
                        }
                    }
                }
                c = read()
            }
            if (c == -1 && name.isEmpty()) return null
            return Token.StartTag(name.toString(), attributes, selfClosing)
        }

        private fun skipUntil(terminator: String) {
            collectUntil(terminator, null)
        }

        private fun collectUntil(terminator: String, out: StringBuilder?) {
            // Sliding window of the terminator's length; overflow is content.
            val tail = StringBuilder()
            while (true) {
                val c = read()
                if (c == -1) {
                    out?.append(tail)
                    return
                }
                tail.append(c.toChar())
                if (tail.length > terminator.length) {
                    out?.append(tail[0])
                    tail.deleteAt(0)
                }
                if (tail.length == terminator.length && tail.toString() == terminator) return
            }
        }
    }

    internal fun decodeEntities(text: String): String {
        if ('&' !in text) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != '&') {
                out.append(c)
                i++
                continue
            }
            val semi = text.indexOf(';', i + 1)
            if (semi == -1 || semi - i > 10) {
                out.append(c)
                i++
                continue
            }
            val entity = text.substring(i + 1, semi)
            val decoded = when {
                entity == "amp" -> "&"
                entity == "lt" -> "<"
                entity == "gt" -> ">"
                entity == "quot" -> "\""
                entity == "apos" -> "'"
                entity.startsWith("#x") || entity.startsWith("#X") ->
                    entity.substring(2).toIntOrNull(16)?.let { codePointToString(it) }
                entity.startsWith("#") ->
                    entity.substring(1).toIntOrNull()?.let { codePointToString(it) }
                else -> null
            }
            if (decoded != null) {
                out.append(decoded)
                i = semi + 1
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    private fun codePointToString(codePoint: Int): String? {
        if (codePoint < 0 || codePoint > 0x10FFFF) return null
        return if (codePoint < 0x10000) {
            codePoint.toChar().toString()
        } else {
            val high = ((codePoint - 0x10000) shr 10) + 0xD800
            val low = ((codePoint - 0x10000) and 0x3FF) + 0xDC00
            charArrayOf(high.toChar(), low.toChar()).concatToString()
        }
    }
}
