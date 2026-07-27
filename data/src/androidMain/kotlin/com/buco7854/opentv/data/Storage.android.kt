package com.buco7854.opentv.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.buco7854.opentv.core.storage.Storage
import com.buco7854.opentv.data.db.OPENTV_MIGRATIONS
import com.buco7854.opentv.data.db.OpenTvDatabase
import com.buco7854.opentv.data.db.SEARCH_INDEX_CALLBACK

fun createRoomStorage(context: Context): Storage {
    val db = Room.databaseBuilder<OpenTvDatabase>(
        context = context,
        name = context.getDatabasePath("opentv.db").absolutePath,
    ).setDriver(BundledSQLiteDriver())
        .addMigrations(*OPENTV_MIGRATIONS)
        .addCallback(SEARCH_INDEX_CALLBACK)
        .build()
    return RoomStorage(db)
}
