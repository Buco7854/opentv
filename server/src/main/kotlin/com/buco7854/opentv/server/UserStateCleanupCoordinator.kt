package com.buco7854.opentv.server

/**
 * Coordinates runtime cleanup caused by persistent authentication and entitlement changes.
 *
 * Keeping these operations behind a named contract makes revocation semantics explicit and
 * prevents feature services from accumulating best-effort callback lists.
 */
interface UserStateCleanupCoordinator {
    fun sessionRevoked(userId: String, authSessionId: String?)
    fun playlistGrantRevoked(userId: String, playlistId: Long)
    fun userDeleted(userId: String)
    fun playlistDeleting(playlistId: Long)
}

object NoopUserStateCleanupCoordinator : UserStateCleanupCoordinator {
    override fun sessionRevoked(userId: String, authSessionId: String?) = Unit
    override fun playlistGrantRevoked(userId: String, playlistId: Long) = Unit
    override fun userDeleted(userId: String) = Unit
    override fun playlistDeleting(playlistId: Long) = Unit
}

class RuntimeUserStateCleanupCoordinator : UserStateCleanupCoordinator {
    @Volatile
    private var runtime: RuntimeCleanup? = null

    fun bind(sessions: PlaybackSessionRegistry, downloads: DownloadManager) {
        check(runtime == null) { "Runtime cleanup is already bound" }
        runtime = RuntimeCleanup(sessions, downloads)
    }

    override fun sessionRevoked(userId: String, authSessionId: String?) {
        val current = requireNotNull(runtime) { "Runtime cleanup has not been bound" }
        if (authSessionId == null) current.sessions.terminateUser(userId)
        else current.sessions.terminateSession(authSessionId)
    }

    override fun playlistGrantRevoked(userId: String, playlistId: Long) {
        val current = requireNotNull(runtime) { "Runtime cleanup has not been bound" }
        current.sessions.terminatePlaylist(userId, playlistId)
        current.downloads.scheduleGrantRevocation(playlistId)
    }

    override fun userDeleted(userId: String) {
        val current = requireNotNull(runtime) { "Runtime cleanup has not been bound" }
        current.sessions.terminateUser(userId)
        current.downloads.scheduleOrphanCleanup()
    }

    override fun playlistDeleting(playlistId: Long) {
        val current = requireNotNull(runtime) { "Runtime cleanup has not been bound" }
        current.sessions.terminatePlaylist(playlistId)
    }

    private data class RuntimeCleanup(
        val sessions: PlaybackSessionRegistry,
        val downloads: DownloadManager,
    )
}
