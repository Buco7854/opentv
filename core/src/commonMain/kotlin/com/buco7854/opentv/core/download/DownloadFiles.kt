package com.buco7854.opentv.core.download

/** Pure, platform-neutral naming policy for downloaded media. */
data class DownloadFileName(
    val baseName: String,
    val extension: String,
) {
    val fileName: String get() = "$baseName.$extension"

    companion object {
        fun from(title: String, sourceUrl: String, id: Long): DownloadFileName {
            val lastSegment = sourceUrl
                .substringBefore('?')
                .substringBefore('#')
                .substringAfterLast('/')
            val extension = lastSegment.substringAfterLast('.', "")
                .filter(Char::isLetterOrDigit)
                .take(5)
                .ifEmpty { "mp4" }
            val safeTitle = title
                .map { if (it.isLetterOrDigit() || it in " ._-()[]") it else '_' }
                .joinToString("")
                .trim()
                .take(120)
                .ifEmpty { "video" }
            return DownloadFileName("$safeTitle-$id", extension)
        }
    }
}
