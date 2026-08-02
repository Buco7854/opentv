package com.buco7854.opentv.serverdata.db

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import com.buco7854.opentv.data.db.CatalogDaos
import com.buco7854.opentv.data.db.ChannelDao
import com.buco7854.opentv.data.db.ChannelRow
import com.buco7854.opentv.data.db.DownloadRow
import com.buco7854.opentv.data.db.EpgDao
import com.buco7854.opentv.data.db.FavoriteDao
import com.buco7854.opentv.data.db.FavoriteRow
import com.buco7854.opentv.data.db.GroupOverrideDao
import com.buco7854.opentv.data.db.GroupOverrideRow
import com.buco7854.opentv.data.db.HubSourceDao
import com.buco7854.opentv.data.db.HubSourceRow
import com.buco7854.opentv.data.db.MetadataDao
import com.buco7854.opentv.data.db.MetadataRow
import com.buco7854.opentv.data.db.PlaylistDao
import com.buco7854.opentv.data.db.PlaylistRow
import com.buco7854.opentv.data.db.ProgrammeRow
import com.buco7854.opentv.data.db.ResumeDao
import com.buco7854.opentv.data.db.ResumePointRow
import com.buco7854.opentv.data.db.XtreamSeriesDao
import com.buco7854.opentv.data.db.XtreamSeriesRow

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
        UserRow::class,
        PasswordCredentialRow::class,
        OidcIdentityRow::class,
        PendingOidcIdentityRow::class,
        TotpCredentialRow::class,
        WebAuthnCredentialRow::class,
        RecoveryCodeRow::class,
        AuthSessionRow::class,
        AuthChallengeRow::class,
        DefaultPlaylistRow::class,
        UserPlaylistGrantRow::class,
        ContentIdentityRow::class,
        UserResumeRow::class,
        UserFavoriteRow::class,
        DownloadBlobRow::class,
        UserDownloadRow::class,
        PlaylistDeletionRow::class,
    ],
    version = 1,
    exportSchema = true,
)
@ConstructedBy(OpenTvServerDatabaseConstructor::class)
abstract class OpenTvServerDatabase : RoomDatabase(), CatalogDaos {
    abstract override fun playlistDao(): PlaylistDao
    abstract override fun channelDao(): ChannelDao
    abstract override fun epgDao(): EpgDao
    abstract override fun downloadDao(): com.buco7854.opentv.data.db.DownloadDao
    abstract override fun metadataDao(): MetadataDao
    abstract override fun xtreamSeriesDao(): XtreamSeriesDao
    abstract override fun favoriteDao(): FavoriteDao
    abstract override fun groupOverrideDao(): GroupOverrideDao
    abstract override fun resumeDao(): ResumeDao
    abstract override fun hubSourceDao(): HubSourceDao

    abstract fun users(): UserDao
    abstract fun credentials(): CredentialDao
    abstract fun oidc(): OidcDao
    abstract fun sessions(): SessionDao
    abstract fun challenges(): ChallengeDao
    abstract fun grants(): GrantDao
    abstract fun content(): ContentDao
    abstract fun activity(): ActivityDao
    abstract fun downloads(): DownloadDao
    abstract fun maintenance(): MaintenanceDao
    abstract fun guideMaintenance(): GuideMaintenanceDao
}

@Suppress("NO_ACTUAL_FOR_EXPECT", "EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
expect object OpenTvServerDatabaseConstructor : RoomDatabaseConstructor<OpenTvServerDatabase> {
    override fun initialize(): OpenTvServerDatabase
}
