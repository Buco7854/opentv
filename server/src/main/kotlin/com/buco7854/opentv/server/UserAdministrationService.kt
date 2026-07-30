package com.buco7854.opentv.server

import com.buco7854.opentv.contract.*
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import com.buco7854.opentv.serverdata.ChallengeKind
import com.buco7854.opentv.serverdata.UserRole
import com.buco7854.opentv.serverdata.UserStatus
import com.buco7854.opentv.serverdata.db.OidcIdentityRow
import com.buco7854.opentv.serverdata.db.OpenTvServerDatabase
import com.buco7854.opentv.serverdata.db.UserRow
import com.buco7854.opentv.serverdata.db.replaceUserPlaylistGrants
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.util.Locale

/**
 * The lifecycle surface owned by administrators.
 *
 * INVITED is assigned only by invitation/reset flows. PENDING is a legacy persisted value:
 * no production flow creates it, but retaining the string keeps hand-edited or future rows
 * readable. An administrator can move either legacy state only to ACTIVE or DISABLED.
 */
internal object AdminUserLifecycle {
    val settableStatuses: List<String> = listOf(UserStatus.ACTIVE, UserStatus.DISABLED)

    fun statusForUpdate(requested: String?, current: String): String {
        if (requested == null) return current
        val status = requested.trim().uppercase(Locale.ROOT)
        return when (status) {
            UserStatus.ACTIVE, UserStatus.DISABLED -> status
            UserStatus.INVITED -> throw UserStatusNotSettableException(
                status,
                "INVITED is assigned by invitation and reset flows",
            )
            UserStatus.PENDING -> throw UserStatusNotSettableException(
                status,
                "PENDING is a legacy value that no flow creates; choose ACTIVE or DISABLED",
            )
            else -> throw UserStatusNotSettableException(
                status,
                "choose ACTIVE or DISABLED",
            )
        }
    }
}

/**
 * Account mutations that can remove administrator or sign-in access.
 *
 * Keeping both protections here makes their different scopes explicit: an administrator
 * cannot perform a direct lockout mutation on themselves, while nobody can remove access
 * from the final active manually managed administrator.
 */
private enum class AdminAccountMutation(
    val selfLockoutField: String?,
    val selfLockoutMessage: String?,
    val lastAdminAction: String,
    val requiresLocalAccountProvisioning: Boolean = false,
) {
    DEMOTE(
        "role",
        "You cannot demote your own administrator account. Ask another administrator to make this change.",
        "demote",
    ),
    DISABLE(
        "status",
        "You cannot disable your own account. Ask another administrator to make this change.",
        "disable",
    ),
    DELETE(
        "account",
        "You cannot delete your own account. Ask another administrator to make this change.",
        "delete",
    ),
    RESET_CREDENTIALS(
        null,
        null,
        "reset credentials for",
        requiresLocalAccountProvisioning = true,
    ),
}

/** Administrative user, identity, session, and playlist-entitlement use cases. */
internal class UserAdministrationService(
    private val db: OpenTvServerDatabase,
    private val accounts: AuthAccountService,
    private val sessions: PersistentSessionService,
    private val credentials: AuthCredentialService,
    private val config: AuthConfig,
    private val resumeTitles: suspend (Collection<String>) -> Map<String, String>,
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
            val updated = db.useWriterConnection { connection ->
                connection.immediateTransaction {
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
                    val updatedTarget = target.copy(
                        oidcAdmin = db.oidc().hasAdminMapping(target.id),
                        updatedAtMs = now,
                    )
                    db.users().update(updatedTarget)
                    db.oidc().deletePending(request.issuer, request.subject)
                    updatedTarget
                }
            }
            accounts.adminUserDto(updated)
        }

    suspend fun users(actor: Actor): List<AdminUserDto> {
        requireAdmin(actor)
        return db.users().all().map { accounts.adminUserDto(it) }
    }

    suspend fun resume(actor: Actor, userId: String): List<AdminResumeDto> {
        requireAdmin(actor)
        db.users().get(userId) ?: throw ResourceNotFound("user")
        val rows = db.activity().resumeForUser(userId)
        val titles = resumeTitles(rows.map { it.contentId })
        return rows.map {
            AdminResumeDto(
                it.contentId,
                titles[it.contentId],
                it.positionMs,
                it.durationMs,
                it.updatedAtMs,
            )
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
        ensureLocalAccountProvisioningAllowed()
        val role = request.role.uppercase()
        require(role == UserRole.USER || role == UserRole.ADMIN) { "Unknown role" }
        val password = request.password
        password?.let {
            // Validate before any user row exists. preparePassword validates again while hashing.
            AuthCrypto.validatePassword(it)
        }
        val now = clock()
        val user = accounts.prepareUser(
            request.username,
            request.displayName.ifBlank { request.username },
            if (password == null) UserStatus.INVITED else UserStatus.ACTIVE,
            role,
            now,
        )
        val activationToken: String?
        if (password != null) {
            val credential = credentials.preparePassword(user.id, password, now)
            db.useWriterConnection { connection ->
                connection.immediateTransaction {
                    accounts.insertPreparedUser(user)
                    credentials.storePreparedPassword(credential)
                    accounts.copyDefaultGrants(user.id, now)
                }
            }
            activationToken = null
        } else {
            activationToken = db.useWriterConnection { connection ->
                connection.immediateTransaction {
                    accounts.insertPreparedUser(user)
                    accounts.issueChallenge(
                        user.id, ChallengeKind.ACTIVATION, "", 24 * 60 * 60_000L,
                    ).first
                }
            }
        }
        CreatedUserDto(accounts.adminUserDto(user), activationToken)
    }

    suspend fun update(
        actor: Actor,
        userId: String,
        request: UpdateUserRequestDto,
    ): AdminUserDto = mutation.withLock {
        requireAdmin(actor)
        val current = db.users().get(userId) ?: throw ResourceNotFound("user")
        val role = request.role?.uppercase() ?: current.manualRole
        val status = AdminUserLifecycle.statusForUpdate(request.status, current.status)
        require(role == UserRole.USER || role == UserRole.ADMIN) { "Unknown role" }
        val accessMutations = buildList {
            if (current.manualRole == UserRole.ADMIN && role == UserRole.USER) {
                add(AdminAccountMutation.DEMOTE)
            }
            if (current.status == UserStatus.ACTIVE && status == UserStatus.DISABLED) {
                add(AdminAccountMutation.DISABLE)
            }
        }
        ensureAccountMutationsAllowed(actor, current, accessMutations)
        val username = request.username?.trim() ?: current.username
        val normalized = AuthCrypto.normalizeUsername(username)
        db.users().byNormalizedUsername(normalized)?.let {
            if (it.id != current.id) throw UsernameTakenException()
        }
        val now = clock()
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
            updatedAtMs = now,
        )
        val accessChanged = current.status != status || current.manualRole != role
        db.useWriterConnection { connection ->
            connection.immediateTransaction {
                db.users().update(updated)
                if (accessChanged) db.sessions().revokeUser(userId, now)
            }
        }
        if (accessChanged) cleanup.sessionRevoked(userId, null)
        accounts.adminUserDto(updated)
    }

    suspend fun delete(actor: Actor, userId: String) = mutation.withLock {
        requireAdmin(actor)
        val user = db.users().get(userId) ?: throw ResourceNotFound("user")
        ensureAccountMutationsAllowed(actor, user, listOf(AdminAccountMutation.DELETE))
        sessions.revokeUser(userId, clock())
        db.users().delete(userId)
        cleanup.userDeleted(userId)
    }

    suspend fun reset(actor: Actor, userId: String): ResetUserDto =
        mutation.withLock {
            requireAdmin(actor)
            val user = db.users().get(userId) ?: throw ResourceNotFound("user")
            ensureAccountMutationsAllowed(
                actor,
                user,
                listOf(AdminAccountMutation.RESET_CREDENTIALS),
            )
            val now = clock()
            val challenge = db.useWriterConnection { connection ->
                connection.immediateTransaction {
                    db.sessions().revokeUser(userId, now)
                    db.credentials().deletePassword(userId)
                    db.credentials().clearMfa(userId)
                    db.users().update(user.copy(status = UserStatus.INVITED, updatedAtMs = now))
                    accounts.issueChallenge(
                        userId, ChallengeKind.PASSWORD_RESET, "", 24 * 60 * 60_000L,
                    )
                }
            }
            cleanup.sessionRevoked(userId, null)
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

    suspend fun setDefaultPlaylists(actor: Actor, ids: List<Long>) = mutation.withLock {
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

    private suspend fun requireAdmin(actor: Actor) {
        // The route authenticator's Actor is a request-start snapshot. Recheck the persisted
        // account so a request already waiting when another administrator disables or demotes
        // this account cannot run with stale authority.
        if (!actor.isAdmin) throw ForbiddenApiException()
        val current = db.users().get(actor.userId) ?: throw UnauthenticatedApiException()
        if (current.status != UserStatus.ACTIVE) throw UnauthenticatedApiException()
        if (accounts.effectiveRole(current) != UserRole.ADMIN) {
            throw ForbiddenApiException()
        }
    }

    private suspend fun ensureAccountMutationsAllowed(
        actor: Actor,
        target: UserRow,
        mutations: List<AdminAccountMutation>,
    ) {
        if (mutations.any(AdminAccountMutation::requiresLocalAccountProvisioning)) {
            ensureLocalAccountProvisioningAllowed()
        }
        if (actor.userId == target.id) {
            mutations.firstOrNull { it.selfLockoutField != null }?.let {
                throw SelfLockoutForbiddenException(
                    requireNotNull(it.selfLockoutField),
                    requireNotNull(it.selfLockoutMessage),
                )
            }
        }
        if (mutations.isNotEmpty() &&
            target.manualRole == UserRole.ADMIN &&
            target.status == UserStatus.ACTIVE &&
            db.users().activeManualAdminCount() <= 1
        ) {
            throw LastAdminException(mutations.first().lastAdminAction)
        }
    }

    private fun ensureLocalAccountProvisioningAllowed() {
        // Invitations and resets are completed by setting a local password, so issuing
        // either while password authentication is disabled creates an unusable account.
        if (!config.passwordEnabled) throw LocalAccountProvisioningDisabledException()
    }
}
