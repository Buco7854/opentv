package com.buco7854.opentv.serverdata.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import com.buco7854.opentv.data.db.ChannelRow
import com.buco7854.opentv.data.db.PlaylistRow

@Entity(
    tableName = "users",
    indices = [Index(value = ["normalizedUsername"], unique = true)],
    primaryKeys = ["id"],
)
data class UserRow(
    val id: String,
    val username: String,
    val normalizedUsername: String,
    val displayName: String,
    val status: String,
    val manualRole: String,
    val oidcAdmin: Boolean,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val lastLoginAtMs: Long?,
)

@Entity(
    tableName = "password_credentials",
    primaryKeys = ["userId"],
    foreignKeys = [ForeignKey(
        entity = UserRow::class,
        parentColumns = ["id"],
        childColumns = ["userId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class PasswordCredentialRow(
    val userId: String,
    val hash: ByteArray,
    val salt: ByteArray,
    val memoryKb: Int,
    val iterations: Int,
    val parallelism: Int,
    val version: Int,
    val changedAtMs: Long,
)

@Entity(
    tableName = "oidc_identities",
    primaryKeys = ["issuer", "subject"],
    indices = [Index("userId")],
    foreignKeys = [ForeignKey(
        entity = UserRow::class,
        parentColumns = ["id"],
        childColumns = ["userId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class OidcIdentityRow(
    val issuer: String,
    val subject: String,
    val userId: String,
    val usernameClaim: String?,
    val displayNameClaim: String?,
    val groupsJson: String,
    val adminMapped: Boolean,
    val updatedAtMs: Long,
)

@Entity(
    tableName = "pending_oidc_identities",
    primaryKeys = ["issuer", "subject"],
)
data class PendingOidcIdentityRow(
    val issuer: String,
    val subject: String,
    val usernameClaim: String?,
    val displayNameClaim: String?,
    val groupsJson: String,
    val adminMapped: Boolean,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

@Entity(
    tableName = "totp_credentials",
    primaryKeys = ["id"],
    indices = [Index(value = ["userId"], unique = true)],
    foreignKeys = [ForeignKey(
        entity = UserRow::class,
        parentColumns = ["id"],
        childColumns = ["userId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class TotpCredentialRow(
    val id: String,
    val userId: String,
    val encryptedSecret: ByteArray,
    val label: String,
    val confirmed: Boolean,
    val lastAcceptedStep: Long?,
    val createdAtMs: Long,
)

@Entity(
    tableName = "webauthn_credentials",
    primaryKeys = ["credentialId"],
    indices = [Index("userId")],
    foreignKeys = [ForeignKey(
        entity = UserRow::class,
        parentColumns = ["id"],
        childColumns = ["userId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class WebAuthnCredentialRow(
    val credentialId: ByteArray,
    val userId: String,
    val publicKeyCose: ByteArray,
    val signCount: Long,
    val transportsJson: String,
    val backupEligible: Boolean,
    val backedUp: Boolean,
    val label: String,
    val createdAtMs: Long,
    val lastUsedAtMs: Long?,
)

@Entity(
    tableName = "recovery_codes",
    primaryKeys = ["id"],
    indices = [Index("userId")],
    foreignKeys = [ForeignKey(
        entity = UserRow::class,
        parentColumns = ["id"],
        childColumns = ["userId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class RecoveryCodeRow(
    val id: String,
    val userId: String,
    val codeHash: ByteArray,
    val createdAtMs: Long,
    val usedAtMs: Long?,
)

@Entity(
    tableName = "auth_sessions",
    primaryKeys = ["id"],
    indices = [Index("userId"), Index(value = ["tokenHash"], unique = true)],
    foreignKeys = [ForeignKey(
        entity = UserRow::class,
        parentColumns = ["id"],
        childColumns = ["userId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class AuthSessionRow(
    val id: String,
    val userId: String,
    val tokenHash: ByteArray,
    val csrfToken: String,
    val authMethod: String,
    val clientKind: String,
    val tokenFamilyId: String,
    val credentialVersion: Int,
    val deviceId: String?,
    val deviceName: String?,
    val mfaSatisfiedAtMs: Long?,
    val createdAtMs: Long,
    val lastSeenAtMs: Long,
    val idleExpiresAtMs: Long,
    val absoluteExpiresAtMs: Long,
    val revokedAtMs: Long?,
)

@Entity(
    tableName = "auth_challenges",
    primaryKeys = ["id"],
    indices = [Index("userId"), Index(value = ["tokenHash"], unique = true)],
    foreignKeys = [ForeignKey(
        entity = UserRow::class,
        parentColumns = ["id"],
        childColumns = ["userId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class AuthChallengeRow(
    val id: String,
    val userId: String?,
    val kind: String,
    val tokenHash: ByteArray,
    val payloadJson: String,
    val attempts: Int,
    val createdAtMs: Long,
    val expiresAtMs: Long,
    val consumedAtMs: Long?,
)

@Entity(
    tableName = "default_playlist_template",
    primaryKeys = ["playlistId"],
    foreignKeys = [ForeignKey(
        entity = PlaylistRow::class,
        parentColumns = ["id"],
        childColumns = ["playlistId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class DefaultPlaylistRow(val playlistId: Long)

@Entity(
    tableName = "user_playlist_grants",
    primaryKeys = ["userId", "playlistId"],
    indices = [Index("playlistId")],
    foreignKeys = [
        ForeignKey(
            entity = UserRow::class,
            parentColumns = ["id"],
            childColumns = ["userId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = PlaylistRow::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class UserPlaylistGrantRow(
    val userId: String,
    val playlistId: Long,
    val grantedAtMs: Long,
)

@Entity(
    tableName = "content_identities",
    primaryKeys = ["contentId"],
    indices = [
        Index(value = ["playlistId", "kind", "providerFingerprint"], unique = true),
        Index("currentChannelId"),
    ],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistRow::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ChannelRow::class,
            parentColumns = ["id"],
            childColumns = ["currentChannelId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
)
data class ContentIdentityRow(
    val contentId: String,
    val playlistId: Long,
    val kind: Int,
    val providerFingerprint: String,
    val currentChannelId: Long?,
    val lastSeenAtMs: Long,
    val retired: Boolean,
)

@Entity(
    tableName = "user_resume",
    primaryKeys = ["userId", "contentId"],
    indices = [Index("contentId"), Index("updatedAtMs")],
    foreignKeys = [
        ForeignKey(entity = UserRow::class, parentColumns = ["id"], childColumns = ["userId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ContentIdentityRow::class, parentColumns = ["contentId"], childColumns = ["contentId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class UserResumeRow(
    val userId: String,
    val contentId: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAtMs: Long,
)

@Entity(
    tableName = "user_favorites",
    primaryKeys = ["userId", "contentId"],
    indices = [Index("contentId")],
    foreignKeys = [
        ForeignKey(entity = UserRow::class, parentColumns = ["id"], childColumns = ["userId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ContentIdentityRow::class, parentColumns = ["contentId"], childColumns = ["contentId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class UserFavoriteRow(
    val userId: String,
    val contentId: String,
    val addedAtMs: Long,
)

@Entity(
    tableName = "download_blobs",
    primaryKeys = ["id"],
    indices = [Index(value = ["contentId"], unique = true)],
    foreignKeys = [ForeignKey(
        entity = ContentIdentityRow::class,
        parentColumns = ["contentId"],
        childColumns = ["contentId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class DownloadBlobRow(
    val id: String,
    val contentId: String,
    val title: String,
    val sourceUrl: String,
    val filePath: String,
    val status: String,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val error: String?,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

@Entity(
    tableName = "user_downloads",
    primaryKeys = ["id"],
    indices = [Index("userId"), Index("blobId"), Index(value = ["userId", "blobId"], unique = true)],
    foreignKeys = [
        ForeignKey(entity = UserRow::class, parentColumns = ["id"], childColumns = ["userId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = DownloadBlobRow::class, parentColumns = ["id"], childColumns = ["blobId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class UserDownloadRow(
    val id: String,
    val userId: String,
    val blobId: String,
    val active: Boolean,
    val suspended: Boolean,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

@Entity(tableName = "playlist_deletions", primaryKeys = ["playlistId"])
data class PlaylistDeletionRow(
    val playlistId: Long,
    val requestedAtMs: Long,
)
