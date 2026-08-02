package com.buco7854.opentv.serverdata.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.buco7854.opentv.data.db.ProgrammeRow

@Dao
interface UserDao {
    @Query("SELECT * FROM users ORDER BY normalizedUsername")
    suspend fun all(): List<UserRow>

    @Query("""
        SELECT users.id AS userId,
               EXISTS(SELECT 1 FROM password_credentials WHERE userId = users.id) AS hasPassword,
               EXISTS(SELECT 1 FROM totp_credentials
                      WHERE userId = users.id AND confirmed = 1) AS hasTotp,
               EXISTS(SELECT 1 FROM webauthn_credentials
                      WHERE userId = users.id) AS hasWebAuthn,
               EXISTS(SELECT 1 FROM oidc_identities WHERE userId = users.id) AS hasOidc
        FROM users ORDER BY users.normalizedUsername
    """)
    suspend fun credentialMethods(): List<UserCredentialMethodsRow>

    @Query("SELECT * FROM users WHERE id = :id")
    suspend fun get(id: String): UserRow?

    @Query("SELECT * FROM users WHERE normalizedUsername = :username")
    suspend fun byNormalizedUsername(username: String): UserRow?

    @Query("SELECT COUNT(*) FROM users WHERE status = 'ACTIVE' AND (manualRole = 'ADMIN' OR oidcAdmin = 1)")
    suspend fun activeAdminCount(): Int

    @Query("SELECT COUNT(*) FROM users WHERE status = 'ACTIVE' AND manualRole = 'ADMIN'")
    suspend fun activeManualAdminCount(): Int

    @Insert
    suspend fun insert(row: UserRow)

    @Update
    suspend fun update(row: UserRow)

    @Query("DELETE FROM users WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE users SET lastLoginAtMs = :atMs, updatedAtMs = :atMs WHERE id = :id")
    suspend fun markLogin(id: String, atMs: Long)
}

@Dao
interface CredentialDao {
    @Query("SELECT * FROM password_credentials WHERE userId = :userId")
    suspend fun password(userId: String): PasswordCredentialRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPassword(row: PasswordCredentialRow)

    @Query("DELETE FROM password_credentials WHERE userId = :userId")
    suspend fun deletePassword(userId: String)

    @Query("SELECT * FROM totp_credentials WHERE userId = :userId AND confirmed = 1 ORDER BY createdAtMs")
    suspend fun confirmedTotp(userId: String): List<TotpCredentialRow>

    @Query("SELECT * FROM totp_credentials WHERE id = :id")
    suspend fun totp(id: String): TotpCredentialRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTotp(row: TotpCredentialRow)

    @Query("DELETE FROM totp_credentials WHERE userId = :userId")
    suspend fun deleteTotp(userId: String)

    @Query("DELETE FROM totp_credentials WHERE userId = :userId AND confirmed = 0")
    suspend fun deleteUnconfirmedTotp(userId: String)

    @Query("SELECT * FROM webauthn_credentials WHERE userId = :userId ORDER BY createdAtMs")
    suspend fun webAuthn(userId: String): List<WebAuthnCredentialRow>

    @Query("SELECT * FROM webauthn_credentials WHERE credentialId = :credentialId")
    suspend fun webAuthnById(credentialId: ByteArray): WebAuthnCredentialRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWebAuthn(row: WebAuthnCredentialRow)

    @Query("DELETE FROM webauthn_credentials WHERE userId = :userId")
    suspend fun deleteWebAuthn(userId: String)

    @Query("DELETE FROM webauthn_credentials WHERE userId = :userId AND credentialId = :credentialId")
    suspend fun deleteWebAuthn(userId: String, credentialId: ByteArray): Int

    @Query("SELECT * FROM recovery_codes WHERE userId = :userId AND usedAtMs IS NULL")
    suspend fun unusedRecoveryCodes(userId: String): List<RecoveryCodeRow>

    @Insert
    suspend fun insertRecoveryCodes(rows: List<RecoveryCodeRow>)

    @Query("""
        UPDATE recovery_codes SET usedAtMs = :usedAtMs
        WHERE id = :id AND userId = :userId AND usedAtMs IS NULL
    """)
    suspend fun consumeRecoveryCode(userId: String, id: String, usedAtMs: Long): Int

    @Query("DELETE FROM recovery_codes WHERE userId = :userId")
    suspend fun deleteRecoveryCodes(userId: String)

    @Transaction
    suspend fun replaceRecoveryCodes(userId: String, rows: List<RecoveryCodeRow>) {
        deleteRecoveryCodes(userId)
        insertRecoveryCodes(rows)
    }

    @Transaction
    suspend fun clearMfa(userId: String) {
        deleteTotp(userId)
        deleteWebAuthn(userId)
        deleteRecoveryCodes(userId)
    }
}

@Dao
interface OidcDao {
    @Query("SELECT * FROM oidc_identities WHERE issuer = :issuer AND subject = :subject")
    suspend fun get(issuer: String, subject: String): OidcIdentityRow?

    @Query("SELECT * FROM oidc_identities WHERE userId = :userId")
    suspend fun forUser(userId: String): List<OidcIdentityRow>

    @Query("SELECT EXISTS(SELECT 1 FROM oidc_identities WHERE userId = :userId AND adminMapped = 1)")
    suspend fun hasAdminMapping(userId: String): Boolean

    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM oidc_identities
            JOIN users ON users.id = oidc_identities.userId
            WHERE users.status = 'ACTIVE'
              AND (users.manualRole = 'ADMIN' OR oidc_identities.adminMapped = 1)
        )
    """)
    suspend fun hasUsableAdminIdentity(): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: OidcIdentityRow)

    @Query("SELECT * FROM pending_oidc_identities ORDER BY createdAtMs")
    suspend fun pending(): List<PendingOidcIdentityRow>

    @Query("SELECT * FROM pending_oidc_identities WHERE issuer = :issuer AND subject = :subject")
    suspend fun pending(issuer: String, subject: String): PendingOidcIdentityRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPending(row: PendingOidcIdentityRow)

    @Query("DELETE FROM pending_oidc_identities WHERE issuer = :issuer AND subject = :subject")
    suspend fun deletePending(issuer: String, subject: String)
}

@Dao
interface SessionDao {
    @Query("SELECT * FROM auth_sessions WHERE tokenHash = :tokenHash LIMIT 1")
    suspend fun byTokenHash(tokenHash: ByteArray): AuthSessionRow?

    @Query("SELECT * FROM auth_sessions WHERE id = :id")
    suspend fun get(id: String): AuthSessionRow?

    @Query("SELECT * FROM auth_sessions WHERE userId = :userId AND revokedAtMs IS NULL ORDER BY createdAtMs DESC")
    suspend fun activeForUser(userId: String): List<AuthSessionRow>

    @Insert
    suspend fun insert(row: AuthSessionRow)

    @Query("""
        UPDATE auth_sessions SET lastSeenAtMs = :lastSeenAtMs, idleExpiresAtMs = :idleExpiresAtMs
        WHERE id = :id AND revokedAtMs IS NULL
    """)
    suspend fun touch(id: String, lastSeenAtMs: Long, idleExpiresAtMs: Long): Int

    @Query("UPDATE auth_sessions SET revokedAtMs = :atMs WHERE id = :id AND revokedAtMs IS NULL")
    suspend fun revoke(id: String, atMs: Long): Int

    @Query("UPDATE auth_sessions SET revokedAtMs = :atMs WHERE userId = :userId AND revokedAtMs IS NULL")
    suspend fun revokeUser(userId: String, atMs: Long): Int

    @Query("""
        UPDATE auth_sessions SET revokedAtMs = :atMs
        WHERE authMethod = 'PASSWORD' AND revokedAtMs IS NULL
    """)
    suspend fun revokePasswordSessions(atMs: Long): Int

    @Query("""
        UPDATE auth_sessions SET revokedAtMs = :atMs
        WHERE authMethod = 'PASSWORD' AND mfaSatisfiedAtMs IS NULL AND revokedAtMs IS NULL
        AND userId IN (
            SELECT id FROM users
            WHERE (:requireAdmin = 1 AND (manualRole = 'ADMIN' OR oidcAdmin = 1))
               OR (:requireUser = 1 AND manualRole != 'ADMIN' AND oidcAdmin = 0)
        )
    """)
    suspend fun revokePasswordSessionsMissingMfa(
        atMs: Long,
        requireUser: Boolean,
        requireAdmin: Boolean,
    ): Int

    @Query("DELETE FROM auth_sessions WHERE absoluteExpiresAtMs < :beforeMs OR (revokedAtMs IS NOT NULL AND revokedAtMs < :beforeMs)")
    suspend fun prune(beforeMs: Long): Int
}

@Dao
interface ChallengeDao {
    @Query("SELECT * FROM auth_challenges WHERE tokenHash = :tokenHash AND kind = :kind LIMIT 1")
    suspend fun byToken(kind: String, tokenHash: ByteArray): AuthChallengeRow?

    @Query("SELECT * FROM auth_challenges WHERE id = :id")
    suspend fun get(id: String): AuthChallengeRow?

    @Insert
    suspend fun insert(row: AuthChallengeRow)

    @Update
    suspend fun update(row: AuthChallengeRow)

    @Query("UPDATE auth_challenges SET consumedAtMs = :atMs WHERE id = :id AND consumedAtMs IS NULL")
    suspend fun consume(id: String, atMs: Long): Int

    @Query("""
        UPDATE auth_challenges SET consumedAtMs = :atMs
        WHERE kind = :kind AND userId = :userId AND consumedAtMs IS NULL
    """)
    suspend fun consumeForUser(kind: String, userId: String, atMs: Long): Int

    @Query("""
        UPDATE auth_challenges SET userId = :userId, payloadJson = :payload
        WHERE id = :id AND (userId IS NULL OR userId = :userId)
          AND consumedAtMs IS NULL AND expiresAtMs > :now
    """)
    suspend fun claimDeviceLink(
        id: String,
        userId: String,
        payload: String,
        now: Long,
    ): Int

    @Query("""
        UPDATE auth_challenges SET payloadJson = :payload
        WHERE id = :id AND userId = :userId AND consumedAtMs IS NULL
          AND expiresAtMs > :now
    """)
    suspend fun completeDeviceLinkDecision(
        id: String,
        userId: String,
        payload: String,
        now: Long,
    ): Int

    @Query("UPDATE auth_challenges SET attempts = attempts + 1 WHERE id = :id")
    suspend fun incrementAttempts(id: String): Int

    @Query("""
        SELECT * FROM auth_challenges
        WHERE kind = :kind AND consumedAtMs IS NULL AND expiresAtMs > :nowMs
    """)
    suspend fun active(kind: String, nowMs: Long): List<AuthChallengeRow>

    @Query("""
        SELECT COUNT(*) FROM auth_challenges
        WHERE kind = :kind AND consumedAtMs IS NULL AND expiresAtMs > :nowMs
    """)
    suspend fun activeCount(kind: String, nowMs: Long): Int

    @Query("DELETE FROM auth_challenges WHERE expiresAtMs < :beforeMs OR (consumedAtMs IS NOT NULL AND consumedAtMs < :beforeMs)")
    suspend fun prune(beforeMs: Long): Int
}

@Dao
interface GrantDao {
    @Query("SELECT playlistId FROM default_playlist_template ORDER BY playlistId")
    suspend fun defaults(): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addDefault(row: DefaultPlaylistRow)

    @Query("DELETE FROM default_playlist_template WHERE playlistId = :playlistId")
    suspend fun removeDefault(playlistId: Long)

    @Query("DELETE FROM default_playlist_template")
    suspend fun clearDefaults()

    @Query("SELECT playlistId FROM user_playlist_grants WHERE userId = :userId ORDER BY playlistId")
    suspend fun forUser(userId: String): List<Long>

    @Query("SELECT * FROM user_playlist_grants ORDER BY userId, playlistId")
    suspend fun allUserGrants(): List<UserPlaylistGrantRow>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun grant(row: UserPlaylistGrantRow)

    @Query("DELETE FROM user_playlist_grants WHERE userId = :userId")
    suspend fun clearForUser(userId: String)

    @Query("DELETE FROM user_playlist_grants WHERE userId = :userId AND playlistId = :playlistId")
    suspend fun revoke(userId: String, playlistId: Long)

    @Transaction
    suspend fun replaceDefaults(ids: List<Long>) {
        clearDefaults()
        ids.distinct().forEach { addDefault(DefaultPlaylistRow(it)) }
    }

    @Transaction
    suspend fun replaceForUser(userId: String, ids: List<Long>, grantedAtMs: Long) {
        clearForUser(userId)
        ids.distinct().forEach { grant(UserPlaylistGrantRow(userId, it, grantedAtMs)) }
    }
}

@Dao
interface ContentDao {
    @Query("SELECT * FROM content_identities WHERE contentId = :contentId")
    suspend fun get(contentId: String): ContentIdentityRow?

    /**
     * Filters on the whole of the `(playlistId, kind, providerFingerprint)` index. Leaving
     * `kind` out made SQLite stop at the first column and scan every identity in the
     * playlist, which browsing does on each page of a large catalog.
     */
    @Query("""
        SELECT * FROM content_identities
        WHERE playlistId = :playlistId AND kind = :kind AND providerFingerprint IN (:fingerprints)
    """)
    suspend fun byFingerprints(
        playlistId: Long,
        kind: Int,
        fingerprints: List<String>,
    ): List<ContentIdentityRow>

    @Query("SELECT * FROM content_identities WHERE contentId IN (:contentIds)")
    suspend fun byContentIds(contentIds: List<String>): List<ContentIdentityRow>

    @Query("SELECT * FROM content_identities WHERE playlistId = :playlistId")
    suspend fun forPlaylist(playlistId: Long): List<ContentIdentityRow>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: ContentIdentityRow): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<ContentIdentityRow>): List<Long>

    @Update
    suspend fun update(row: ContentIdentityRow): Int

    @Update
    suspend fun updateAll(rows: List<ContentIdentityRow>): Int

    @Transaction
    suspend fun upsert(row: ContentIdentityRow) {
        if (insert(row) == -1L) update(row)
    }

    @Query("""
        UPDATE content_identities SET retired = 1, currentChannelId = NULL
        WHERE playlistId = :playlistId AND lastSeenAtMs < :seenAtMs
    """)
    suspend fun retireNotSeen(playlistId: Long, seenAtMs: Long)

}

@Dao
interface ActivityDao {
    @Query("SELECT * FROM user_resume WHERE userId = :userId ORDER BY updatedAtMs DESC")
    suspend fun resumeForUser(userId: String): List<UserResumeRow>

    @Query("SELECT * FROM user_resume WHERE userId = :userId AND contentId = :contentId")
    suspend fun resume(userId: String, contentId: String): UserResumeRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertResume(row: UserResumeRow)

    @Query("DELETE FROM user_resume WHERE userId = :userId AND contentId = :contentId")
    suspend fun deleteResume(userId: String, contentId: String)

    @Query("""
        DELETE FROM user_resume WHERE userId = :userId
        AND contentId IN (SELECT contentId FROM content_identities WHERE playlistId = :playlistId)
    """)
    suspend fun clearResumeForPlaylist(userId: String, playlistId: Long)

    @Query("DELETE FROM user_resume WHERE updatedAtMs < :beforeMs")
    suspend fun pruneResume(beforeMs: Long)

    @Query("SELECT * FROM user_favorites WHERE userId = :userId ORDER BY addedAtMs DESC")
    suspend fun favorites(userId: String): List<UserFavoriteRow>

    @Query("""
        SELECT user_favorites.contentId AS contentId,
               content_identities.playlistId AS playlistId,
               content_identities.kind AS kind,
               content_identities.currentChannelId AS currentChannelId,
               content_identities.retired AS retired,
               user_favorites.addedAtMs AS addedAtMs
        FROM user_favorites
        JOIN content_identities
          ON content_identities.contentId = user_favorites.contentId
        WHERE user_favorites.userId = :userId
        ORDER BY user_favorites.addedAtMs DESC
    """)
    suspend fun favoriteIdentities(userId: String): List<FavoriteIdentityRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addFavorite(row: UserFavoriteRow)

    @Query("DELETE FROM user_favorites WHERE userId = :userId AND contentId = :contentId")
    suspend fun removeFavorite(userId: String, contentId: String)
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM download_blobs WHERE id = :id")
    suspend fun blob(id: String): DownloadBlobRow?

    @Query("SELECT * FROM download_blobs WHERE contentId = :contentId")
    suspend fun blobForContent(contentId: String): DownloadBlobRow?

    @Query("SELECT * FROM download_blobs WHERE status = :status ORDER BY createdAtMs")
    suspend fun blobsByStatus(status: String): List<DownloadBlobRow>

    @Query("""
        SELECT download_blobs.* FROM download_blobs
        JOIN content_identities ON content_identities.contentId = download_blobs.contentId
        WHERE content_identities.playlistId = :playlistId
    """)
    suspend fun blobsForPlaylist(playlistId: Long): List<DownloadBlobRow>

    @Query("""
        SELECT * FROM download_blobs
        WHERE id NOT IN (SELECT DISTINCT blobId FROM user_downloads)
    """)
    suspend fun orphanBlobs(): List<DownloadBlobRow>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBlob(row: DownloadBlobRow): Long

    @Update
    suspend fun updateBlob(row: DownloadBlobRow): Int

    @Transaction
    suspend fun upsertBlob(row: DownloadBlobRow) {
        if (insertBlob(row) == -1L) updateBlob(row)
    }

    @Query("""
        UPDATE download_blobs SET downloadedBytes = :downloadedBytes, totalBytes = :totalBytes,
        status = :newStatus, updatedAtMs = :updatedAtMs
        WHERE id = :id AND status IN (:expectedStatuses)
    """)
    suspend fun updateBlobProgress(
        id: String,
        downloadedBytes: Long,
        totalBytes: Long,
        newStatus: String,
        updatedAtMs: Long,
        expectedStatuses: List<String>,
    ): Int

    @Query("""
        UPDATE download_blobs SET status = :newStatus, error = :error, updatedAtMs = :updatedAtMs
        WHERE id = :id AND status IN (:expectedStatuses)
    """)
    suspend fun updateBlobStatus(
        id: String,
        newStatus: String,
        error: String?,
        updatedAtMs: Long,
        expectedStatuses: List<String>,
    ): Int

    @Query("DELETE FROM download_blobs WHERE id = :id")
    suspend fun deleteBlob(id: String)

    @Query("SELECT * FROM user_downloads WHERE id = :id")
    suspend fun userDownload(id: String): UserDownloadRow?

    @Query("SELECT * FROM user_downloads WHERE userId = :userId ORDER BY createdAtMs DESC")
    suspend fun forUser(userId: String): List<UserDownloadRow>

    @Query("SELECT * FROM user_downloads WHERE userId = :userId AND blobId = :blobId")
    suspend fun forUserBlob(userId: String, blobId: String): UserDownloadRow?

    @Query("""
        SELECT user_downloads.id AS userDownloadId,
               user_downloads.userId AS userId,
               download_blobs.id AS blobId,
               user_downloads.active AS active,
               user_downloads.suspended AS suspended,
               user_downloads.createdAtMs AS userCreatedAtMs,
               user_downloads.updatedAtMs AS userUpdatedAtMs,
               download_blobs.contentId AS contentId,
               download_blobs.title AS title,
               download_blobs.sourceUrl AS sourceUrl,
               download_blobs.filePath AS filePath,
               download_blobs.status AS status,
               download_blobs.totalBytes AS totalBytes,
               download_blobs.downloadedBytes AS downloadedBytes,
               download_blobs.error AS error,
               download_blobs.createdAtMs AS blobCreatedAtMs,
               download_blobs.updatedAtMs AS blobUpdatedAtMs
        FROM user_downloads
        JOIN download_blobs ON download_blobs.id = user_downloads.blobId
        WHERE user_downloads.userId = :userId
        ORDER BY user_downloads.createdAtMs DESC
    """)
    suspend fun listingForUser(userId: String): List<DownloadListingRow>

    @Query("SELECT * FROM user_downloads ORDER BY createdAtMs DESC")
    suspend fun allUserDownloads(): List<UserDownloadRow>

    @Query("""
        SELECT user_downloads.id AS userDownloadId,
               user_downloads.userId AS userId,
               download_blobs.id AS blobId,
               user_downloads.active AS active,
               user_downloads.suspended AS suspended,
               user_downloads.createdAtMs AS userCreatedAtMs,
               user_downloads.updatedAtMs AS userUpdatedAtMs,
               download_blobs.contentId AS contentId,
               download_blobs.title AS title,
               download_blobs.sourceUrl AS sourceUrl,
               download_blobs.filePath AS filePath,
               download_blobs.status AS status,
               download_blobs.totalBytes AS totalBytes,
               download_blobs.downloadedBytes AS downloadedBytes,
               download_blobs.error AS error,
               download_blobs.createdAtMs AS blobCreatedAtMs,
               download_blobs.updatedAtMs AS blobUpdatedAtMs
        FROM user_downloads
        JOIN download_blobs ON download_blobs.id = user_downloads.blobId
        ORDER BY user_downloads.createdAtMs DESC
    """)
    suspend fun allListings(): List<DownloadListingRow>

    @Query("SELECT * FROM user_downloads WHERE blobId = :blobId")
    suspend fun forBlob(blobId: String): List<UserDownloadRow>

    @Query("SELECT COUNT(*) FROM user_downloads WHERE blobId = :blobId")
    suspend fun referenceCount(blobId: String): Int

    @Query("SELECT COUNT(*) FROM user_downloads WHERE blobId = :blobId AND active = 1 AND suspended = 0")
    suspend fun activeReferenceCount(blobId: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertUserDownload(row: UserDownloadRow): Long

    @Update
    suspend fun updateUserDownload(row: UserDownloadRow): Int

    @Transaction
    suspend fun upsertUserDownload(row: UserDownloadRow) {
        if (insertUserDownload(row) == -1L) updateUserDownload(row)
    }

    @Query("DELETE FROM user_downloads WHERE id = :id")
    suspend fun deleteUserDownload(id: String)

    @Query("""
        UPDATE user_downloads
        SET suspended = :suspended,
            active = CASE WHEN :suspended = 1 THEN 0 ELSE active END,
            updatedAtMs = :atMs
        WHERE userId = :userId AND blobId IN (
            SELECT download_blobs.id FROM download_blobs
            JOIN content_identities ON content_identities.contentId = download_blobs.contentId
            WHERE content_identities.playlistId = :playlistId
        )
    """)
    suspend fun suspendForPlaylist(userId: String, playlistId: Long, suspended: Boolean, atMs: Long): Int
}

@Dao
interface MaintenanceDao {
    @Query("SELECT EXISTS(SELECT 1 FROM playlist_deletions WHERE playlistId = :playlistId)")
    suspend fun isPlaylistDeleting(playlistId: Long): Boolean

    @Query("SELECT * FROM playlist_deletions ORDER BY requestedAtMs")
    suspend fun pendingPlaylistDeletions(): List<PlaylistDeletionRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun beginPlaylistDeletion(row: PlaylistDeletionRow)

    @Query("DELETE FROM playlist_deletions WHERE playlistId = :playlistId")
    suspend fun finishPlaylistDeletion(playlistId: Long)
}

/** Read-only guide projections for the bounded set of channels a client is displaying. */
@Dao
interface GuideDecorationDao {
    /** Provider guides can overlap; the active row with the latest start owns that channel. */
    @Query("""
        SELECT p.* FROM programmes AS p
        WHERE p.playlistId = :playlistId AND p.tvgId IN (:tvgIds)
          AND p.startMs <= :now AND p.endMs > :now
          AND NOT EXISTS (
              SELECT 1 FROM programmes AS n
              WHERE n.playlistId = p.playlistId AND n.tvgId = p.tvgId
                AND n.startMs <= :now AND n.endMs > :now
                AND n.startMs > p.startMs
          )
    """)
    suspend fun nowAiring(
        playlistId: Long,
        tvgIds: List<String>,
        now: Long,
    ): List<ProgrammeRow>

    @Query("""
        SELECT DISTINCT tvgId FROM programmes
        WHERE playlistId = :playlistId AND tvgId IN (:tvgIds)
    """)
    suspend fun guideIds(playlistId: Long, tvgIds: List<String>): List<String>
}

/** Server-only chunk seams for guide mutations that would otherwise monopolize SQLite's writer. */
@Dao
interface GuideMaintenanceDao {
    @Query("""
        DELETE FROM programmes WHERE id IN (
            SELECT id FROM programmes
            WHERE playlistId = :playlistId AND startMs >= :fromMs
            ORDER BY startMs LIMIT :limit
        )
    """)
    suspend fun deleteFromChunk(playlistId: Long, fromMs: Long, limit: Int): Int

    @Query("""
        DELETE FROM programmes WHERE id IN (
            SELECT id FROM programmes
            WHERE playlistId = :playlistId AND endMs <= :beforeMs
            ORDER BY endMs LIMIT :limit
        )
    """)
    suspend fun pruneChunk(playlistId: Long, beforeMs: Long, limit: Int): Int

    @Query("""
        DELETE FROM programmes WHERE id IN (
            SELECT id FROM programmes WHERE playlistId = :playlistId LIMIT :limit
        )
    """)
    suspend fun deleteForPlaylistChunk(playlistId: Long, limit: Int): Int
}
