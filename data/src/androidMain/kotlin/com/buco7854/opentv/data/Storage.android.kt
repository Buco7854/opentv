package com.buco7854.opentv.data

import android.content.Context
import androidx.room.Room
import com.buco7854.opentv.core.storage.Storage
import com.buco7854.opentv.data.db.OPENTV_MIGRATIONS
import com.buco7854.opentv.data.db.OpenTvDatabase

fun createRoomStorage(context: Context): Storage {
    val db = Room.databaseBuilder<OpenTvDatabase>(
        context = context,
        name = context.getDatabasePath("opentv.db").absolutePath,
    ).addMigrations(*OPENTV_MIGRATIONS)
        .build()
    return RoomStorage(db)
}
