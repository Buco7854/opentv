package com.buco7854.opentv.server

import com.buco7854.opentv.contract.*
import com.buco7854.opentv.core.model.Channel
import com.buco7854.opentv.core.model.ChannelKind
import com.buco7854.opentv.core.repo.GuideEntry
import com.buco7854.opentv.core.storage.Storage
import com.buco7854.opentv.core.repo.XtreamRepository
import kotlinx.coroutines.channels.ReceiveChannel

data class PlaybackClient(val ip: String, val userAgent: String)

private const val CATCHUP_DAY_MS = 24L * 60 * 60 * 1_000

internal suspend fun requireReplayableCatchup(
    channel: Channel,
    startMs: Long,
    durationMs: Long,
    nowMs: Long,
    loadGuide: suspend () -> List<GuideEntry>,
): Long {
    fun unavailable(): Nothing = throw ResourceNotFound("catchup", "Catch-up is unavailable")

    if (channel.catchupDays <= 0 || durationMs <= 0) unavailable()
    val endMs = try {
        Math.addExact(startMs, durationMs)
    } catch (_: ArithmeticException) {
        unavailable()
    }
    val windowStartMs = try {
        Math.subtractExact(nowMs, Math.multiplyExact(channel.catchupDays.toLong(), CATCHUP_DAY_MS))
    } catch (_: ArithmeticException) {
        unavailable()
    }
    if (startMs < windowStartMs || endMs > nowMs) unavailable()
    if (loadGuide().none { it.replayable && it.startMs == startMs && it.endMs == endMs }) unavailable()
    return endMs
}

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
    private val clock: () -> Long = System::currentTimeMillis,
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
        val requestedDownloadId = request.downloadId
        require(requestedDownloadId == null || requestedDownloadId.length <= 128) {
            "Invalid download id"
        }
        require(request.mode in setOf("play", "catchup", "download")) { "Unknown playback mode" }
        val clientInstanceId = request.clientInstanceId
        require(
            clientInstanceId == null ||
                clientInstanceId.length in 16..64 && clientInstanceId.all {
                    it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_'
                },
        ) { "Invalid playback client instance" }
        val (identity, resolvedChannel) = content.resolve(request.contentId)
        val channel = resolvedChannel ?: if (request.mode == "download") null
            else throw ResourceNotFound("content", "Content is unavailable")
        if (!auth.hasPlaylistAccess(actor, identity.playlistId)) throw ResourceNotFound("content")
        val sourceUrl = if (request.mode == "catchup") {
            val catchupStartMs = request.catchupStartMs
            val catchupDurationMs = request.catchupDurationMs
            require(catchupStartMs != null && catchupDurationMs != null) {
                "Catch-up start and duration are required"
            }
            val catchupEndMs = requireReplayableCatchup(
                channel = requireNotNull(channel),
                startMs = catchupStartMs,
                durationMs = catchupDurationMs,
                nowMs = clock(),
                loadGuide = { xtream.guideFor(channel) },
            )
            xtream.catchupUrlFor(
                channel,
                catchupStartMs,
                catchupEndMs,
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
            MediaCapabilities.from(request.capabilities),
            liveSource = request.mode == "play" && channel?.kind == ChannelKind.LIVE,
            clientInstanceId = clientInstanceId,
        )
        val grant = mediaGrants.issue(actor, lease.id)
        val remote = sourceUrl.startsWith("http://") || sourceUrl.startsWith("https://")
        val source = if (remote) cipher.encryptStream(sourceUrl, lease.id) else null
        val downloadFile = request.downloadId?.let {
            auth.requireActiveActor(actor)
            cipher.encryptDownloadFile(actor.userId, actor.authSessionId, it)
        }
        val remuxStart = source?.let { mediaUrl("/api/v1/remux/start", it, lease.id, grant.token) }
            ?: ("/api/v1/remux/start?d=${urlEncode(requireNotNull(request.downloadId))}" +
                "&sid=${urlEncode(lease.id)}&g=${urlEncode(grant.token)}")
        return PlaybackLeaseDto(
            id = lease.id,
            contentId = identity.contentId,
            playlistId = identity.playlistId,
            mediaGrant = grant.token,
            mediaGrantExpiresAtMs = grant.expiresAtMs,
            streamUrl = source?.let { mediaUrl("/api/v1/stream", it, lease.id, grant.token) },
            sharedHlsUrl = source
                ?.takeIf { it.startsWith("h.") }
                ?.let { mediaUrl("/api/v1/shared-hls", it, lease.id, grant.token) },
            relayUrl = source?.let { mediaUrl("/api/v1/relay", it, lease.id, grant.token) },
            transcodeUrl = source?.let { mediaUrl("/api/v1/transcode", it, lease.id, grant.token) },
            remuxStartUrl = remuxStart,
            downloadFileUrl = downloadFile?.let {
                "/api/v1/downloads/${urlEncode(requireNotNull(request.downloadId))}/file" +
                    "?token=${urlEncode(it.token)}"
            },
        )
    }

    suspend fun webSocketAccess(actor: Actor, id: String): WebSocketAccessDto {
        validateLeaseActor(actor, id)
        return cipher.encryptWebSocket(actor.authSessionId, id).let {
            WebSocketAccessDto(it.token, it.expiresAtMs)
        }
    }

    suspend fun refreshMediaGrant(actor: Actor, id: String): MediaGrantDto {
        validateLeaseActor(actor, id)
        return mediaGrants.issue(actor, id).let { MediaGrantDto(it.token, it.expiresAtMs) }
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
            .map { WatchIntentPeer(it.id, it.displayName, sameAccount = it.userId == self.userId) }
        val url = self.sourceUrl
        val limit = connectionLimit(url)
        val group = sessions.shareGroup(id)
        val full = url.startsWith("http") &&
            streamGate.streams(providerKeyOf(url), group) >= limit.coerceAtLeast(1)
        return WatchIntentResponse(
            sameContent = peers,
            full = full,
            limit = limit,
            requiresJoin = sessions.sameAccountConflict(id) != null,
        )
    }

    fun watchAlone(actor: Actor, id: String) {
        sessions.owned(actor, id)
        if (!sessions.watchAlone(id)) {
            throw SameContentAlreadyPlayingException()
        }
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

    fun ready(actor: Actor, id: String, request: ReadyBody) {
        sessions.owned(actor, id)
        require(request.generation > 0) { "Invalid room barrier generation" }
        sessions.markReady(id, request.generation)
    }

    fun leave(actor: Actor, id: String) {
        sessions.owned(actor, id)
        sessions.leaveRoom(id)
    }

    fun command(actor: Actor, id: String, command: SessionCommandDto) {
        requireAdmin(actor)
        require(command.type in setOf("pause", "play", "message")) { "Unknown command" }
        val text = command.text
        require(text == null || text.length <= 1_000) { "Message is too long" }
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
        val logo = request.logo
        require(logo == null || logo.length <= 8_192) { "Playback logo is too large" }
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
