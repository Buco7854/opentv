package com.buco7854.opentv.core.net

/** Minimal multiplatform URL parsing for normalization and query extraction (explicit port, like OkHttp). */
object Urls {

    class Parts(
        val scheme: String,
        val host: String,
        val port: Int,
        val path: String,
        val query: String?,
    ) {
        fun queryParameter(name: String): String? {
            val q = query ?: return null
            for (pair in q.split('&')) {
                val eq = pair.indexOf('=')
                val key = if (eq >= 0) pair.substring(0, eq) else pair
                if (percentDecode(key) == name) {
                    return if (eq >= 0) percentDecode(pair.substring(eq + 1)) else ""
                }
            }
            return null
        }
    }

    private val HOST_CHARS = Regex("""^[A-Za-z0-9.\-_~%\[\]:]+$""")

    fun parse(url: String): Parts? {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd <= 0) return null
        val scheme = url.substring(0, schemeEnd).lowercase()
        if (scheme != "http" && scheme != "https") return null

        var rest = url.substring(schemeEnd + 3)
        var query: String? = null
        rest.substringBefore('#').let { rest = it }
        val qIndex = rest.indexOf('?')
        if (qIndex >= 0) {
            query = rest.substring(qIndex + 1)
            rest = rest.substring(0, qIndex)
        }
        val slash = rest.indexOf('/')
        val authority = if (slash >= 0) rest.substring(0, slash) else rest
        val path = if (slash >= 0) rest.substring(slash) else "/"

        var host = authority
        var port = if (scheme == "https") 443 else 80
        val colon = authority.lastIndexOf(':')
        // Skip IPv6 literal internals ("[::1]:8080" has the port colon after ']').
        if (colon >= 0 && colon > authority.lastIndexOf(']')) {
            val portText = authority.substring(colon + 1)
            if (portText.isNotEmpty()) {
                port = portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            }
            host = authority.substring(0, colon)
        }
        if (host.isEmpty() || !HOST_CHARS.matches(host)) return null
        return Parts(scheme, host, port, path, query)
    }

    fun percentDecode(text: String): String {
        if ('%' !in text && '+' !in text) return text
        val bytes = ArrayList<Byte>(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '%' && i + 2 < text.length -> {
                    val hex = text.substring(i + 1, i + 3).toIntOrNull(16)
                    if (hex != null) {
                        bytes.add(hex.toByte()); i += 3
                    } else {
                        bytes.add(c.code.toByte()); i++
                    }
                }
                c == '+' -> { bytes.add(' '.code.toByte()); i++ }
                else -> {
                    for (b in c.toString().encodeToByteArray()) bytes.add(b)
                    i++
                }
            }
        }
        return bytes.toByteArray().decodeToString()
    }

    private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

    fun percentEncode(text: String): String = buildString {
        for (b in text.encodeToByteArray()) {
            val c = b.toInt().toChar()
            if (c in UNRESERVED) append(c)
            else append('%').append(((b.toInt() and 0xFF) or 0x100).toString(16).substring(1).uppercase())
        }
    }
}
