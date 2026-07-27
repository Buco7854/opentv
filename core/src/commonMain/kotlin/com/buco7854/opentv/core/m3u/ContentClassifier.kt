package com.buco7854.opentv.core.m3u

import com.buco7854.opentv.core.model.ChannelKind

class Classification(
    val kind: Int,
    val seriesKey: String?,
    val season: Int?,
    val episode: Int?,
)

/**
 * Classifies an M3U entry as live/movie/series by layering signals in order of
 * reliability: URL path (/live//movie//series/) > title episode marker >
 * group-title keyword > file extension > trailing year tag.
 */
object ContentClassifier {

    // S01E02 / S01 EP 02 / S01.E02 / S1-E2 ...
    private val SEASON_EPISODE = Regex("""(?i)\bS(\d{1,2})\s*[._\- ]?\s*EP?\.?\s*(\d{1,4})\b""")
    // 1x05 / 01x123
    private val CROSS_EPISODE = Regex("""\b(\d{1,2})x(\d{2,4})\b""")
    // Season 1 Episode 2 (and "Saison/Temporada" variants)
    private val WORDY_EPISODE =
        Regex("""(?i)\b(?:season|saison|temporada)\s*(\d{1,2})\b.{0,24}?\b(?:episode|episodio|ep)\s*\.?\s*(\d{1,4})\b""")
    // EP 7 / Ep.07 on its own (season unknown)
    private val BARE_EPISODE = Regex("""(?i)\bEP\.?\s*(\d{1,4})\b""")

    private val SERIES_GROUP_HINTS =
        listOf("series", "série", "serie", "serial", "box set", "boxset", "show", "dizi", "episode")
    private val MOVIE_GROUP_HINTS =
        listOf("movie", "vod", "film", "cine", "pelicula", "película")

    private val VOD_EXTENSIONS =
        setOf("mp4", "mkv", "avi", "m4v", "mov", "webm", "flv", "wmv", "mpg", "mpeg", "3gp")
    private val LIVE_EXTENSIONS = setOf("ts", "m3u8", "m3u")

    private val YEAR_TAG = Regex("""[(\[](19|20)\d{2}[)\]]\s*$""")

    private val SEPARATOR = Regex("""^[#=\-_*●•►◄|~+\s]{2,}.*[#=\-_*●•►◄|~+\s]{2,}$""")

    fun isSeparator(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return true
        if (trimmed.none { it.isLetterOrDigit() }) return true
        return SEPARATOR.matches(trimmed)
    }

    fun classify(name: String, url: String, groupTitle: String?): Classification {
        val path = url.substringAfter("://", url).substringBefore('?').substringBefore('#')
        val kindFromUrl = when {
            path.contains("/movie/") -> ChannelKind.MOVIE
            path.contains("/movies/") -> ChannelKind.MOVIE
            path.contains("/series/") -> ChannelKind.SERIES
            path.contains("/live/") -> ChannelKind.LIVE
            else -> null
        }

        val episodeMatch = findEpisodeMarker(name)
        val group = groupTitle?.lowercase() ?: ""
        val kindFromGroup = when {
            SERIES_GROUP_HINTS.any { group.contains(it) } -> ChannelKind.SERIES
            MOVIE_GROUP_HINTS.any { group.contains(it) } -> ChannelKind.MOVIE
            else -> null
        }
        val extension = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()
        val kindFromExtension = when (extension) {
            in VOD_EXTENSIONS -> ChannelKind.MOVIE
            in LIVE_EXTENSIONS -> ChannelKind.LIVE
            else -> null
        }

        val kind = kindFromUrl
            ?: when {
                // Strong marker beats extension: series are often served as .ts.
                episodeMatch != null && episodeMatch.strong -> ChannelKind.SERIES
                // Weak markers (1x05, EP 7) only count with a corroborating signal.
                episodeMatch != null &&
                    (kindFromGroup == ChannelKind.SERIES || kindFromExtension == ChannelKind.MOVIE) ->
                    ChannelKind.SERIES
                kindFromGroup != null -> kindFromGroup
                kindFromExtension != null -> kindFromExtension
                YEAR_TAG.containsMatchIn(name.trim()) -> ChannelKind.MOVIE
                else -> ChannelKind.LIVE
            }

        if (kind != ChannelKind.SERIES) {
            return Classification(kind, null, null, null)
        }
        return asSeries(name)
    }

    /** Treat [name] as a series episode regardless of other signals. */
    fun asSeries(name: String): Classification {
        val marker = findEpisodeMarker(name)
        val seriesKey = marker
            ?.let { name.substring(0, it.range.first).trim(' ', '-', '_', ':', '.', ',', '|') }
            ?.takeIf { it.isNotEmpty() }
            ?: name.trim()
        return Classification(
            kind = ChannelKind.SERIES,
            seriesKey = seriesKey.replace(Regex("""\s+"""), " "),
            season = marker?.season,
            episode = marker?.episode,
        )
    }

    private class EpisodeMarker(val range: IntRange, val season: Int?, val episode: Int?, val strong: Boolean)

    private fun findEpisodeMarker(name: String): EpisodeMarker? {
        SEASON_EPISODE.find(name)?.let {
            return EpisodeMarker(it.range, it.groupValues[1].toIntOrNull(), it.groupValues[2].toIntOrNull(), strong = true)
        }
        WORDY_EPISODE.find(name)?.let {
            return EpisodeMarker(it.range, it.groupValues[1].toIntOrNull(), it.groupValues[2].toIntOrNull(), strong = true)
        }
        CROSS_EPISODE.find(name)?.let {
            return EpisodeMarker(it.range, it.groupValues[1].toIntOrNull(), it.groupValues[2].toIntOrNull(), strong = false)
        }
        BARE_EPISODE.find(name)?.let {
            return EpisodeMarker(it.range, null, it.groupValues[1].toIntOrNull(), strong = false)
        }
        return null
    }
}
