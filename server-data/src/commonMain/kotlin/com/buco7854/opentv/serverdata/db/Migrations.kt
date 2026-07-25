package com.buco7854.opentv.serverdata.db

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

val SERVER_USER_MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("DROP TABLE IF EXISTS security_events")
    }
}

val SERVER_USER_MIGRATIONS = arrayOf(SERVER_USER_MIGRATION_1_2)
