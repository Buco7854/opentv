package com.buco7854.opentv.data.db

import androidx.room.migration.Migration
import androidx.sqlite.execSQL

/**
 * Index only: "now airing" scans a playlist's whole retained guide without it. Adding an
 * index rewrites no rows, so this is safe on a large catalog.
 */
private val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(connection: androidx.sqlite.SQLiteConnection) {
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS index_programmes_playlistId_endMs_startMs " +
                "ON programmes (playlistId, endMs, startMs)"
        )
    }
}

/**
 * Adds normalized prefix columns, FTS5 external-content sidecars for literal substring
 * search, and the measured browse indices. Both Android and JVM install this migration.
 */
private val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(connection: androidx.sqlite.SQLiteConnection) {
        connection.execSQL("ALTER TABLE channels ADD COLUMN searchName TEXT NOT NULL DEFAULT ''")
        connection.execSQL("ALTER TABLE xtream_series ADD COLUMN searchName TEXT NOT NULL DEFAULT ''")

        backfillChannelSearch(connection)
        backfillSeriesSearch(connection)

        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS index_channels_playlistId_kind_searchName_position_id " +
                "ON channels (playlistId, kind, searchName, position, id)"
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS index_xtream_series_playlistId_searchName_seriesId " +
                "ON xtream_series (playlistId, searchName, seriesId)"
        )
        connection.execSQL("DROP INDEX IF EXISTS index_channels_playlistId_kind_groupTitle")
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS index_channels_playlistId_kind_groupTitle_position " +
                "ON channels (playlistId, kind, groupTitle, position)"
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS index_channels_playlistId_kind_groupTitle_seriesKey " +
                "ON channels (playlistId, kind, groupTitle, seriesKey)"
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS index_xtream_series_playlistId_categoryName_name_seriesId " +
                "ON xtream_series (playlistId, categoryName, name, seriesId)"
        )
        ensureSearchIndexSchema(connection)
    }
}

private fun backfillChannelSearch(connection: androidx.sqlite.SQLiteConnection) {
    connection.prepare("SELECT id, name FROM channels").use { rows ->
        connection.prepare(
            "UPDATE channels SET searchName = ? WHERE id = ?"
        ).use { update ->
            while (rows.step()) {
                update.bindText(1, searchIndexName(rows.getText(1)))
                update.bindLong(2, rows.getLong(0))
                update.step()
                update.reset()
                update.clearBindings()
            }
        }
    }
}

private fun backfillSeriesSearch(connection: androidx.sqlite.SQLiteConnection) {
    connection.prepare("SELECT rowid, name FROM xtream_series").use { rows ->
        connection.prepare(
            "UPDATE xtream_series SET searchName = ? WHERE rowid = ?"
        ).use { update ->
            while (rows.step()) {
                update.bindText(1, searchIndexName(rows.getText(1)))
                update.bindLong(2, rows.getLong(0))
                update.step()
                update.reset()
                update.clearBindings()
            }
        }
    }
}

val OPENTV_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_8_9, MIGRATION_9_10)
