package com.buco7854.opentv.server

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

internal class SubtitleCue(val start: Double, val end: Double, val block: String)

/**
 * Grow-only WebVTT store keyed by source URL.
 *
 * ffmpeg only extracts cues for the stretch it has read, and a seek restarts it elsewhere, so
 * the cues a session holds shrink and shift. Subtitles do not depend on the audio track either,
 * so every session of a URL folds what it extracted into one shared store and reads the union
 * back: switching audio or seeking never drops a cue that was already seen.
 */
internal class SubtitleCueStore(root: Path) {
    private val directory: Path = Files.createDirectories(root.resolve("subs"))
    private val locks = ConcurrentHashMap<String, Any>()

    /** Fold [fresh] into the store for [url]'s track [index] (both on the same clock) and return
     *  the union, ordered by start time. */
    fun merge(url: String, index: Int, fresh: String?): List<SubtitleCue> {
        // Subtitles are per URL, not per share group or audio track.
        val key = "${shortSha1(url)}_$index"
        val file = directory.resolve("$key.vtt")
        synchronized(locks.computeIfAbsent(key) { Any() }) {
            val stored = runCatching { Files.readString(file) }.getOrNull()
            val cues = LinkedHashMap<String, SubtitleCue>()
            parse(stored).forEach { cues.putIfAbsent(it.block, it) }
            parse(fresh).forEach { cues.putIfAbsent(it.block, it) }
            val sorted = cues.values.sortedBy { it.start }
            val merged = if (sorted.isEmpty()) "" else buildString {
                append("WEBVTT\n\n")
                sorted.forEach { append(it.block).append("\n\n") }
            }
            if (merged.isNotBlank() && merged != stored) {
                runCatching { Files.writeString(file, merged) }
                prune()
            }
            return sorted
        }
    }

    private fun prune() {
        runCatching {
            Files.list(directory).use { it.toList() }
                .takeIf { it.size > MAX_FILES }
                ?.sortedByDescending { Files.getLastModifiedTime(it).toMillis() }
                ?.drop(MAX_FILES)
                ?.forEach { Files.deleteIfExists(it) }
        }
    }

    private fun parse(document: String?): List<SubtitleCue> {
        document ?: return emptyList()
        return document.split(BLANK_LINE).mapNotNull { raw ->
            val block = raw.trim()
            val line = block.lineSequence().firstOrNull { it.contains("-->") } ?: return@mapNotNull null
            val start = seconds(line.substringBefore("-->").trim()) ?: return@mapNotNull null
            val end = seconds(line.substringAfter("-->").trim().substringBefore(' ')) ?: (start + 5)
            SubtitleCue(start, end, block)
        }
    }

    private fun seconds(timestamp: String): Double? {
        val parts = timestamp.split(':')
        val (hours, minutes, rest) = when (parts.size) {
            3 -> Triple(
                parts[0].toDoubleOrNull(),
                parts[1].toDoubleOrNull(),
                parts[2].replace(',', '.').toDoubleOrNull(),
            )
            2 -> Triple(0.0, parts[0].toDoubleOrNull(), parts[1].replace(',', '.').toDoubleOrNull())
            else -> return null
        }
        return if (hours != null && minutes != null && rest != null) {
            hours * 3600 + minutes * 60 + rest
        } else null
    }

    private companion object {
        const val MAX_FILES = 512
        val BLANK_LINE = Regex("\\r?\\n\\r?\\n")
    }
}
