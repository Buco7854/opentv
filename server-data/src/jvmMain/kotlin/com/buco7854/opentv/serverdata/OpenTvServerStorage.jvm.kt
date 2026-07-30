package com.buco7854.opentv.serverdata

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.buco7854.opentv.core.storage.Storage
import com.buco7854.opentv.data.RoomStorage
import com.buco7854.opentv.data.db.SEARCH_INDEX_CALLBACK
import com.buco7854.opentv.serverdata.db.OpenTvServerDatabase
import kotlinx.coroutines.Dispatchers

data class OpenTvServerStorage(
    val database: OpenTvServerDatabase,
    val catalog: Storage,
)

fun createOpenTvServerStorage(path: String): OpenTvServerStorage {
    val database = createOpenTvServerDatabase(path)
    return OpenTvServerStorage(
        database = database,
        catalog = RoomStorage(database, database::close),
    )
}

fun createOpenTvServerDatabase(path: String): OpenTvServerDatabase =
    Room.databaseBuilder<OpenTvServerDatabase>(path)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .fallbackToDestructiveMigration(dropAllTables = true)
        .addCallback(SEARCH_INDEX_CALLBACK)
        .build()
