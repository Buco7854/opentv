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
    val catchupDays: Int,
)

class M3uHeader(val epgUrl: String?)

/**
 * Single-pass streaming parser: reads straight from the network/file stream and
 * emits entries through a callback, so a 50k-line playlist never needs to be
 * held in memory as one big string.
 */
object M3uParser {

    private val ATTR = Regex("""([\w-]+)="([^"]*)"""")

    fun parse(
        reader: BufferedReader,
        onHeader: (M3uHeader) -> Unit = {},
        onEntry: (M3uEntry) -> Unit,
    ) {
        var name = ""
        var logo: String? = null
        var group: String? = null
        var tvgId: String? = null
        var catchupDays = 0
        var extGrp: String? = null
        var pendingInfo = false

        var line = reader.readLine()
        while (line != null) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("#EXTM3U", ignoreCase = true) -> {
                    val attrs = parseAttrs(trimmed)
                    onHeader(M3uHeader(attrs["url-tvg"] ?: attrs["x-tvg-url"]))
                }
                trimmed.startsWith("#EXTINF", ignoreCase = true) -> {
                    val matches = ATTR.findAll(trimmed).toList()
                    val attrs = matches.associate {
                        it.groupValues[1].lowercase(Locale.ROOT) to it.groupValues[2]
                    }
                    // The display name is everything after the first comma that
                    // follows the attribute section - NOT after the last comma,
                    // or titles like "News, Weather & Sport" get truncated.
                    val searchFrom = (matches.lastOrNull()?.range?.last ?: trimmed.indexOf(':')) + 1
                    val comma = trimmed.indexOf(',', startIndex = searchFrom.coerceIn(0, trimmed.length))
                    name = if (comma >= 0) trimmed.substring(comma + 1).trim() else ""
                    if (name.isEmpty()) name = attrs["tvg-name"] ?: "Unknown"
                    logo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() }
                    group = attrs["group-title"]?.takeIf { it.isNotBlank() }
                    tvgId = attrs["tvg-id"]?.takeIf { it.isNotBlank() }
                    catchupDays = attrs["catchup-days"]?.toIntOrNull()
                        ?: attrs["tv-archive-duration"]?.toIntOrNull() ?: 0
                    pendingInfo = true
                }
                trimmed.startsWith("#EXTGRP:", ignoreCase = true) -> {
                    extGrp = trimmed.substringAfter(':').trim().takeIf { it.isNotBlank() }
                }
                trimmed.isNotEmpty() && !trimmed.startsWith("#") && pendingInfo -> {
                    // Decorative separator rows ("#### SPORTS ####") are not content;
                    // letting them through is how some players end up with phantom channels.
                    if (!ContentClassifier.isSeparator(name)) {
                        onEntry(buildEntry(name, trimmed, logo, group ?: extGrp, tvgId, catchupDays))
                    }
                    pendingInfo = false
                    logo = null; group = null; tvgId = null; catchupDays = 0
                }
            }
            line = reader.readLine()
        }
    }

    private fun parseAttrs(line: String): Map<String, String> =
        ATTR.findAll(line).associate { it.groupValues[1].lowercase(Locale.ROOT) to it.groupValues[2] }

    private fun buildEntry(
        name: String,
        url: String,
        logo: String?,
        group: String?,
        tvgId: String?,
        catchupDays: Int,
    ): M3uEntry {
        val classification = ContentClassifier.classify(name, url, group)
        return M3uEntry(
            name = name,
            url = url,
            logo = logo,
            groupTitle = group ?: "Uncategorized",
            tvgId = tvgId,
            kind = classification.kind,
            seriesKey = classification.seriesKey,
            season = classification.season,
            episode = classification.episode,
            catchupDays = if (classification.kind == ChannelKind.LIVE) catchupDays else 0,
        )
    }
}
