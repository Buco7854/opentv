package com.buco7854.opentv.server

import com.buco7854.opentv.serverdata.AuthMethod
import com.buco7854.opentv.serverdata.ClientKind
import com.buco7854.opentv.serverdata.UserRole
import com.buco7854.opentv.serverdata.UserStatus
import com.buco7854.opentv.serverdata.db.AuthSessionRow
import com.buco7854.opentv.serverdata.db.ServerUserDatabase
import com.buco7854.opentv.serverdata.db.UserRow
import java.util.UUID

/** Persistent, revocable session storage independent from HTTP cookie delivery. */
internal class PersistentSessionService(
    private val db: ServerUserDatabase,
    private val config: AuthConfig,
    private val cleanup: UserStateCleanupCoordinator,
    private val clock: () -> Long,
) {
    suspend fun issue(user: UserRow, method: String, mfa: Boolean): IssuedSession {
        val now = clock()
        val token = AuthCrypto.token()
        val row = AuthSessionRow(
            id = UUID.randomUUID().toString(),
            userId = user.id,
            tokenHash = AuthCrypto.hashToken(token),
            csrfToken = AuthCrypto.token(),
            authMethod = method,
            clientKind = ClientKind.BROWSER,
            tokenFamilyId = UUID.randomUUID().toString(),
            credentialVersion = db.credentials().password(user.id)?.version ?: 0,
            deviceId = null,
            deviceName = null,
            mfaSatisfiedAtMs = now.takeIf { mfa },
            createdAtMs = now,
            lastSeenAtMs = now,
            idleExpiresAtMs = now + config.sessionIdleMs,
            absoluteExpiresAtMs = now + config.sessionAbsoluteMs,
            revokedAtMs = null,
        )
        db.sessions().insert(row)
        return IssuedSession(token, row, user)
    }

    suspend fun authenticate(rawToken: String?): Pair<Actor, AuthSessionRow>? {
        if (rawToken.isNullOrBlank() || rawToken.length > 512) return null
        val now = clock()
        val session = db.sessions().byTokenHash(AuthCrypto.hashToken(rawToken)) ?: return null
        if (session.revokedAtMs != null ||
            session.idleExpiresAtMs <= now ||
            session.absoluteExpiresAtMs <= now ||
            (!config.passwordEnabled && session.authMethod == AuthMethod.PASSWORD)
        ) {
            revoke(session.userId, session.id, now)
            return null
        }
        val user = db.users().get(session.userId)
            ?.takeIf { it.status == UserStatus.ACTIVE }
        if (user == null) {
            revoke(session.userId, session.id, now)
            return null
        }
        if (session.authMethod == AuthMethod.PASSWORD &&
            session.mfaSatisfiedAtMs == null &&
            effectiveRole(user) in config.mfaRequiredRoles
        ) {
            revoke(user.id, session.id, now)
            return null
        }
        if (now - session.lastSeenAtMs >= 60_000) {
            db.sessions().touch(
                session.id,
                now,
                minOf(now + config.sessionIdleMs, session.absoluteExpiresAtMs),
            )
        }
        return actor(user, session) to session
    }

    suspend fun csrf(actor: Actor): String =
        db.sessions().get(actor.authSessionId)
            ?.takeIf { it.userId == actor.userId && it.revokedAtMs == null }
            ?.csrfToken ?: throw UnauthenticatedApiException()

    suspend fun recentMfa(actor: Actor): AuthSessionRow =
        db.sessions().get(actor.authSessionId)
            ?.takeIf {
                val mfaAt = it.mfaSatisfiedAtMs
                it.userId == actor.userId &&
                    it.revokedAtMs == null &&
                    mfaAt != null &&
                    clock() - mfaAt <= 5 * 60_000L
            } ?: throw ForbiddenApiException()

    suspend fun revokeUser(userId: String, atMs: Long) {
        db.sessions().revokeUser(userId, atMs)
        cleanup.sessionRevoked(userId, null)
    }

    suspend fun revoke(userId: String, sessionId: String, atMs: Long) {
        db.sessions().revoke(sessionId, atMs)
        cleanup.sessionRevoked(userId, sessionId)
    }

    private fun actor(user: UserRow, session: AuthSessionRow) = Actor(
        userId = user.id,
        authSessionId = session.id,
        username = user.username,
        displayName = user.displayName,
        roles = setOf(UserRole.USER, effectiveRole(user)),
        authMethod = session.authMethod,
        clientKind = session.clientKind,
    )

    private fun effectiveRole(user: UserRow) =
        if (user.manualRole == UserRole.ADMIN || user.oidcAdmin) UserRole.ADMIN else UserRole.USER
}

/** Transport-neutral adapter from an opaque credential to the application actor. */
internal class RequestAuthenticator(
    private val sessions: PersistentSessionService,
) {
    suspend fun authenticate(rawToken: String?): Actor? = sessions.authenticate(rawToken)?.first
}
