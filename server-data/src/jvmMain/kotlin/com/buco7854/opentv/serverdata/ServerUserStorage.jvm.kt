package com.buco7854.opentv.serverdata

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.buco7854.opentv.serverdata.db.ServerUserDatabase
import kotlinx.coroutines.Dispatchers

fun createServerUserDatabase(path: String): ServerUserDatabase =
    Room.databaseBuilder<ServerUserDatabase>(path)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()

