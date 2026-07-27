package com.buco7854.opentv.server

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Coordinates runtime cleanup caused by persistent authentication and entitlement changes.
 *
 * Keeping these operations behind a named contract makes revocation semantics explicit and
 * prevents feature services from accumulating best-effort callback lists.
 */
interface UserStateCleanupCoordinator {
    suspend fun sessionRevoked(userId: String, authSessionId: String?)
    suspend fun playlistGrantRevoked(userId: String, playlistId: Long)
    suspend fun userDeleted(userId: String)
    suspend fun playlistDeleting(playlistId: Long)
    suspend fun <T> admitPlayback(block: suspend () -> T): T
}

object NoopUserStateCleanupCoordinator : UserStateCleanupCoordinator {
    override suspend fun sessionRevoked(userId: String, authSessionId: String?) = Unit
    override suspend fun playlistGrantRevoked(userId: String, playlistId: Long) = Unit
    override suspend fun userDeleted(userId: String) = Unit
    override suspend fun playlistDeleting(playlistId: Long) = Unit
    override suspend fun <T> admitPlayback(block: suspend () -> T): T = block()
}

class RuntimeUserStateCleanupCoordinator : UserStateCleanupCoordinator {
    private val admission = Mutex()
    @Volatile
    private var runtime: RuntimeCleanup? = null

    fun bind(sessions: PlaybackSessionRegistry, downloads: DownloadManager) {
        check(runtime == null) { "Runtime cleanup is already bound" }
        runtime = RuntimeCleanup(sessions, downloads)
    }

    override suspend fun sessionRevoked(userId: String, authSessionId: String?) =
        admission.withLock {
            val current = requireNotNull(runtime) { "Runtime cleanup has not been bound" }
            if (authSessionId == null) current.sessions.terminateUser(userId)
            else current.sessions.terminateSession(authSessionId)
        }

    override suspend fun playlistGrantRevoked(userId: String, playlistId: Long) =
        admission.withLock {
            val current = requireNotNull(runtime) { "Runtime cleanup has not been bound" }
            current.sessions.terminatePlaylist(userId, playlistId)
            current.downloads.scheduleGrantRevocation(playlistId)
        }

    override suspend fun userDeleted(userId: String) = admission.withLock {
        val current = requireNotNull(runtime) { "Runtime cleanup has not been bound" }
        current.sessions.terminateUser(userId)
        current.downloads.scheduleOrphanCleanup()
    }

    override suspend fun playlistDeleting(playlistId: Long) = admission.withLock {
        val current = requireNotNull(runtime) { "Runtime cleanup has not been bound" }
        current.sessions.terminatePlaylist(playlistId)
    }

    override suspend fun <T> admitPlayback(block: suspend () -> T): T =
        admission.withLock { block() }

    private data class RuntimeCleanup(
        val sessions: PlaybackSessionRegistry,
        val downloads: DownloadManager,
    )
}
