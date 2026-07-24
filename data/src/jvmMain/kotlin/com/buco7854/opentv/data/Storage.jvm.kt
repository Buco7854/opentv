package com.buco7854.opentv.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.buco7854.opentv.core.storage.Storage
import com.buco7854.opentv.data.db.OpenTvDatabase
import kotlinx.coroutines.Dispatchers

fun createRoomStorage(dbPath: String): Storage {
    val db = Room.databaseBuilder<OpenTvDatabase>(dbPath)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
    return RoomStorage(db)
}
