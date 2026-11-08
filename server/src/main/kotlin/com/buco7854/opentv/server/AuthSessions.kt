package com.buco7854.opentv.server

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import com.buco7854.opentv.serverdata.AuthMethod
import com.buco7854.opentv.serverdata.ClientKind
import com.buco7854.opentv.serverdata.UserRole
import com.buco7854.opentv.serverdata.UserStatus
import com.buco7854.opentv.serverdata.db.AuthSessionRow
import com.buco7854.opentv.serverdata.db.OpenTvServerDatabase
import com.buco7854.opentv.serverdata.db.UserRow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/** Persistent, revocable session storage independent from HTTP delivery. */
internal class PersistentSessionService(
    private val db: OpenTvServerDatabase,
    private val config: AuthConfig,
    private val cleanup: UserStateCleanupCoordinator,
    private val clock: () -> Long,
) {
    suspend fun issue(
        user: UserRow,
        method: String,
        mfa: Boolean,
        clientKind: String = ClientKind.BROWSER,
    ): IssuedSession {
        val issued = prepare(user, method, mfa, clientKind = clientKind)
        db.sessions().insert(issued.row)
        return issued
    }

    suspend fun prepare(
        user: UserRow,
        method: String,
        mfa: Boolean,
        deviceName: String? = null,
        clientKind: String = ClientKind.BROWSER,
    ): IssuedSession {
        val now = clock()
        val token = AuthCrypto.token()
        val row = AuthSessionRow(
            id = UUID.randomUUID().toString(),
            userId = user.id,
            tokenHash = AuthCrypto.hashToken(token),
            csrfToken = "",
            authMethod = method,
            clientKind = clientKind,
            tokenFamilyId = UUID.randomUUID().toString(),
            credentialVersion = db.credentials().password(user.id)?.version ?: 0,
            deviceId = null,
            deviceName = deviceName,
            mfaSatisfiedAtMs = now.takeIf { mfa },
            createdAtMs = now,
            lastSeenAtMs = now,
            idleExpiresAtMs = now + config.sessionIdleMs,
            absoluteExpiresAtMs = now + config.sessionAbsoluteMs,
            revokedAtMs = null,
        )
        return IssuedSession(token, row, user)
    }

    suspend fun authenticate(rawToken: String?, renew: Boolean = true): Pair<Actor, AuthSessionRow>? {
        if (rawToken.isNullOrBlank() || rawToken.length > 512) return null
        val session = db.sessions().byTokenHash(AuthCrypto.hashToken(rawToken)) ?: return null
        return authenticate(session, renew)
    }

    suspend fun authenticateSession(sessionId: String, renew: Boolean = true): Pair<Actor, AuthSessionRow>? {
        if (sessionId.isBlank() || sessionId.length > 128) return null
        val session = db.sessions().get(sessionId) ?: return null
        return authenticate(session, renew)
    }

    private suspend fun authenticate(session: AuthSessionRow, renew: Boolean): Pair<Actor, AuthSessionRow>? {
        val now = clock()
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
        if (renew && needsRenewal(session)) renew(session)
        return actor(user, session) to session
    }

    fun needsRenewal(session: AuthSessionRow): Boolean = clock() - session.lastSeenAtMs >= 60_000

    suspend fun renew(session: AuthSessionRow) {
        db.useWriterConnection { connection ->
            connection.immediateTransaction<Unit> {
                // A queued renewal may acquire the writer after expiry or revocation. Recheck
                // the persistent row and the clock here so it can never resurrect that session.
                val current = db.sessions().get(session.id) ?: return@immediateTransaction
                val now = clock()
                if (current.revokedAtMs == null && current.idleExpiresAtMs > now &&
                    current.absoluteExpiresAtMs > now && needsRenewal(current)
                ) {
                    db.sessions().touch(
                        current.id, now,
                        minOf(now + config.sessionIdleMs, current.absoluteExpiresAtMs),
                    )
                }
            }
        }
    }

    suspend fun recentlyAuthenticated(actor: Actor): AuthSessionRow =
        db.sessions().get(actor.authSessionId)
            ?.takeIf {
                it.userId == actor.userId &&
                    it.revokedAtMs == null &&
                    clock() - it.createdAtMs <= RECENT_AUTHENTICATION_MS
            } ?: throw ForbiddenApiException()

    suspend fun requireMfaSatisfied(actor: Actor): AuthSessionRow {
        requireActive(actor)
        return db.sessions().get(actor.authSessionId)
            ?.takeIf {
                it.userId == actor.userId &&
                    it.revokedAtMs == null &&
                    it.mfaSatisfiedAtMs != null
            } ?: throw ForbiddenApiException()
    }

    suspend fun requireActive(actor: Actor) {
        val now = clock()
        val session = db.sessions().get(actor.authSessionId)
            ?.takeIf {
                it.userId == actor.userId &&
                    it.revokedAtMs == null &&
                    it.idleExpiresAtMs > now &&
                    it.absoluteExpiresAtMs > now
            } ?: throw UnauthenticatedApiException()
        val user = db.users().get(actor.userId)
            ?.takeIf { it.status == UserStatus.ACTIVE }
            ?: throw UnauthenticatedApiException()
        if (session.authMethod == AuthMethod.PASSWORD &&
            session.mfaSatisfiedAtMs == null &&
            effectiveRole(user) in config.mfaRequiredRoles
        ) {
            throw UnauthenticatedApiException()
        }
    }

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

    private companion object {
        const val RECENT_AUTHENTICATION_MS = 5 * 60_000L
    }
}

/** Transport-neutral adapter from an opaque credential to the application actor. */
internal class RequestAuthenticator(
    private val sessions: PersistentSessionService,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : AutoCloseable {
    private val renewingSessions = ConcurrentHashMap.newKeySet<String>()
    private val log = LoggerFactory.getLogger("opentv.auth")

    suspend fun authenticate(rawToken: String?): Actor? =
        sessions.authenticate(rawToken, renew = false)?.also { renewAfterRequest(it.second) }?.first

    suspend fun authenticateSession(sessionId: String): Actor? =
        sessions.authenticateSession(sessionId, renew = false)?.also { renewAfterRequest(it.second) }?.first

    private fun renewAfterRequest(session: AuthSessionRow) {
        if (!sessions.needsRenewal(session) || !renewingSessions.add(session.id)) return
        // Catalog and guide refreshes share this database's one writer. Authentication reads
        // current session/user authority on every request; last-seen maintenance must not put
        // a returning viewer's favorites, movies and series behind a busy writer. Keep at most
        // one renewal queued per session, owned and cancelled by the server runtime.
        scope.launch {
            try {
                sessions.renew(session)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                log.warn("Session activity renewal deferred ({})", failure.javaClass.simpleName)
            } finally {
                renewingSessions.remove(session.id)
            }
        }
    }

    override fun close() = scope.cancel()
}
