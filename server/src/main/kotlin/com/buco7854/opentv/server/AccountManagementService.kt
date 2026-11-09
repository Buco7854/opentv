package com.buco7854.opentv.server

import com.buco7854.opentv.contract.AccountProgressDto
import com.buco7854.opentv.contract.AuthSessionDto
import com.buco7854.opentv.serverdata.db.OpenTvServerDatabase

/** Account-owned management deliberately does not depend on current catalog entitlements. */
internal class AccountManagementService(
    private val db: OpenTvServerDatabase,
    private val sessions: PersistentSessionService,
    private val titles: suspend (Collection<String>) -> Map<String, String>,
    private val clock: () -> Long,
) {
    suspend fun progress(actor: Actor): List<AccountProgressDto> {
        sessions.requireActive(actor)
        val rows = db.activity().resumeForUser(actor.userId)
        val resolved = titles(rows.map { it.contentId })
        return rows.map {
            AccountProgressDto(it.contentId, resolved[it.contentId], it.positionMs, it.durationMs, it.updatedAtMs)
        }
    }

    suspend fun deleteProgress(actor: Actor, contentId: String) {
        sessions.requireActive(actor)
        db.activity().deleteResume(actor.userId, contentId)
    }

    suspend fun clearProgress(actor: Actor) {
        sessions.requireActive(actor)
        db.activity().clearResumeForUser(actor.userId)
    }

    suspend fun sessions(actor: Actor): List<AuthSessionDto> {
        sessions.requireActive(actor)
        val now = clock()
        return db.sessions().activeForUser(actor.userId)
            .filter { it.idleExpiresAtMs > now && it.absoluteExpiresAtMs > now }
            .map {
                AuthSessionDto(
                    it.id, it.authMethod, it.clientKind, it.deviceName, it.createdAtMs,
                    it.lastSeenAtMs, it.idleExpiresAtMs, it.absoluteExpiresAtMs,
                )
            }
    }

    suspend fun revokeSession(actor: Actor, sessionId: String) {
        sessions.requireActive(actor)
        val row = db.sessions().get(sessionId)?.takeIf { it.userId == actor.userId }
            ?: throw ResourceNotFound("session")
        sessions.revoke(actor.userId, row.id, clock())
    }

    suspend fun revokeOtherSessions(actor: Actor) {
        sessions.requireActive(actor)
        sessions.revokeOthers(actor.userId, actor.authSessionId, clock())
    }
}
