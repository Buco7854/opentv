package com.buco7854.opentv.server

import com.buco7854.opentv.core.storage.Storage
import kotlinx.coroutines.channels.ReceiveChannel

data class PlaybackClient(val ip: String, val userAgent: String)

/** Application use cases for playback presence and watch-together rooms. */
class SessionApplicationService(
    private val storage: Storage,
    private val sessions: PlaybackSessionRegistry,
    private val remux: RemuxService,
    private val cipher: StreamCipher,
    private val streamGate: StreamGate,
    private val connectionLimit: suspend (String) -> Int,
) {
    suspend fun active(): List<SessionDto> = sessions.active().map { live ->
        val state = live.state
        val room = sessions.roomOf(live.id)
        SessionDto(
            id = live.id,
            ip = live.ip,
            userAgent = live.userAgent,
            playlistName = state.playlistId?.let { storage.playlists.get(it)?.name },
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

    fun heartbeat(client: PlaybackClient, request: SessionHeartbeatDto): HeartbeatResponseDto =
        HeartbeatResponseDto(sessions.heartbeat(client.ip, client.userAgent, request))

    suspend fun watchIntent(request: WatchIntentRequest): WatchIntentResponse {
        val peers = sessions.sameContentPeers(request.selfId, request.contentKey)
            .map { WatchIntentPeer(it.id, it.state.name.ifBlank { "Someone" }) }
        val url = request.source?.let(cipher::tryDecrypt)
        val limit = url?.let { connectionLimit(it) } ?: Int.MAX_VALUE
        val group = sessions.shareGroup(request.selfId)
        val full = url != null &&
            streamGate.streams(providerKeyOf(url), group) >= limit.coerceAtLeast(1)
        return WatchIntentResponse(peers, full, limit)
    }

    fun requestJoin(hostId: String, request: JoinRequestBody) {
        if (!sessions.requestJoin(hostId, request.peerId, request.peerName, request.contentKey)) {
            throw ResourceNotFound("session")
        }
    }

    fun answerJoin(hostId: String, request: JoinAnswerBody) {
        if (!sessions.answerJoin(
                hostId,
                request.peerId,
                request.hostName,
                request.contentKey,
                request.accept,
            )
        ) {
            throw ResourceNotFound("session")
        }
    }

    fun sync(id: String, state: SyncStateDto) = sessions.syncRoom(id, state)

    fun kick(hostId: String, request: KickBody) {
        if (!sessions.kick(hostId, request.targetId)) throw ResourceNotFound("room")
    }

    fun requestControl(id: String, request: RequestControlBody) {
        if (!sessions.requestControl(id, request.peerName)) {
            throw ResourceNotFound("room", "Not in a room")
        }
    }

    fun grantControl(hostId: String, request: GrantControlBody) {
        if (!sessions.grantControl(hostId, request.peerId, request.grant)) {
            throw ResourceNotFound("room")
        }
    }

    fun setControl(hostId: String, request: SetControlBody) {
        if (!sessions.setControl(hostId, request.targetId, request.grant)) {
            throw ResourceNotFound("room")
        }
    }

    fun setRoomAudio(id: String, request: RoomAudioBody) {
        if (!sessions.setRoomAudio(id, request.audioIndex.coerceAtLeast(0))) {
            throw ResourceNotFound("room")
        }
    }

    fun ready(id: String) = sessions.markReady(id)
    fun leave(id: String) = sessions.leaveRoom(id)

    fun command(id: String, command: SessionCommandDto) {
        require(command.type in setOf("pause", "play", "message")) { "Unknown command" }
        if (!sessions.command(id, command)) throw ResourceNotFound("session")
    }

    fun resendRoomState(id: String) = sessions.resendRoomState(id)
    fun commands(id: String): List<SessionCommandDto> = sessions.drainCommands(id)
    fun commandSignal(id: String): ReceiveChannel<Unit> = sessions.commandSignal(id)
    fun update(client: PlaybackClient, heartbeat: SessionHeartbeatDto) =
        sessions.update(client.ip, client.userAgent, heartbeat)

    fun remove(id: String) {
        sessions.remove(id)
        streamGate.release(id)
    }
}
