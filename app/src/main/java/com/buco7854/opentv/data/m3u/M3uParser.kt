package com.buco7854.opentv.data.m3u

import com.buco7854.opentv.data.db.ChannelKind
import java.io.BufferedReader
import java.util.Locale

class M3uEntry(
    val name: String,
    val url: String,
    val logo: String?,
    val groupTitle: String,
    val tvgId: String?,
    val kind: Int,
    val seriesKey: String?,
    val season: Int?,
    val episode: Int?,
)

class M3uHeader(val epgUrl: String?)

/**
 * Single-pass streaming parser: reads straight from the network/file stream and
 * emits entries through a callback, so a 50k-line playlist never needs to be
 * held in memory as one big string.
 */
object M3uParser {

    private val ATTR = Regex("""([\w-]+)="([^"]*)"""")
    // S01E02 / S1 E2 / 1x02 style markers used to detect series episodes.
    private val SERIES = Regex("""(?i)\bS(\d{1,2})\s*[._\- ]?\s*E(\d{1,4})\b|\b(\d{1,2})x(\d{2,4})\b""")
    private val VOD_EXTENSIONS = setOf("mp4", "mkv", "avi", "m4v", "mov", "webm", "flv", "wmv", "mpg", "mpeg")

    fun parse(
        reader: BufferedReader,
        onHeader: (M3uHeader) -> Unit = {},
        onEntry: (M3uEntry) -> Unit,
    ) {
        var name = ""
        var logo: String? = null
        var group: String? = null
        var tvgId: String? = null
        var extGrp: String? = null
        var pendingInfo = false

        var line = reader.readLine()
        while (line != null) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("#EXTM3U") -> {
                    val attrs = parseAttrs(trimmed)
                    onHeader(M3uHeader(attrs["url-tvg"] ?: attrs["x-tvg-url"]))
                }
                trimmed.startsWith("#EXTINF", ignoreCase = true) -> {
                    val attrs = parseAttrs(trimmed)
                    name = trimmed.substringAfterLast(',').trim()
                    if (name.isEmpty()) name = attrs["tvg-name"] ?: "Unknown"
                    logo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() }
                    group = attrs["group-title"]?.takeIf { it.isNotBlank() }
                    tvgId = attrs["tvg-id"]?.takeIf { it.isNotBlank() }
                    pendingInfo = true
                }
                trimmed.startsWith("#EXTGRP:", ignoreCase = true) -> {
                    extGrp = trimmed.substringAfter(':').trim().takeIf { it.isNotBlank() }
                }
                trimmed.isNotEmpty() && !trimmed.startsWith("#") && pendingInfo -> {
                    onEntry(buildEntry(name, trimmed, logo, group ?: extGrp, tvgId))
                    pendingInfo = false
                    logo = null; group = null; tvgId = null
                }
            }
            line = reader.readLine()
        }
    }

    private fun parseAttrs(line: String): Map<String, String> =
        ATTR.findAll(line).associate { it.groupValues[1].lowercase(Locale.ROOT) to it.groupValues[2] }

    private fun buildEntry(name: String, url: String, logo: String?, group: String?, tvgId: String?): M3uEntry {
        val kindFromUrl = when {
            url.contains("/movie/") -> ChannelKind.MOVIE
            url.contains("/series/") -> ChannelKind.SERIES
            url.contains("/live/") -> ChannelKind.LIVE
            else -> null
        }
        val extension = url.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "")
            .lowercase(Locale.ROOT)
        val seriesMatch = SERIES.find(name)

        val kind = kindFromUrl ?: when {
            seriesMatch != null && extension in VOD_EXTENSIONS -> ChannelKind.SERIES
            extension in VOD_EXTENSIONS -> ChannelKind.MOVIE
            else -> ChannelKind.LIVE
        }
        // URL said "series" but the title carries no SxxExx marker: still treat as series,
        // grouping by full name minus any trailing episode junk.
        val resolvedKind = if (kind == ChannelKind.SERIES) ChannelKind.SERIES else kind

        var seriesKey: String? = null
        var season: Int? = null
        var episode: Int? = null
        if (resolvedKind == ChannelKind.SERIES) {
            if (seriesMatch != null) {
                season = (seriesMatch.groupValues[1].ifEmpty { seriesMatch.groupValues[3] }).toIntOrNull()
                episode = (seriesMatch.groupValues[2].ifEmpty { seriesMatch.groupValues[4] }).toIntOrNull()
                seriesKey = name.substring(0, seriesMatch.range.first)
                    .trim(' ', '-', '_', ':', '.', ',').ifEmpty { name }
            } else {
                seriesKey = name
            }
        }
        return M3uEntry(
            name = name,
            url = url,
            logo = logo,
            groupTitle = group ?: "Uncategorized",
            tvgId = tvgId,
            kind = resolvedKind,
            seriesKey = seriesKey,
            season = season,
            episode = episode,
        )
    }
}
