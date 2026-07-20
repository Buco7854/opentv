package com.buco7854.opentv.data.db

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor

@Database(
    entities = [
        PlaylistRow::class,
        ChannelRow::class,
        ProgrammeRow::class,
        DownloadRow::class,
        MetadataRow::class,
        XtreamSeriesRow::class,
        FavoriteRow::class,
        GroupOverrideRow::class,
        ResumePointRow::class,
    ],
    version = 8,
    exportSchema = false,
)
@ConstructedBy(OpenTvDatabaseConstructor::class)
abstract class OpenTvDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun channelDao(): ChannelDao
    abstract fun epgDao(): EpgDao
    abstract fun downloadDao(): DownloadDao
    abstract fun metadataDao(): MetadataDao
    abstract fun xtreamSeriesDao(): XtreamSeriesDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun groupOverrideDao(): GroupOverrideDao
    abstract fun resumeDao(): ResumeDao
}

// Room generates the per-platform actuals.
@Suppress("NO_ACTUAL_FOR_EXPECT", "EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
expect object OpenTvDatabaseConstructor : RoomDatabaseConstructor<OpenTvDatabase> {
    override fun initialize(): OpenTvDatabase
}
