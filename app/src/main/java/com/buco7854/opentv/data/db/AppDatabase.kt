package com.buco7854.opentv.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        PlaylistEntity::class,
        ChannelEntity::class,
        ProgrammeEntity::class,
        DownloadEntity::class,
        MetadataEntity::class,
        XtreamSeriesEntity::class,
        FavoriteEntity::class,
        GroupOverrideEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun channelDao(): ChannelDao
    abstract fun epgDao(): EpgDao
    abstract fun downloadDao(): DownloadDao
    abstract fun metadataDao(): MetadataDao
    abstract fun xtreamSeriesDao(): XtreamSeriesDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun groupOverrideDao(): GroupOverrideDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "opentv.db")
                // Pre-release: schema changes recreate the database (playlists
                // are cheap to re-add). Proper migrations start at first release.
                .fallbackToDestructiveMigration()
                .build()
    }
}
