package com.buco7854.opentv.serverdata.db

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor

@Database(
    entities = [
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
        SecurityEventRow::class,
        PlaylistDeletionRow::class,
    ],
    version = 1,
    exportSchema = true,
)
@ConstructedBy(ServerUserDatabaseConstructor::class)
abstract class ServerUserDatabase : RoomDatabase() {
    abstract fun users(): UserDao
    abstract fun credentials(): CredentialDao
    abstract fun oidc(): OidcDao
    abstract fun sessions(): SessionDao
    abstract fun challenges(): ChallengeDao
    abstract fun grants(): GrantDao
    abstract fun content(): ContentDao
    abstract fun activity(): ActivityDao
    abstract fun downloads(): DownloadDao
    abstract fun securityEvents(): SecurityEventDao
    abstract fun maintenance(): MaintenanceDao
}

@Suppress("NO_ACTUAL_FOR_EXPECT", "EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
expect object ServerUserDatabaseConstructor : RoomDatabaseConstructor<ServerUserDatabase> {
    override fun initialize(): ServerUserDatabase
}
