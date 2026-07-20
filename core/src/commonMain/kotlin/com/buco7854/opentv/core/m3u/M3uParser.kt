package com.buco7854.opentv.core.m3u

import com.buco7854.opentv.core.model.ChannelKind

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
    val catchupSource: String?,
)

class M3uHeader(val epgUrl: String?)

/** Single-pass streaming parser: emits entries via callback so large playlists stay off-heap. */
object M3uParser {

    private val ATTR = Regex("""([\w-]+)="([^"]*)"""")

    suspend fun parse(
        lines: Sequence<String>,
        onHeader: suspend (M3uHeader) -> Unit = {},
        onEntry: suspend (M3uEntry) -> Unit,
    ) {
        var name = ""
        var logo: String? = null
        var group: String? = null
        var tvgId: String? = null
        var catchupDays = 0
        var catchupSource: String? = null
        var catchupMode: String? = null
        var extGrp: String? = null
        var pendingInfo = false

        for (line in lines) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("#EXTM3U", ignoreCase = true) -> {
                    val attrs = parseAttrs(trimmed)
                    onHeader(M3uHeader(attrs["url-tvg"] ?: attrs["x-tvg-url"]))
                }
                trimmed.startsWith("#EXTINF", ignoreCase = true) -> {
                    val matches = ATTR.findAll(trimmed).toList()
                    val attrs = matches.associate {
                        it.groupValues[1].lowercase() to it.groupValues[2]
                    }
                    // Name starts at the first comma after the attributes, not the last (commas in titles).
                    val searchFrom = (matches.lastOrNull()?.range?.last ?: trimmed.indexOf(':')) + 1
                    val comma = trimmed.indexOf(',', startIndex = searchFrom.coerceIn(0, trimmed.length))
                    name = if (comma >= 0) trimmed.substring(comma + 1).trim() else ""
                    if (name.isEmpty()) name = attrs["tvg-name"] ?: "Unknown"
                    logo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() }
                    group = attrs["group-title"]?.takeIf { it.isNotBlank() }
                    tvgId = attrs["tvg-id"]?.takeIf { it.isNotBlank() }
                    catchupDays = attrs["catchup-days"]?.toIntOrNull()
                        ?: attrs["tv-archive-duration"]?.toIntOrNull() ?: 0
                    catchupSource = attrs["catchup-source"]?.takeIf { it.isNotBlank() }
                    catchupMode = attrs["catchup"]?.lowercase()?.takeIf { it.isNotBlank() }
                    pendingInfo = true
                }
                trimmed.startsWith("#EXTGRP:", ignoreCase = true) -> {
                    extGrp = trimmed.substringAfter(':').trim().takeIf { it.isNotBlank() }
                }
                trimmed.isNotEmpty() && !trimmed.startsWith("#") && pendingInfo -> {
                    // Drop decorative separator rows so they don't become phantom channels.
                    if (!ContentClassifier.isSeparator(name)) {
                        onEntry(
                            buildEntry(name, trimmed, logo, group ?: extGrp, tvgId, catchupDays, catchupSource, catchupMode)
                        )
                    }
                    pendingInfo = false
                    logo = null; group = null; tvgId = null
                    catchupDays = 0; catchupSource = null; catchupMode = null
                }
            }
        }
    }

    private fun parseAttrs(line: String): Map<String, String> =
        ATTR.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }

    private fun buildEntry(
        name: String,
        url: String,
        logo: String?,
        group: String?,
        tvgId: String?,
        catchupDays: Int,
        catchupSource: String?,
        catchupMode: String?,
    ): M3uEntry {
        val classification = ContentClassifier.classify(name, url, group)
        val isLive = classification.kind == ChannelKind.LIVE
        // Normalize template-less modes (shift, flussonic) into a catchup-source template.
        val resolvedSource = when {
            !isLive -> null
            catchupSource != null -> if (catchupMode == "append") url + catchupSource else catchupSource
            catchupMode == "shift" || catchupMode == "timeshift" ->
                url + (if ('?' in url) "&" else "?") + "utc={utc}&lutc={lutc}"
            catchupMode == "flussonic" || catchupMode == "flussonic-hls" || catchupMode == "fs" ->
                flussonicTemplate(url)
            else -> null
        }
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
            catchupDays = if (isLive) catchupDays else 0,
            catchupSource = resolvedSource,
        )
    }

    // Flussonic archive lives next to the stream: index.m3u8 -> archive-{utc}-{duration}.m3u8, TS -> timeshift_abs.
    private fun flussonicTemplate(url: String): String? {
        val base = url.substringBefore('?')
        val pathStart = base.indexOf("://").takeIf { it >= 0 }?.plus(3) ?: 0
        val cut = base.lastIndexOf('/')
        if (cut <= pathStart) return null
        val dir = base.substring(0, cut)
        return if (base.endsWith(".m3u8")) "$dir/archive-{utc}-{duration}.m3u8"
        else "$dir/timeshift_abs-{utc}.ts"
    }
}
