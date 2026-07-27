package com.buco7854.opentv.data.db

import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Room 2.x can query FTS5 through raw queries but cannot model an FTS5 entity. Keep the sidecar
 * DDL here, install it from both creation/open callbacks, and call the same code from migration
 * 9 -> 10. The catalog tables are external content; these triggers make every refresh atomic
 * with its search-index update.
 */
internal val SEARCH_INDEX_CALLBACK = object : RoomDatabase.Callback() {
    override fun onCreate(connection: SQLiteConnection) {
        ensureSearchIndexSchema(connection)
    }

    override fun onOpen(connection: SQLiteConnection) {
        ensureSearchIndexSchema(connection)
    }
}

internal fun ensureSearchIndexSchema(connection: SQLiteConnection) {
    val rebuildChannels = !connection.hasTable("channels_fts")
    val rebuildSeries = !connection.hasTable("xtream_series_fts")
    val rebuildChannelWords = !connection.hasTable("channels_words_fts")
    val rebuildSeriesWords = !connection.hasTable("xtream_series_words_fts")

    connection.execSQL(
        "CREATE VIRTUAL TABLE IF NOT EXISTS channels_fts USING fts5(" +
            "searchName, playlistId UNINDEXED, kind UNINDEXED, " +
            "content='channels', content_rowid='id', tokenize='trigram')"
    )
    connection.execSQL(
        "CREATE VIRTUAL TABLE IF NOT EXISTS xtream_series_fts USING fts5(" +
            "searchName, playlistId UNINDEXED, " +
            "content='xtream_series', content_rowid='rowid', tokenize='trigram')"
    )
    connection.execSQL(
        "CREATE VIRTUAL TABLE IF NOT EXISTS channels_words_fts USING fts5(" +
            "searchName, playlistId UNINDEXED, kind UNINDEXED, " +
            "content='channels', content_rowid='id', tokenize='unicode61')"
    )
    connection.execSQL(
        "CREATE VIRTUAL TABLE IF NOT EXISTS xtream_series_words_fts USING fts5(" +
            "searchName, playlistId UNINDEXED, " +
            "content='xtream_series', content_rowid='rowid', tokenize='unicode61')"
    )

    SEARCH_TRIGGER_SQL.forEach(connection::execSQL)

    if (rebuildChannels) {
        connection.execSQL("INSERT INTO channels_fts(channels_fts) VALUES('rebuild')")
    }
    if (rebuildSeries) {
        connection.execSQL("INSERT INTO xtream_series_fts(xtream_series_fts) VALUES('rebuild')")
    }
    if (rebuildChannelWords) {
        connection.execSQL("INSERT INTO channels_words_fts(channels_words_fts) VALUES('rebuild')")
    }
    if (rebuildSeriesWords) {
        connection.execSQL("INSERT INTO xtream_series_words_fts(xtream_series_words_fts) VALUES('rebuild')")
    }
}

private fun SQLiteConnection.hasTable(name: String): Boolean =
    prepare("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?").use { statement ->
        statement.bindText(1, name)
        statement.step()
    }

private val SEARCH_TRIGGER_SQL = listOf(
    "CREATE TRIGGER IF NOT EXISTS opentv_channels_fts_ai AFTER INSERT ON channels BEGIN " +
        "INSERT INTO channels_fts(rowid, searchName, playlistId, kind) " +
        "VALUES (new.id, new.searchName, new.playlistId, new.kind); END",
    "CREATE TRIGGER IF NOT EXISTS opentv_channels_fts_ad AFTER DELETE ON channels BEGIN " +
        "INSERT INTO channels_fts(channels_fts, rowid, searchName, playlistId, kind) " +
        "VALUES ('delete', old.id, old.searchName, old.playlistId, old.kind); END",
    "CREATE TRIGGER IF NOT EXISTS opentv_channels_fts_au AFTER UPDATE ON channels BEGIN " +
        "INSERT INTO channels_fts(channels_fts, rowid, searchName, playlistId, kind) " +
        "VALUES ('delete', old.id, old.searchName, old.playlistId, old.kind); " +
        "INSERT INTO channels_fts(rowid, searchName, playlistId, kind) " +
        "VALUES (new.id, new.searchName, new.playlistId, new.kind); END",
    "CREATE TRIGGER IF NOT EXISTS opentv_xtream_series_fts_ai AFTER INSERT ON xtream_series BEGIN " +
        "INSERT INTO xtream_series_fts(rowid, searchName, playlistId) " +
        "VALUES (new.rowid, new.searchName, new.playlistId); END",
    "CREATE TRIGGER IF NOT EXISTS opentv_xtream_series_fts_ad AFTER DELETE ON xtream_series BEGIN " +
        "INSERT INTO xtream_series_fts(xtream_series_fts, rowid, searchName, playlistId) " +
        "VALUES ('delete', old.rowid, old.searchName, old.playlistId); END",
    "CREATE TRIGGER IF NOT EXISTS opentv_xtream_series_fts_au AFTER UPDATE ON xtream_series BEGIN " +
        "INSERT INTO xtream_series_fts(xtream_series_fts, rowid, searchName, playlistId) " +
        "VALUES ('delete', old.rowid, old.searchName, old.playlistId); " +
        "INSERT INTO xtream_series_fts(rowid, searchName, playlistId) " +
        "VALUES (new.rowid, new.searchName, new.playlistId); END",
    "CREATE TRIGGER IF NOT EXISTS opentv_channels_words_fts_ai AFTER INSERT ON channels BEGIN " +
        "INSERT INTO channels_words_fts(rowid, searchName, playlistId, kind) " +
        "VALUES (new.id, new.searchName, new.playlistId, new.kind); END",
    "CREATE TRIGGER IF NOT EXISTS opentv_channels_words_fts_ad AFTER DELETE ON channels BEGIN " +
        "INSERT INTO channels_words_fts(channels_words_fts, rowid, searchName, playlistId, kind) " +
        "VALUES ('delete', old.id, old.searchName, old.playlistId, old.kind); END",
    "CREATE TRIGGER IF NOT EXISTS opentv_channels_words_fts_au AFTER UPDATE ON channels BEGIN " +
        "INSERT INTO channels_words_fts(channels_words_fts, rowid, searchName, playlistId, kind) " +
        "VALUES ('delete', old.id, old.searchName, old.playlistId, old.kind); " +
        "INSERT INTO channels_words_fts(rowid, searchName, playlistId, kind) " +
        "VALUES (new.id, new.searchName, new.playlistId, new.kind); END",
    "CREATE TRIGGER IF NOT EXISTS opentv_xtream_series_words_fts_ai AFTER INSERT ON xtream_series BEGIN " +
        "INSERT INTO xtream_series_words_fts(rowid, searchName, playlistId) " +
        "VALUES (new.rowid, new.searchName, new.playlistId); END",
    "CREATE TRIGGER IF NOT EXISTS opentv_xtream_series_words_fts_ad AFTER DELETE ON xtream_series BEGIN " +
        "INSERT INTO xtream_series_words_fts(xtream_series_words_fts, rowid, searchName, playlistId) " +
        "VALUES ('delete', old.rowid, old.searchName, old.playlistId); END",
    "CREATE TRIGGER IF NOT EXISTS opentv_xtream_series_words_fts_au AFTER UPDATE ON xtream_series BEGIN " +
        "INSERT INTO xtream_series_words_fts(xtream_series_words_fts, rowid, searchName, playlistId) " +
        "VALUES ('delete', old.rowid, old.searchName, old.playlistId); " +
        "INSERT INTO xtream_series_words_fts(rowid, searchName, playlistId) " +
        "VALUES (new.rowid, new.searchName, new.playlistId); END",
)
