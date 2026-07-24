package com.buco7854.opentv.server

import com.buco7854.opentv.core.storage.Storage
import com.buco7854.opentv.core.repo.XtreamRepository
import kotlinx.coroutines.channels.ReceiveChannel

data class PlaybackClient(val ip: String, val userAgent: String)

/** Actor-aware playback lease and watch-together application service. */
class SessionApplicationService(
    private val storage: Storage,
    private val sessions: PlaybackSessionRegistry,
    private val remux: RemuxService,
    private val cipher: StreamCipher,
    private val streamGate: StreamGate,
    private val connectionLimit: suspend (String) -> Int,
    private val auth: AuthService,
    private val content: ContentIdentityService,
    private val mediaGrants: PlaybackMediaGrants,
    private val xtream: XtreamRepository,
    private val downloads: DownloadManager,
    private val cleanup: UserStateCleanupCoordinator = NoopUserStateCleanupCoordinator,
) {
    suspend fun create(
        actor: Actor,
        client: PlaybackClient,
        request: PlaybackCreateRequest,
    ): PlaybackLeaseDto = cleanup.admitPlayback {
        auth.requireActiveActor(actor)
        createAdmitted(actor, client, request)
    }

    private suspend fun createAdmitted(
        actor: Actor,
        client: PlaybackClient,
        request: PlaybackCreateRequest,
    ): PlaybackLeaseDto {
        require(client.userAgent.length <= 2_048) { "User-Agent is too long" }
        require(client.ip.length <= 128) { "Client address is too long" }
        require(request.contentId.length in 1..128) { "Invalid content id" }
        require(request.downloadId == null || request.downloadId.length <= 128) {
            "Invalid download id"
        }
        require(request.mode in setOf("play", "catchup", "download")) { "Unknown playback mode" }
        val (identity, resolvedChannel) = content.resolve(request.contentId)
        val channel = resolvedChannel ?: if (request.mode == "download") null
            else throw ResourceNotFound("content", "Content is unavailable")
        if (!auth.hasPlaylistAccess(actor, identity.playlistId)) throw ResourceNotFound("content")
        val sourceUrl = if (request.mode == "catchup") {
            require(request.catchupStartMs != null && request.catchupDurationMs != null) {
                "Catch-up start and duration are required"
            }
            require(request.catchupDurationMs > 0) { "Catch-up duration must be positive" }
            xtream.catchupUrlFor(
                requireNotNull(channel),
                request.catchupStartMs,
                request.catchupStartMs + request.catchupDurationMs,
            ) ?: throw ResourceNotFound("catchup", "Catch-up is unavailable")
        } else if (request.mode == "download") {
            val downloadId = request.downloadId ?: throw IllegalArgumentException("Download id is required")
            val (blob, path) = downloads.fileFor(actor.userId, downloadId)
                ?: throw ResourceNotFound("download", "Download not finished")
            require(blob.contentId == request.contentId) { "Download content mismatch" }
            path.toString()
        } else requireNotNull(channel).url
        val lease = sessions.create(
            actor, identity.playlistId, identity.contentId, sourceUrl, client.ip, client.userAgent,
        )
        val grant = mediaGrants.issue(actor, lease.id)
        val source = cipher.encrypt(sourceUrl)
        val remote = sourceUrl.startsWith("http://") || sourceUrl.startsWith("https://")
        val remuxStart = if (remote) {
            mediaUrl("/api/v1/remux/start", source, lease.id, grant.token)
        } else {
            "/api/v1/remux/start?d=${urlEncode(requireNotNull(request.downloadId))}" +
                "&sid=${urlEncode(lease.id)}&g=${urlEncode(grant.token)}"
        }
        return PlaybackLeaseDto(
            id = lease.id,
            contentId = identity.contentId,
            playlistId = identity.playlistId,
            mediaGrant = grant.token,
            mediaGrantExpiresAtMs = grant.expiresAtMs,
            streamUrl = mediaUrl("/api/v1/stream", source, lease.id, grant.token).takeIf { remote },
            relayUrl = mediaUrl("/api/v1/relay", source, lease.id, grant.token).takeIf { remote },
            transcodeUrl = mediaUrl("/api/v1/transcode", source, lease.id, grant.token).takeIf { remote },
            remuxStartUrl = remuxStart,
        )
    }

    suspend fun refreshMediaGrant(actor: Actor, id: String): IssuedMediaGrant {
        validateLeaseActor(actor, id)
        return mediaGrants.issue(actor, id)
    }

    suspend fun active(actor: Actor): List<SessionDto> {
        requireAdmin(actor)
        return sessions.active().map { live ->
            val state = live.state
            val room = sessions.roomOf(live.id)
            SessionDto(
                id = live.id,
                userId = live.userId,
                username = live.username,
                displayName = live.displayName,
                clientKind = live.clientKind,
                ip = live.ip,
                userAgent = live.userAgent,
                playlistName = storage.playlists.get(live.playlistId)?.name,
                title = state.title,
                kind = state.kind,
                logo = state.logo,
                positionMs = state.positionMs,
                durationMs = state.durationMs,
                paused = state.paused,
                live = state.live,
                startedAtMs = live.startedAtMs,
                lastSeenMs = live.lastSeenMs,
                stream = SessionStreamDto(
                    state.engine,
                    state.direct,
                    state.audioTranscoded,
                    state.preparing,
                    state.remuxId?.let { remux.diagnostics(it) }?.toDto(),
                ),
                roomId = room?.first,
                roomSize = room?.second ?: 0,
            )
        }
    }

    suspend fun heartbeat(
        actor: Actor,
        client: PlaybackClient,
        request: SessionHeartbeatDto,
    ): HeartbeatResponseDto {
        validateHeartbeat(client, request)
        validateLeaseActor(actor, request.id)
        return HeartbeatResponseDto(
            sessions.heartbeat(actor, client.ip, client.userAgent, request),
        )
    }

    suspend fun watchIntent(actor: Actor, id: String): WatchIntentResponse {
        val self = sessions.owned(actor, id)
        val peers = sessions.sameContentPeers(id, self.contentId)
            .map { WatchIntentPeer(it.id, it.displayName) }
        val url = self.sourceUrl
        val limit = connectionLimit(url)
        val group = sessions.shareGroup(id)
        val full = url.startsWith("http") &&
            streamGate.streams(providerKeyOf(url), group) >= limit.coerceAtLeast(1)
        return WatchIntentResponse(peers, full, limit)
    }

    fun requestJoin(actor: Actor, id: String, request: JoinRequestBody) {
        val peer = sessions.owned(actor, id)
        if (sessions.requestJoin(request.peerId, id, actor.displayName, peer.contentId) == null) {
            throw ResourceNotFound("playback")
        }
    }

    fun answerJoin(actor: Actor, id: String, request: JoinAnswerBody) {
        sessions.owned(actor, id)
        if (!sessions.answerJoin(id, request.requestId, request.accept)) {
            throw ResourceNotFound("playback")
        }
    }

    fun sync(actor: Actor, id: String, state: SyncStateDto) {
        sessions.owned(actor, id)
        sessions.syncRoom(id, state)
    }

    fun kick(actor: Actor, id: String, request: KickBody) {
        sessions.owned(actor, id)
        if (!sessions.kick(id, request.targetId)) throw ResourceNotFound("room")
    }

    fun requestControl(actor: Actor, id: String, request: RequestControlBody) {
        sessions.owned(actor, id)
        if (request.requested && !sessions.requestControl(id, actor.displayName)) {
            throw ResourceNotFound("room", "Not in a room")
        }
    }

    fun grantControl(actor: Actor, id: String, request: GrantControlBody) {
        sessions.owned(actor, id)
        if (!sessions.grantControl(id, request.peerId, request.grant)) throw ResourceNotFound("room")
    }

    fun setControl(actor: Actor, id: String, request: SetControlBody) {
        sessions.owned(actor, id)
        if (!sessions.setControl(id, request.targetId, request.grant)) throw ResourceNotFound("room")
    }

    fun setRoomAudio(actor: Actor, id: String, request: RoomAudioBody) {
        sessions.owned(actor, id)
        if (!sessions.setRoomAudio(id, request.audioIndex.coerceAtLeast(0))) throw ResourceNotFound("room")
    }

    fun ready(actor: Actor, id: String) {
        sessions.owned(actor, id)
        sessions.markReady(id)
    }

    fun leave(actor: Actor, id: String) {
        sessions.owned(actor, id)
        sessions.leaveRoom(id)
    }

    fun command(actor: Actor, id: String, command: SessionCommandDto) {
        requireAdmin(actor)
        require(command.type in setOf("pause", "play", "message")) { "Unknown command" }
        require(command.text == null || command.text.length <= 1_000) { "Message is too long" }
        if (!sessions.command(id, command)) throw ResourceNotFound("playback")
    }

    fun resendRoomState(actor: Actor, id: String) {
        sessions.owned(actor, id)
        sessions.resendRoomState(id)
    }

    fun commands(actor: Actor, id: String): List<SessionCommandDto> {
        sessions.owned(actor, id)
        return sessions.drainCommands(id)
    }

    fun commandSignal(actor: Actor, id: String): ReceiveChannel<Unit> {
        sessions.owned(actor, id)
        return sessions.commandSignal(id)
    }

    private fun validateHeartbeat(client: PlaybackClient, request: SessionHeartbeatDto) {
        require(client.userAgent.length <= 2_048) { "User-Agent is too long" }
        require(client.ip.length <= 128) { "Client address is too long" }
        require(request.title.length <= 512) { "Playback title is too long" }
        require(request.logo == null || request.logo.length <= 8_192) { "Playback logo is too large" }
        require(request.positionMs >= 0 && request.durationMs >= 0) { "Playback times must be positive" }
        require(request.kind in setOf("live", "movie", "series", "catchup", "download")) {
            "Unknown playback kind"
        }
    }

    suspend fun update(actor: Actor, client: PlaybackClient, heartbeat: SessionHeartbeatDto) {
        validateHeartbeat(client, heartbeat)
        validateLeaseActor(actor, heartbeat.id)
        sessions.update(actor, client.ip, client.userAgent, heartbeat)
    }

    fun remove(actor: Actor, id: String) {
        sessions.owned(actor, id)
        sessions.remove(id)
    }

    fun adminRemove(actor: Actor, id: String) {
        requireAdmin(actor)
        sessions.remove(id)
    }

    private fun requireAdmin(actor: Actor) {
        if (!actor.isAdmin) throw ForbiddenApiException()
    }

    private suspend fun validateLeaseActor(actor: Actor, leaseId: String) {
        val lease = sessions.owned(actor, leaseId)
        try {
            auth.requireActiveActor(actor)
            if (!auth.hasPlaylistAccess(actor, lease.playlistId)) throw ForbiddenApiException()
        } catch (error: UnauthenticatedApiException) {
            sessions.remove(leaseId)
            throw error
        } catch (error: ForbiddenApiException) {
            sessions.remove(leaseId)
            throw error
        }
    }
}
