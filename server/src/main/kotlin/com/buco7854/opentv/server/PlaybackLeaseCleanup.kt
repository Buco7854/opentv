package com.buco7854.opentv.server

interface PlaybackLeaseCleanup {
    fun memberLeaving(leaseId: String)
    fun shareGroupUnused(group: String)
    fun leaseTerminated(leaseId: String, unusedShareGroup: String?)
}

object NoopPlaybackLeaseCleanup : PlaybackLeaseCleanup {
    override fun memberLeaving(leaseId: String) = Unit
    override fun shareGroupUnused(group: String) = Unit
    override fun leaseTerminated(leaseId: String, unusedShareGroup: String?) = Unit
}

class RuntimePlaybackLeaseCleanup : PlaybackLeaseCleanup {
    @Volatile
    private var runtime: RuntimeCleanup? = null

    fun bind(
        mediaGrants: PlaybackMediaGrants,
        proxy: StreamProxy,
        liveRelay: LiveRelay,
        transcoder: AudioTranscoder,
        streamGate: StreamGate,
        remux: RemuxService,
    ) {
        check(runtime == null) { "Playback cleanup is already bound" }
        runtime = RuntimeCleanup(mediaGrants, proxy, liveRelay, transcoder, streamGate, remux)
    }

    override fun memberLeaving(leaseId: String) {
        val current = requireNotNull(runtime) { "Playback cleanup has not been bound" }
        current.mediaGrants.detachResources(leaseId).forEach(current.remux::stop)
        current.dropTransports(leaseId)
    }

    override fun shareGroupUnused(group: String) {
        val current = requireNotNull(runtime) { "Playback cleanup has not been bound" }
        current.proxy.dropShareGroup(group)
        current.streamGate.release(group)
        current.remux.stopGroup(group)
    }

    override fun leaseTerminated(leaseId: String, unusedShareGroup: String?) {
        val current = requireNotNull(runtime) { "Playback cleanup has not been bound" }
        current.mediaGrants.revokeLease(leaseId).forEach(current.remux::stop)
        current.dropTransports(leaseId)
        unusedShareGroup?.let(::shareGroupUnused)
    }

    private fun RuntimeCleanup.dropTransports(leaseId: String) {
        proxy.drop(leaseId)
        liveRelay.drop(leaseId)
        transcoder.drop(leaseId)
        streamGate.release(leaseId)
        streamGate.release(transcodeGateId(leaseId))
    }

    private data class RuntimeCleanup(
        val mediaGrants: PlaybackMediaGrants,
        val proxy: StreamProxy,
        val liveRelay: LiveRelay,
        val transcoder: AudioTranscoder,
        val streamGate: StreamGate,
        val remux: RemuxService,
    )
}
