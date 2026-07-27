package com.buco7854.opentv.serverdata

object UserStatus {
    const val INVITED = "INVITED"
    /** Legacy persisted value kept only so existing rows remain readable. No flow may assign it. */
    const val PENDING = "PENDING"
    const val ACTIVE = "ACTIVE"
    const val DISABLED = "DISABLED"
}

object UserRole {
    const val USER = "USER"
    const val ADMIN = "ADMIN"
}

object AuthMethod {
    const val PASSWORD = "PASSWORD"
    const val OIDC = "OIDC"
    const val WEBAUTHN = "WEBAUTHN"
}

object ClientKind {
    const val BROWSER = "BROWSER"
    const val NATIVE = "NATIVE"
    const val LINKED_DEVICE = "LINKED_DEVICE"
}

object ChallengeKind {
    const val BOOTSTRAP = "BOOTSTRAP"
    const val ACTIVATION = "ACTIVATION"
    const val PASSWORD = "PASSWORD"
    const val PASSWORD_RESET = "PASSWORD_RESET"
    const val MFA = "MFA"
    const val TOTP_ENROLL = "TOTP_ENROLL"
    const val WEBAUTHN_REGISTER = "WEBAUTHN_REGISTER"
    const val WEBAUTHN_AUTHENTICATE = "WEBAUTHN_AUTHENTICATE"
    const val WEBAUTHN_LOGIN = "WEBAUTHN_LOGIN"
    const val OIDC = "OIDC"
    const val DEVICE_LINK = "DEVICE_LINK"
}

object DownloadBlobStatus {
    const val QUEUED = "QUEUED"
    const val RUNNING = "RUNNING"
    const val DONE = "DONE"
    const val FAILED = "FAILED"
    const val PAUSED = "PAUSED"
    const val CANCELLED = "CANCELLED"
}

data class UserRecord(
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
) {
    val effectiveRole: String
        get() = if (manualRole == UserRole.ADMIN || oidcAdmin) UserRole.ADMIN else UserRole.USER
}

data class PasswordCredential(
    val userId: String,
    val hash: ByteArray,
    val salt: ByteArray,
    val memoryKb: Int,
    val iterations: Int,
    val parallelism: Int,
    val version: Int,
    val changedAtMs: Long,
)

data class SessionRecord(
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

data class ContentIdentityRecord(
    val contentId: String,
    val playlistId: Long,
    val kind: Int,
    val providerFingerprint: String,
    val currentChannelId: Long?,
    val lastSeenAtMs: Long,
    val retired: Boolean,
)

data class ResumeRecord(
    val userId: String,
    val contentId: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAtMs: Long,
)

data class FavoriteRecord(
    val userId: String,
    val contentId: String,
    val addedAtMs: Long,
)

data class DownloadBlobRecord(
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

data class UserDownloadRecord(
    val id: String,
    val userId: String,
    val blobId: String,
    val active: Boolean,
    val suspended: Boolean,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)
