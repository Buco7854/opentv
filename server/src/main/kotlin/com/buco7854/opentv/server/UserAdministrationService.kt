package com.buco7854.opentv.server

import com.buco7854.opentv.serverdata.ChallengeKind
import com.buco7854.opentv.serverdata.UserRole
import com.buco7854.opentv.serverdata.UserStatus
import com.buco7854.opentv.serverdata.db.OidcIdentityRow
import com.buco7854.opentv.serverdata.db.ServerUserDatabase
import com.buco7854.opentv.serverdata.db.UserRow
import com.buco7854.opentv.serverdata.db.replaceUserPlaylistGrants
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/** Administrative user, identity, session, and playlist-entitlement use cases. */
internal class UserAdministrationService(
    private val db: ServerUserDatabase,
    private val accounts: AuthAccountService,
    private val sessions: PersistentSessionService,
    private val mutation: Mutex,
    private val cleanup: UserStateCleanupCoordinator,
    private val clock: () -> Long,
) {
    suspend fun pendingOidc(actor: Actor): List<PendingOidcDto> {
        requireAdmin(actor)
        return db.oidc().pending().map {
            PendingOidcDto(
                it.issuer, it.subject, it.usernameClaim, it.displayNameClaim,
                runCatching { Json.decodeFromString<List<String>>(it.groupsJson) }
                    .getOrDefault(emptyList()),
                it.adminMapped, it.createdAtMs,
            )
        }
    }

    suspend fun approveOidc(actor: Actor, request: ApproveOidcRequestDto): AdminUserDto =
        mutation.withLock {
            requireAdmin(actor)
            val pending = db.oidc().pending(request.issuer, request.subject)
                ?: throw ResourceNotFound("oidc identity")
            val now = clock()
            val target = request.userId?.let {
                db.users().get(it) ?: throw ResourceNotFound("user")
            } ?: accounts.createUser(
                accounts.availableOidcUsername(
                    pending.usernameClaim ?: "oidc-user",
                    request.issuer,
                    request.subject,
                ),
                pending.displayNameClaim ?: pending.usernameClaim ?: "OIDC user",
                UserStatus.ACTIVE,
                UserRole.USER,
                now,
            ).also { accounts.copyDefaultGrants(it.id, now) }
            db.oidc().upsert(
                OidcIdentityRow(
                    request.issuer, request.subject, target.id,
                    pending.usernameClaim, pending.displayNameClaim,
                    pending.groupsJson, pending.adminMapped, now,
                ),
            )
            val updated = target.copy(
                oidcAdmin = db.oidc().hasAdminMapping(target.id),
                updatedAtMs = now,
            )
            db.users().update(updated)
            db.oidc().deletePending(request.issuer, request.subject)
            accounts.adminUserDto(updated)
        }

    suspend fun users(actor: Actor): List<AdminUserDto> {
        requireAdmin(actor)
        return db.users().all().map { accounts.adminUserDto(it) }
    }

    suspend fun resume(actor: Actor, userId: String): List<ResumePointDto> {
        requireAdmin(actor)
        db.users().get(userId) ?: throw ResourceNotFound("user")
        return db.activity().resumeForUser(userId).map {
            ResumePointDto(it.contentId, it.positionMs, it.durationMs, it.updatedAtMs)
        }
    }

    suspend fun deleteResume(actor: Actor, userId: String, contentId: String) {
        requireAdmin(actor)
        db.activity().deleteResume(userId, contentId)
    }

    suspend fun create(
        actor: Actor,
        request: CreateUserRequestDto,
    ): CreatedUserDto = mutation.withLock {
        requireAdmin(actor)
        val role = request.role.uppercase()
        require(role == UserRole.USER || role == UserRole.ADMIN) { "Unknown role" }
        val now = clock()
        val user = accounts.createUser(
            request.username,
            request.displayName.ifBlank { request.username },
            UserStatus.INVITED,
            role,
            now,
        )
        val challenge = accounts.issueChallenge(
            user.id, ChallengeKind.ACTIVATION, "", 24 * 60 * 60_000L,
        )
        CreatedUserDto(accounts.adminUserDto(user), challenge.first)
    }

    suspend fun update(
        actor: Actor,
        userId: String,
        request: UpdateUserRequestDto,
    ): AdminUserDto = mutation.withLock {
        requireAdmin(actor)
        val current = db.users().get(userId) ?: throw ResourceNotFound("user")
        val role = request.role?.uppercase() ?: current.manualRole
        val status = request.status?.uppercase() ?: current.status
        require(role == UserRole.USER || role == UserRole.ADMIN) { "Unknown role" }
        require(
            status in setOf(
                UserStatus.INVITED,
                UserStatus.PENDING,
                UserStatus.ACTIVE,
                UserStatus.DISABLED,
            )
        ) { "Unknown user status" }
        if (role != UserRole.ADMIN || status != UserStatus.ACTIVE) {
            ensureNotFinalManualAdmin(current, "disable or demote")
        }
        val username = request.username?.trim() ?: current.username
        val normalized = AuthCrypto.normalizeUsername(username)
        db.users().byNormalizedUsername(normalized)?.let {
            require(it.id == current.id) { "Username is already in use" }
        }
        val updated = current.copy(
            username = username,
            normalizedUsername = normalized,
            displayName = request.displayName?.trim()?.ifBlank { username }?.also {
                require(it.codePointCount(0, it.length) <= 128) {
                    "Display name must be at most 128 characters"
                }
            } ?: current.displayName,
            status = status,
            manualRole = role,
            updatedAtMs = clock(),
        )
        db.users().update(updated)
        if (current.status != status || current.manualRole != role) {
            sessions.revokeUser(userId, clock())
        }
        accounts.adminUserDto(updated)
    }

    suspend fun delete(actor: Actor, userId: String) = mutation.withLock {
        requireAdmin(actor)
        val user = db.users().get(userId) ?: throw ResourceNotFound("user")
        ensureNotFinalManualAdmin(user, "delete")
        sessions.revokeUser(userId, clock())
        db.users().delete(userId)
        cleanup.userDeleted(userId)
    }

    suspend fun reset(actor: Actor, userId: String): ResetUserDto =
        mutation.withLock {
            requireAdmin(actor)
            val user = db.users().get(userId) ?: throw ResourceNotFound("user")
            ensureNotFinalManualAdmin(user, "reset")
            sessions.revokeUser(userId, clock())
            db.credentials().deletePassword(userId)
            db.credentials().clearMfa(userId)
            db.users().update(user.copy(status = UserStatus.INVITED, updatedAtMs = clock()))
            val challenge = accounts.issueChallenge(
                userId, ChallengeKind.PASSWORD_RESET, "", 24 * 60 * 60_000L,
            )
            ResetUserDto(challenge.first)
        }

    suspend fun revokeSession(actor: Actor, userId: String, sessionId: String?) {
        requireAdmin(actor)
        if (sessionId == null) sessions.revokeUser(userId, clock())
        else {
            val row = db.sessions().get(sessionId) ?: throw ResourceNotFound("session")
            if (row.userId != userId) throw ResourceNotFound("session")
            sessions.revoke(userId, sessionId, clock())
        }
    }

    suspend fun sessions(actor: Actor, userId: String): List<AuthSessionDto> {
        requireAdmin(actor)
        db.users().get(userId) ?: throw ResourceNotFound("user")
        return db.sessions().activeForUser(userId).map {
            AuthSessionDto(
                it.id, it.authMethod, it.clientKind, it.deviceName, it.createdAtMs,
                it.lastSeenAtMs, it.idleExpiresAtMs, it.absoluteExpiresAtMs,
            )
        }
    }

    suspend fun defaultPlaylists(actor: Actor): List<Long> {
        requireAdmin(actor)
        return db.grants().defaults()
    }

    suspend fun setDefaultPlaylists(actor: Actor, ids: List<Long>) {
        requireAdmin(actor)
        db.grants().replaceDefaults(accounts.validatePlaylistIds(ids))
    }

    suspend fun setUserPlaylists(actor: Actor, userId: String, ids: List<Long>) =
        mutation.withLock {
            requireAdmin(actor)
            db.users().get(userId) ?: throw ResourceNotFound("user")
            val replacement = db.replaceUserPlaylistGrants(
                userId,
                accounts.validatePlaylistIds(ids),
                clock(),
            )
            replacement.removed.forEach { cleanup.playlistGrantRevoked(userId, it) }
        }

    private fun requireAdmin(actor: Actor) {
        if (!actor.isAdmin) throw ForbiddenApiException()
    }

    private suspend fun ensureNotFinalManualAdmin(user: UserRow, action: String) {
        if (user.manualRole == UserRole.ADMIN && user.status == UserStatus.ACTIVE &&
            db.users().activeManualAdminCount() <= 1
        ) {
            throw IllegalArgumentException(
                "Cannot $action the final manually managed administrator"
            )
        }
    }
}
