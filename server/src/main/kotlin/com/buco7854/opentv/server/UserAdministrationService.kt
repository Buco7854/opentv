package com.buco7854.opentv.server

import com.buco7854.opentv.serverdata.ChallengeKind
import com.buco7854.opentv.serverdata.UserRole
import com.buco7854.opentv.serverdata.UserStatus
import com.buco7854.opentv.serverdata.db.OidcIdentityRow
import com.buco7854.opentv.serverdata.db.ServerUserDatabase
import com.buco7854.opentv.serverdata.db.replaceUserPlaylistGrants
import kotlinx.serialization.json.Json

/** Administrative user, identity, session, and playlist-entitlement use cases. */
internal class UserAdministrationService(
    private val db: ServerUserDatabase,
    private val auth: AuthService,
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
        auth.mutate {
            requireAdmin(actor)
            val pending = db.oidc().pending(request.issuer, request.subject)
                ?: throw ResourceNotFound("oidc identity")
            val now = clock()
            val target = request.userId?.let {
                db.users().get(it) ?: throw ResourceNotFound("user")
            } ?: auth.createUser(
                auth.availableOidcUsername(
                    pending.usernameClaim ?: "oidc-user",
                    request.issuer,
                    request.subject,
                ),
                pending.displayNameClaim ?: pending.usernameClaim ?: "OIDC user",
                UserStatus.ACTIVE,
                UserRole.USER,
                now,
            ).also { auth.copyDefaultGrants(it.id, now) }
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
            auth.adminUserDto(updated)
        }

    suspend fun users(actor: Actor): List<AdminUserDto> {
        requireAdmin(actor)
        return db.users().all().map { auth.adminUserDto(it) }
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
        clientIp: String,
    ): CreatedUserDto = auth.mutate {
        requireAdmin(actor)
        val role = request.role.uppercase()
        require(role == UserRole.USER || role == UserRole.ADMIN) { "Unknown role" }
        val now = clock()
        val user = auth.createUser(
            request.username,
            request.displayName.ifBlank { request.username },
            UserStatus.INVITED,
            role,
            now,
        )
        val challenge = auth.issueChallenge(
            user.id, ChallengeKind.ACTIVATION, "", 24 * 60 * 60_000L,
        )
        auth.event(actor.userId, user.id, "user_created", clientIp)
        CreatedUserDto(auth.adminUserDto(user), challenge.first)
    }

    suspend fun update(
        actor: Actor,
        userId: String,
        request: UpdateUserRequestDto,
        clientIp: String,
    ): AdminUserDto = auth.mutate {
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
        if (current.manualRole == UserRole.ADMIN && current.status == UserStatus.ACTIVE &&
            (role != UserRole.ADMIN || status != UserStatus.ACTIVE) &&
            db.users().activeManualAdminCount() <= 1
        ) {
            throw IllegalArgumentException(
                "Cannot disable or demote the final manually managed administrator"
            )
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
            auth.revokeUserSessions(userId, clock())
        }
        auth.event(actor.userId, userId, "user_updated", clientIp)
        auth.adminUserDto(updated)
    }

    suspend fun delete(actor: Actor, userId: String, clientIp: String) = auth.mutate {
        requireAdmin(actor)
        val user = db.users().get(userId) ?: throw ResourceNotFound("user")
        if (user.manualRole == UserRole.ADMIN && user.status == UserStatus.ACTIVE &&
            db.users().activeManualAdminCount() <= 1
        ) {
            throw IllegalArgumentException(
                "Cannot delete the final manually managed administrator"
            )
        }
        auth.revokeUserSessions(userId, clock())
        db.securityEvents().deleteForUser(userId)
        db.users().delete(userId)
        cleanup.userDeleted(userId)
    }

    suspend fun reset(actor: Actor, userId: String, clientIp: String): ResetUserDto =
        auth.mutate {
            requireAdmin(actor)
            val user = db.users().get(userId) ?: throw ResourceNotFound("user")
            auth.revokeUserSessions(userId, clock())
            db.credentials().deletePassword(userId)
            db.credentials().clearMfa(userId)
            db.users().update(user.copy(status = UserStatus.INVITED, updatedAtMs = clock()))
            val challenge = auth.issueChallenge(
                userId, ChallengeKind.PASSWORD_RESET, "", 24 * 60 * 60_000L,
            )
            auth.event(actor.userId, userId, "credentials_reset", clientIp)
            ResetUserDto(challenge.first)
        }

    suspend fun revokeSession(actor: Actor, userId: String, sessionId: String?) {
        requireAdmin(actor)
        if (sessionId == null) auth.revokeUserSessions(userId, clock())
        else {
            val row = db.sessions().get(sessionId) ?: throw ResourceNotFound("session")
            if (row.userId != userId) throw ResourceNotFound("session")
            auth.revokeSessionInternal(userId, sessionId, clock())
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
        db.grants().replaceDefaults(auth.validatePlaylistIds(ids))
    }

    suspend fun setUserPlaylists(actor: Actor, userId: String, ids: List<Long>) =
        auth.mutate {
            requireAdmin(actor)
            db.users().get(userId) ?: throw ResourceNotFound("user")
            val replacement = db.replaceUserPlaylistGrants(
                userId,
                auth.validatePlaylistIds(ids),
                clock(),
            )
            replacement.removed.forEach { cleanup.playlistGrantRevoked(userId, it) }
        }

    private fun requireAdmin(actor: Actor) {
        if (!actor.isAdmin) throw ForbiddenApiException()
    }
}
