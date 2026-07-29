package com.buco7854.opentv.server

import com.buco7854.opentv.contract.*
import com.buco7854.opentv.serverdata.UserRole
import com.buco7854.opentv.serverdata.db.AuthChallengeRow
import com.buco7854.opentv.serverdata.db.ServerUserDatabase
import com.buco7854.opentv.serverdata.db.UserPlaylistGrantRow
import com.buco7854.opentv.serverdata.db.UserRow
import java.util.UUID

/**
 * Shared account persistence operations used by authentication flows and user administration.
 *
 * This service deliberately has no dependency on [AuthService], keeping the authentication
 * composition graph acyclic.
 */
internal class AuthAccountService(
    private val db: ServerUserDatabase,
    private val clock: () -> Long,
    private val playlistExists: suspend (Long) -> Boolean,
) {
    suspend fun createUser(
        username: String,
        displayName: String,
        status: String,
        role: String,
        now: Long,
    ): UserRow = prepareUser(username, displayName, status, role, now)
        .also { insertPreparedUser(it) }

    /**
     * Validates and assembles a user without writing it. Administration uses this to commit
     * a new user and its initial password credential in one database transaction.
     */
    suspend fun prepareUser(
        username: String,
        displayName: String,
        status: String,
        role: String,
        now: Long,
    ): UserRow {
        val normalized = AuthCrypto.normalizeUsername(username)
        if (db.users().byNormalizedUsername(normalized) != null) throw UsernameTakenException()
        val cleanDisplayName = displayName.trim().ifBlank { username.trim() }
        require(cleanDisplayName.codePointCount(0, cleanDisplayName.length) <= 128) {
            "Display name must be at most 128 characters"
        }
        return UserRow(
            id = UUID.randomUUID().toString(),
            username = username.trim(),
            normalizedUsername = normalized,
            displayName = cleanDisplayName,
            status = status,
            manualRole = role,
            oidcAdmin = false,
            createdAtMs = now,
            updatedAtMs = now,
            lastLoginAtMs = null,
        )
    }

    suspend fun insertPreparedUser(user: UserRow) = db.users().insert(user)

    suspend fun availableOidcUsername(base: String, issuer: String, subject: String): String {
        val cleanBase = base.trim().ifBlank { "oidc-user" }
        if (db.users().byNormalizedUsername(AuthCrypto.normalizeUsername(cleanBase)) == null) {
            return cleanBase
        }
        val suffix = AuthCrypto.sha256("$issuer\u0000$subject".toByteArray())
            .take(4).joinToString("") { "%02x".format(it) }
        return "$cleanBase-$suffix"
    }

    suspend fun issueChallenge(
        userId: String?,
        kind: String,
        payload: String,
        ttlMs: Long,
    ): Pair<String, Long> {
        val raw = AuthCrypto.token()
        val now = clock()
        val expires = now + ttlMs
        db.challenges().insert(
            AuthChallengeRow(
                id = UUID.randomUUID().toString(),
                userId = userId,
                kind = kind,
                tokenHash = AuthCrypto.hashToken(raw),
                payloadJson = payload,
                attempts = 0,
                createdAtMs = now,
                expiresAtMs = expires,
                consumedAtMs = null,
            ),
        )
        return raw to expires
    }

    suspend fun challenge(kind: String, raw: String): AuthChallengeRow {
        if (raw.isBlank() || raw.length > 512) throw InvalidChallengeException()
        val now = clock()
        return db.challenges().byToken(kind, AuthCrypto.hashToken(raw))
            ?.takeIf { it.consumedAtMs == null && it.expiresAtMs > now }
            ?: throw InvalidChallengeException()
    }

    suspend fun copyDefaultGrants(userId: String, now: Long) {
        db.grants().defaults().forEach {
            db.grants().grant(UserPlaylistGrantRow(userId, it, now))
        }
    }

    suspend fun validatePlaylistIds(ids: List<Long>): List<Long> {
        require(ids.size <= 1_000) { "Too many playlist assignments" }
        val distinct = ids.distinct()
        distinct.forEach {
            if (db.maintenance().isPlaylistDeleting(it) || !playlistExists(it)) {
                throw UnknownPlaylistException(it)
            }
        }
        return distinct
    }

    suspend fun adminUserDto(user: UserRow): AdminUserDto {
        val methods = buildList {
            if (db.credentials().password(user.id) != null) add("password")
            if (db.credentials().confirmedTotp(user.id).isNotEmpty()) add("totp")
            if (db.credentials().webAuthn(user.id).isNotEmpty()) add("webauthn")
            if (db.oidc().forUser(user.id).isNotEmpty()) add("oidc")
        }
        return AdminUserDto(
            id = user.id,
            username = user.username,
            displayName = user.displayName,
            status = user.status,
            manualRole = user.manualRole,
            effectiveRole = effectiveRole(user),
            authMethods = methods,
            playlistIds = db.grants().forUser(user.id),
            createdAtMs = user.createdAtMs,
            lastLoginAtMs = user.lastLoginAtMs,
            settableStatuses = AdminUserLifecycle.settableStatuses,
        )
    }

    suspend fun currentUserDto(
        user: UserRow,
        method: String,
        clientKind: String,
        authSessionId: String,
    ) = CurrentUserDto(
        id = user.id,
        username = user.username,
        displayName = user.displayName,
        role = effectiveRole(user),
        authMethod = method,
        clientKind = clientKind,
        authSessionId = authSessionId,
        playlistIds = db.grants().forUser(user.id),
        hasPassword = db.credentials().password(user.id) != null,
    )

    fun effectiveRole(user: UserRow) =
        if (user.manualRole == UserRole.ADMIN || user.oidcAdmin) UserRole.ADMIN else UserRole.USER
}
