package com.buco7854.opentv.data

import android.content.Context
import androidx.room.Room
import com.buco7854.opentv.core.storage.Storage
import com.buco7854.opentv.data.db.OpenTvDatabase

fun createRoomStorage(context: Context): Storage {
    val db = Room.databaseBuilder<OpenTvDatabase>(
        context = context,
        name = context.getDatabasePath("opentv.db").absolutePath,
    )
        // Pre-release: schema changes recreate the DB; migrations start at first release.
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()
    return RoomStorage(db)
}
