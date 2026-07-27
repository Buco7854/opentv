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

    private val LANG_PREFIX = Regex("""^\s*[A-Z]{2,3}\s*[-|•]\s*""")

    private val BRACKETED_YEAR = Regex("""[\[(]\s*((?:19|20)\d{2})\s*[)\]]""")
    private val BARE_YEAR = Regex("""\b((?:19|20)\d{2})\b""")
    private val SEPARATOR_RUNS = Regex("""[\s._\-|]{2,}""")

    fun clean(raw: String): Pair<String, String?> {
        val releaseStyle = !raw.contains(' ') && raw.count { it == '.' } >= 2
        var title = if (releaseStyle) raw.replace('.', ' ') else raw

        val yearMatch = BRACKETED_YEAR.find(title)
            ?: BARE_YEAR.find(title).takeIf { releaseStyle }
        val year = yearMatch?.groupValues?.get(1)

        title = title
            .replace(BRACKETED, " ")
            .replace(QUALITY, " ")
            .replace(LANG_PREFIX, "")
        if (year != null && releaseStyle) {
            title = title.replaceFirst(Regex("""\b$year\b"""), " ")
        }
        title = title
            .replace(SEPARATOR_RUNS, " ")
            .trim(' ', '-', '_', '|', ':', '.', ',')
            .replace(Regex("""\s+"""), " ")
        return title to year
    }
}
