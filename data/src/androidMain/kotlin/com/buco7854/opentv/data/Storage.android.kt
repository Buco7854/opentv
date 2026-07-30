package com.buco7854.opentv.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.buco7854.opentv.core.storage.Storage
import com.buco7854.opentv.data.db.OpenTvDatabase
import com.buco7854.opentv.data.db.SEARCH_INDEX_CALLBACK

fun createRoomStorage(context: Context): Storage {
    val db = Room.databaseBuilder<OpenTvDatabase>(
        context = context,
        name = context.getDatabasePath("opentv.db").absolutePath,
    ).setDriver(BundledSQLiteDriver())
        .fallbackToDestructiveMigration(dropAllTables = true)
        .addCallback(SEARCH_INDEX_CALLBACK)
        .build()
    return RoomStorage(db, db::close)
}
