package com.buco7854.opentv.data.db

/**
 * The catalog-facing surface required by [com.buco7854.opentv.data.RoomStorage].
 *
 * Android's catalog-only database and the server's merged database both implement this
 * contract. Keeping the adapter on DAO accessors rather than a concrete `@Database` lets the
 * server compose the shared catalog rows with its user rows in one SQLite connection.
 */
interface CatalogDaos {
    fun playlistDao(): PlaylistDao
    fun channelDao(): ChannelDao
    fun epgDao(): EpgDao
    fun downloadDao(): DownloadDao
    fun metadataDao(): MetadataDao
    fun xtreamSeriesDao(): XtreamSeriesDao
    fun favoriteDao(): FavoriteDao
    fun groupOverrideDao(): GroupOverrideDao
    fun resumeDao(): ResumeDao
    fun hubSourceDao(): HubSourceDao
}
