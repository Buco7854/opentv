package com.buco7854.opentv.data.m3u

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
                    // Decorative separator rows ("#### SPORTS ####") are not content;
                    // letting them through is how some players end up with phantom channels.
                    if (!ContentClassifier.isSeparator(name)) {
                        onEntry(buildEntry(name, trimmed, logo, group ?: extGrp, tvgId))
                    }
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
        )
    }
}
