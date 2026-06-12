package com.buco7854.opentv.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        PlaylistEntity::class,
        ChannelEntity::class,
        ProgrammeEntity::class,
        DownloadEntity::class,
        MetadataEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun channelDao(): ChannelDao
    abstract fun epgDao(): EpgDao
    abstract fun downloadDao(): DownloadDao
    abstract fun metadataDao(): MetadataDao

    companion object {
        /** v2: TMDB metadata cache. Hand-written so user data is never wiped. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `metadata` (" +
                        "`cacheKey` TEXT NOT NULL, `title` TEXT, `year` TEXT, " +
                        "`overview` TEXT, `rating` REAL, `castNames` TEXT, " +
                        "`posterUrl` TEXT, `fetchedAtMs` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`cacheKey`))"
                )
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "opentv.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
