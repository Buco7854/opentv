package com.buco7854.opentv.data.db

import androidx.room.RoomRawQuery

internal data class SearchIndexQuery(
    val normalizedQuery: String,
    val wordMatchExpression: String,
    /** FTS5's trigram tokenizer requires three code points; two-character searches omit mid-word matches. */
    val substringMatchExpression: String?,
)

internal fun searchIndexName(name: String): String =
    normalizeSearchText(name, MAX_INDEXED_NAME_CODE_POINTS)

internal fun searchIndexQuery(query: String): SearchIndexQuery? {
    val normalized = normalizeSearchText(query, MAX_QUERY_CODE_POINTS)
    val pointCount = normalized.searchCodePoints().size
    if (pointCount < MIN_QUERY_CODE_POINTS) return null
    return SearchIndexQuery(
        normalizedQuery = normalized,
        wordMatchExpression = "searchName : ${fts5Phrase(normalized)} *",
        substringMatchExpression = if (pointCount >= MIN_TRIGRAM_QUERY_CODE_POINTS) {
            "searchName : ${fts5Phrase(normalized)}"
        } else {
            null
        },
    )
}

internal fun channelIndexedSearchQuery(
    playlistId: Long,
    kind: Int,
    query: SearchIndexQuery,
    wordBoundary: Boolean,
    limit: Int,
): RoomRawQuery = RoomRawQuery(
    if (wordBoundary) CHANNEL_WORD_BOUNDARY_SQL else CHANNEL_MID_WORD_SQL
) { statement ->
    statement.bindText(1, query.normalizedQuery)
    statement.bindText(
        2,
        if (wordBoundary) query.wordMatchExpression else requireNotNull(query.substringMatchExpression),
    )
    statement.bindLong(3, playlistId)
    statement.bindLong(4, kind.toLong())
    statement.bindLong(5, limit.toLong())
    statement.bindLong(6, playlistId)
    statement.bindLong(7, kind.toLong())
}

internal fun seriesIndexedSearchQuery(
    playlistId: Long,
    query: SearchIndexQuery,
    wordBoundary: Boolean,
    limit: Int,
): RoomRawQuery = RoomRawQuery(
    if (wordBoundary) SERIES_WORD_BOUNDARY_SQL else SERIES_MID_WORD_SQL
) { statement ->
    statement.bindText(1, query.normalizedQuery)
    statement.bindText(
        2,
        if (wordBoundary) query.wordMatchExpression else requireNotNull(query.substringMatchExpression),
    )
    statement.bindLong(3, playlistId)
    statement.bindLong(4, limit.toLong())
    statement.bindLong(5, playlistId)
}

/**
 * SQLite's built-in lower() and LIKE are ASCII-case-insensitive. Mirroring that deliberately
 * keeps migrated rows and newly refreshed rows identical on both Android and bundled SQLite.
 */
private fun normalizeSearchText(value: String, maxCodePoints: Int): String {
    val lowered = buildString(value.length) {
        value.forEach { char ->
            if (char != '\u0000') {
                append(if (char in 'A'..'Z') char + ('a' - 'A') else char)
            }
        }
    }.trim(' ')
    return lowered.takeCodePoints(maxCodePoints)
}

private fun fts5Phrase(value: String): String = "\"${value.replace("\"", "\"\"")}\""

private fun String.searchCodePoints(): List<Int> = buildList {
    var index = 0
    while (index < length) {
        val first = this@searchCodePoints[index]
        if (first.isHighSurrogate() && index + 1 < length && this@searchCodePoints[index + 1].isLowSurrogate()) {
            val second = this@searchCodePoints[index + 1]
            add(((first.code - HIGH_SURROGATE_START) shl 10) + second.code - LOW_SURROGATE_START + 0x10000)
            index += 2
        } else {
            add(first.code)
            index++
        }
    }
}

private fun String.takeCodePoints(count: Int): String {
    var charCount = 0
    var pointCount = 0
    while (charCount < length && pointCount < count) {
        val first = this[charCount]
        charCount += if (first.isHighSurrogate() && charCount < length && this[charCount].isLowSurrogate()) {
            2
        } else {
            1
        }
        pointCount++
    }
    return take(charCount)
}

private const val MIN_QUERY_CODE_POINTS = 2
private const val MIN_TRIGRAM_QUERY_CODE_POINTS = 3
private const val MAX_QUERY_CODE_POINTS = 80
private const val MAX_INDEXED_NAME_CODE_POINTS = 256
private const val HIGH_SURROGATE_START = 0xD800
private const val LOW_SURROGATE_START = 0xDC00

private const val NOT_WORD_BOUNDARY_FILTER =
    "instr(searchName, ' ' || term) = 0 " +
        "AND instr(searchName, '-' || term) = 0 " +
        "AND instr(searchName, '_' || term) = 0 " +
        "AND instr(searchName, '.' || term) = 0 " +
        "AND instr(searchName, '/' || term) = 0"

private const val CHANNEL_WORD_BOUNDARY_SQL =
    "SELECT channels.* FROM channels JOIN (" +
        "SELECT rowid FROM channels_words_fts CROSS JOIN (SELECT ? AS term) " +
        "WHERE channels_words_fts MATCH ? AND playlistId = ? AND kind = ? " +
        "AND instr(searchName, term) > 1 ORDER BY rowid LIMIT ?" +
        ") AS hits ON hits.rowid = channels.id " +
        "WHERE channels.playlistId = ? AND channels.kind = ? ORDER BY hits.rowid"

private const val CHANNEL_MID_WORD_SQL =
    "SELECT channels.* FROM channels JOIN (" +
        "SELECT rowid FROM channels_fts CROSS JOIN (SELECT ? AS term) " +
        "WHERE channels_fts MATCH ? AND playlistId = ? AND kind = ? " +
        "AND instr(searchName, term) > 1 AND $NOT_WORD_BOUNDARY_FILTER ORDER BY rowid LIMIT ?" +
        ") AS hits ON hits.rowid = channels.id " +
        "WHERE channels.playlistId = ? AND channels.kind = ? ORDER BY hits.rowid"

private const val SERIES_WORD_BOUNDARY_SQL =
    "SELECT xtream_series.* FROM xtream_series JOIN (" +
        "SELECT rowid FROM xtream_series_words_fts CROSS JOIN (SELECT ? AS term) " +
        "WHERE xtream_series_words_fts MATCH ? AND playlistId = ? " +
        "AND instr(searchName, term) > 1 ORDER BY rowid LIMIT ?" +
        ") AS hits ON hits.rowid = xtream_series.rowid " +
        "WHERE xtream_series.playlistId = ? ORDER BY hits.rowid"

private const val SERIES_MID_WORD_SQL =
    "SELECT xtream_series.* FROM xtream_series JOIN (" +
        "SELECT rowid FROM xtream_series_fts CROSS JOIN (SELECT ? AS term) " +
        "WHERE xtream_series_fts MATCH ? AND playlistId = ? " +
        "AND instr(searchName, term) > 1 AND $NOT_WORD_BOUNDARY_FILTER ORDER BY rowid LIMIT ?" +
        ") AS hits ON hits.rowid = xtream_series.rowid " +
        "WHERE xtream_series.playlistId = ? ORDER BY hits.rowid"
