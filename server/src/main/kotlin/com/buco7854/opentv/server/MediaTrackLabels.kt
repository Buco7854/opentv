package com.buco7854.opentv.server

/**
 * Human-readable names for the audio and subtitle tracks a file exposes. Labels lead with
 * whatever the file itself says (title, else language, else an ordinal) and only append the
 * details that name does not already carry, so nothing reads as "English · English".
 */
internal object MediaTrackLabels {

    private val languageNames = mapOf(
        "eng" to "English", "en" to "English",
        "fre" to "Français", "fra" to "Français", "fr" to "Français",
        "spa" to "Español", "es" to "Español",
        "ger" to "Deutsch", "deu" to "Deutsch", "de" to "Deutsch",
        "ita" to "Italiano", "it" to "Italiano",
        "por" to "Português", "pt" to "Português",
        "rus" to "Русский", "ru" to "Русский",
        "jpn" to "日本語", "ja" to "日本語",
        "kor" to "한국어", "ko" to "한국어",
        "chi" to "中文", "zho" to "中文", "zh" to "中文",
        "ara" to "العربية", "ar" to "العربية",
        "tur" to "Türkçe", "tr" to "Türkçe",
        "nld" to "Nederlands", "dut" to "Nederlands", "nl" to "Nederlands",
        "pol" to "Polski", "pl" to "Polski",
        "swe" to "Svenska", "dan" to "Dansk", "nor" to "Norsk", "fin" to "Suomi",
        "ces" to "Čeština", "cze" to "Čeština",
        "hun" to "Magyar", "ell" to "Ελληνικά", "gre" to "Ελληνικά",
        "heb" to "עברית", "hin" to "हिन्दी", "tha" to "ไทย", "vie" to "Tiếng Việt",
        "ukr" to "Українська", "ron" to "Română", "rum" to "Română",
        "bul" to "Български", "hrv" to "Hrvatski", "srp" to "Srpski",
        "slk" to "Slovenčina", "slv" to "Slovenščina", "cat" to "Català",
        "ind" to "Indonesia", "msa" to "Melayu", "may" to "Melayu",
        "fas" to "فارسی", "per" to "فارسی",
    )

    private val codecNames = mapOf(
        "aac" to "AAC", "ac3" to "AC3", "eac3" to "E-AC3", "dts" to "DTS",
        "truehd" to "TrueHD", "opus" to "Opus", "mp3" to "MP3",
        "flac" to "FLAC", "vorbis" to "Vorbis", "mp2" to "MP2",
    )

    fun audio(streams: List<MediaStreamInfo>): List<String> =
        label(streams) { stream, parts, base ->
            codecNames[stream.codec.lowercase()]?.let { codec ->
                if (!base.contains(codec, true)) parts += codec
            }
            channelsName(stream.channels)?.let { layout ->
                if (!base.contains(layout)) parts += layout
            }
        }

    fun subtitles(streams: List<MediaStreamInfo>): List<String> =
        label(streams) { stream, parts, base ->
            if (stream.forced && !base.contains("forc", true)) parts += "Forced"
        }

    private fun label(
        streams: List<MediaStreamInfo>,
        detail: (MediaStreamInfo, MutableList<String>, String) -> Unit,
    ): List<String> = unique(
        streams.mapIndexed { index, stream ->
            val language = languageNames[stream.language?.lowercase()] ?: stream.language
            val base = stream.title ?: language ?: "Track ${index + 1}"
            val parts = mutableListOf(base)
            if (stream.title != null && language != null && !base.contains(language, true) &&
                stream.language?.let { base.contains(it, true) } != true
            ) {
                parts += language
            }
            detail(stream, parts, base)
            parts.joinToString(" · ")
        }
    )

    private fun channelsName(channels: Int?) = when (channels) {
        null -> null
        1 -> "Mono"
        2 -> "Stereo"
        6 -> "5.1"
        8 -> "7.1"
        else -> "${channels}ch"
    }

    /** Menus must never show two identical entries; repeats are numbered in file order. */
    private fun unique(labels: List<String>): List<String> {
        val seen = HashMap<String, Int>()
        return labels.map { raw ->
            val label = raw.replace("\"", "").replace("\n", " ").trim().ifBlank { "Track" }
            val count = seen.merge(label, 1, Int::plus)!!
            if (count == 1) label else "$label ($count)"
        }
    }
}
