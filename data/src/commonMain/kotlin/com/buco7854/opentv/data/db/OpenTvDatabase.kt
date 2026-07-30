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
        HubSourceRow::class,
    ],
    version = 12,
    exportSchema = true,
)
@ConstructedBy(OpenTvDatabaseConstructor::class)
abstract class OpenTvDatabase : RoomDatabase(), CatalogDaos {
    abstract override fun playlistDao(): PlaylistDao
    abstract override fun channelDao(): ChannelDao
    abstract override fun epgDao(): EpgDao
    abstract override fun downloadDao(): DownloadDao
    abstract override fun metadataDao(): MetadataDao
    abstract override fun xtreamSeriesDao(): XtreamSeriesDao
    abstract override fun favoriteDao(): FavoriteDao
    abstract override fun groupOverrideDao(): GroupOverrideDao
    abstract override fun resumeDao(): ResumeDao
    abstract override fun hubSourceDao(): HubSourceDao
}

// Room generates the per-platform actuals.
@Suppress("NO_ACTUAL_FOR_EXPECT", "EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
expect object OpenTvDatabaseConstructor : RoomDatabaseConstructor<OpenTvDatabase> {
    override fun initialize(): OpenTvDatabase
}
