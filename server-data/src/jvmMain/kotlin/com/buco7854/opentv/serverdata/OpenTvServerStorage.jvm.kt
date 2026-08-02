package com.buco7854.opentv.serverdata

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.buco7854.opentv.core.storage.EpgStore
import com.buco7854.opentv.core.storage.Storage
import com.buco7854.opentv.data.RoomStorage
import com.buco7854.opentv.data.db.SEARCH_INDEX_CALLBACK
import com.buco7854.opentv.serverdata.db.OpenTvServerDatabase
import com.buco7854.opentv.serverdata.db.deleteGuideForPlaylistInChunks
import com.buco7854.opentv.serverdata.db.deleteGuideFromInChunks
import com.buco7854.opentv.serverdata.db.pruneGuideInChunks
import kotlinx.coroutines.Dispatchers

data class OpenTvServerStorage(
    val database: OpenTvServerDatabase,
    val catalog: Storage,
)

fun createOpenTvServerStorage(path: String): OpenTvServerStorage {
    val database = createOpenTvServerDatabase(path)
    val room = RoomStorage(database, database::close)
    return OpenTvServerStorage(
        database = database,
        catalog = ServerCatalogStorage(room, database),
    )
}

fun createOpenTvServerDatabase(path: String): OpenTvServerDatabase =
    Room.databaseBuilder<OpenTvServerDatabase>(path)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .fallbackToDestructiveMigration(dropAllTables = true)
        .addCallback(SEARCH_INDEX_CALLBACK)
        .addCallback(SERVER_SCALE_INDEX_CALLBACK)
        .build()

private class ServerCatalogStorage(
    private val delegate: Storage,
    database: OpenTvServerDatabase,
) : Storage by delegate {
    override val epg: EpgStore = object : EpgStore by delegate.epg {
        override suspend fun deleteForPlaylist(playlistId: Long) =
            database.deleteGuideForPlaylistInChunks(playlistId)

        override suspend fun deleteFrom(playlistId: Long, fromMs: Long) =
            database.deleteGuideFromInChunks(playlistId, fromMs)

        override suspend fun prune(playlistId: Long, beforeMs: Long) =
            database.pruneGuideInChunks(playlistId, beforeMs)
    }

    override fun close() = delegate.close()
}

/**
 * The shared schema's per-channel guide index cannot seek a playlist-wide future deletion.
 * This server-only sidecar keeps those refresh mutations sublinear without changing Android's
 * database or storage implementation.
 */
private val SERVER_SCALE_INDEX_CALLBACK = object : RoomDatabase.Callback() {
    override fun onCreate(connection: SQLiteConnection) = ensureServerScaleIndexes(connection)
    override fun onOpen(connection: SQLiteConnection) = ensureServerScaleIndexes(connection)
}

private fun ensureServerScaleIndexes(connection: SQLiteConnection) {
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS opentv_programmes_playlist_start " +
            "ON programmes(playlistId, startMs)",
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS opentv_channels_playlist_kind_series " +
            "ON channels(playlistId, kind, seriesKey)",
    )
    connection.execSQL(
        "CREATE TABLE IF NOT EXISTS content_series_locators (" +
            "contentId TEXT NOT NULL PRIMARY KEY, " +
            "playlistId INTEGER NOT NULL, " +
            "sourceKind TEXT NOT NULL, " +
            "sourceKey TEXT NOT NULL, " +
            "FOREIGN KEY(contentId) REFERENCES content_identities(contentId) ON DELETE CASCADE, " +
            "FOREIGN KEY(playlistId) REFERENCES playlists(id) ON DELETE CASCADE)",
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS opentv_content_series_locators_source " +
            "ON content_series_locators(playlistId, sourceKind, sourceKey)",
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS opentv_user_favorites_user_added " +
            "ON user_favorites(userId, addedAtMs DESC)",
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS opentv_user_downloads_user_created " +
            "ON user_downloads(userId, createdAtMs DESC)",
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS opentv_user_downloads_created " +
            "ON user_downloads(createdAtMs DESC)",
    )
}
