package com.buco7854.opentv.data.m3u

import com.buco7854.opentv.data.db.ChannelKind
import java.util.Locale

class Classification(
    val kind: Int,
    val seriesKey: String?,
    val season: Int?,
    val episode: Int?,
)

/**
 * Decides whether an M3U entry is a live channel, a movie, or a series episode.
 *
 * M3U is a flat format with no real content type, so every player has to guess.
 * Guessing from the file extension alone (the common approach) breaks constantly:
 * many providers serve series episodes over `.ts` URLs, hide extensions entirely,
 * or put movies behind extensionless redirect links. We therefore layer several
 * signals and trust them in order of reliability:
 *
 *  1. Xtream-style URL path (`/live/`, `/movie/`, `/series/`) - this mirrors the
 *     provider's own server-side database, so it is authoritative when present.
 *  2. An episode marker in the title (S01E02, 1x05, EP 7, "Season 1 Episode 2") -
 *     near-zero false positives, and crucially it wins over a `.ts` extension.
 *  3. Keywords in the group-title (SERIES, VOD, FILM, ...) - providers usually
 *     label their categories even when individual entries are messy.
 *  4. The file extension (.mp4/.mkv/... = VOD, .ts/.m3u8/none = live).
 *  5. A trailing year tag like "(2021)" as a last-resort movie hint.
 *
 * Decorative separator entries ("#### SPORTS ####") that some providers embed
 * are detected so they can be dropped instead of being counted as channels.
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

    // Entries that are visual separators, not playable content.
    private val SEPARATOR = Regex("""^[#=\-_*●•►◄|~+\s]{2,}.*[#=\-_*●•►◄|~+\s]{2,}$""")

    fun isSeparator(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return true
        // Entirely decorative (no letters or digits at all).
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
        val group = groupTitle?.lowercase(Locale.ROOT) ?: ""
        val kindFromGroup = when {
            SERIES_GROUP_HINTS.any { group.contains(it) } -> ChannelKind.SERIES
            MOVIE_GROUP_HINTS.any { group.contains(it) } -> ChannelKind.MOVIE
            else -> null
        }
        val extension = path.substringAfterLast('/').substringAfterLast('.', "").lowercase(Locale.ROOT)
        val kindFromExtension = when (extension) {
            in VOD_EXTENSIONS -> ChannelKind.MOVIE
            in LIVE_EXTENSIONS -> ChannelKind.LIVE
            else -> null
        }

        val kind = kindFromUrl
            ?: when {
                // A strong episode marker (S01E02, "Season 1 Episode 2") beats the
                // extension: providers routinely serve series episodes as .ts
                // streams, which extension-only logic would dump into live TV.
                episodeMatch != null && episodeMatch.strong -> ChannelKind.SERIES
                // Weak markers (1x05, EP 7) can collide with live channel names,
                // so they only count when another signal also points away from live.
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

        val seriesKey = episodeMatch
            ?.let { name.substring(0, it.range.first).trim(' ', '-', '_', ':', '.', ',', '|') }
            ?.takeIf { it.isNotEmpty() }
            ?: name.trim()
        return Classification(
            kind = ChannelKind.SERIES,
            seriesKey = seriesKey.replace(Regex("""\s+"""), " "),
            season = episodeMatch?.season,
            episode = episodeMatch?.episode,
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
