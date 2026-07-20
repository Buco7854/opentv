package com.buco7854.opentv.core.meta

/**
 * Strips provider decorations from titles ("FR - Oppenheimer (2023) [1080p x265]"):
 * language prefixes, brackets, quality/codec tags; returns the year as a separate hint.
 */
object TitleCleaner {
    private val BRACKETED = Regex("""[\[(][^)\]]*[)\]]""")
    private val QUALITY = Regex(
        """(?i)\b(4K|UHD|2160p|1080p|FHD|720p|480p|HEVC|x26[45]|H\.?26[45]|HDR(?:10)?|WEB-?DL|WEBRip|BluRay|BRRip|HDTV|MULTI|VOSTFR|HD|SD)\b"""
    )
    private val LANG_PREFIX = Regex("""^\s*[A-Z]{2,3}\s*[-|:•]\s*""")
    private val YEAR = Regex("""\b(19|20)\d{2}\b""")
    private val SEPARATOR_RUNS = Regex("""[\s._\-|]{2,}""")

    fun clean(raw: String): Pair<String, String?> {
        val year = YEAR.find(raw)?.value
        // Release-style names use dots for spaces ("The.Matrix.1999").
        var title = if (!raw.contains(' ') && raw.count { it == '.' } >= 2) {
            raw.replace('.', ' ')
        } else raw
        title = title
            .replace(BRACKETED, " ")
            .replace(QUALITY, " ")
            .replace(LANG_PREFIX, "")
        year?.let { title = title.replace(it, " ") }
        title = title
            .replace(SEPARATOR_RUNS, " ")
            .trim(' ', '-', '_', '|', ':', '.', ',')
            .replace(Regex("""\s+"""), " ")
        return title to year
    }
}
