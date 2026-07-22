package com.buco7854.opentv.server

import com.buco7854.opentv.core.util.nowMs
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/** What a player reports about its current playback (client -> server). */
@Serializable
data class SessionHeartbeatDto(
    /** Stable per browser tab; identifies the session across heartbeats. */
    val id: String,
    val playlistId: Long? = null,
    val title: String = "",
    /** "live" | "movie" | "series" | "catchup" | "download". */
    val kind: String = "live",
    /** Encrypted logo token (as held by the client), for the card thumbnail. */
    val logo: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val paused: Boolean = false,
    val live: Boolean = false,
    /** Playback engine the client picked: "hls" | "mpegts" | "native" | "remux". */
    val engine: String = "native",
    /** Playing the server's own output (remux/download): the stream proxy is bypassed. */
    val direct: Boolean = false,
    /** Live audio the browser couldn't decode, rescued to AAC by the server. */
    val audioTranscoded: Boolean = false,
    /** ffmpeg is still probing the file; the copy-vs-transcode choice isn't made yet. */
    val preparing: Boolean = false,
    /** Set when engine == "remux"; joins to server-side ffmpeg diagnostics. */
    val remuxId: String? = null,
    /** Stable identity of the content being watched (channel/download/catch-up), so the
     *  server can tell two viewers apart from two viewers of the same thing. */
    val contentKey: String = "",
    /** Friendly device label ("Chrome · Windows"), shown in the watch-together roster. */
    val name: String = "",
)

/** Driver playback state, mirrored to the other members of a watch-together room.
 *  [seek] marks a deliberate jump (apply exactly) vs. a periodic anchor (only fix big drift). */
@Serializable
data class SyncStateDto(val positionMs: Long, val paused: Boolean, val rate: Double = 1.0, val seek: Boolean = false)

/** One viewer in a watch-together room (server -> members). */
@Serializable
data class RoomMemberDto(val id: String, val name: String, val host: Boolean, val controller: Boolean)

/**
 * A command pushed to a viewer. The admin queues pause/play/message; the rest coordinate
 * watch-together rooms between viewers over the same channel.
 */
@Serializable
data class SessionCommandDto(
    /** pause | play | message (admin) · join-request | join-response | control-request |
     *  control-response | sync | room-state (roster changed) | room-ended (dropped/kicked) |
     *  room-audio (a controller changed the shared audio track) | room-go (all reloaded, resume). */
    val type: String,
    val text: String? = null,
    /** join/control-request: the other viewer's session id and display name. */
    val peerId: String? = null,
    val peerName: String? = null,
    /** join/control-response: whether it was accepted. */
    val accepted: Boolean? = null,
    /** join-request: the host already declined this peer once, so nudge quietly. */
    val quiet: Boolean = false,
    /** sync: the driver's latest playback state. */
    val sync: SyncStateDto? = null,
    /** room-state: the full roster, so each client can render who's in and their rights. */
    val members: List<RoomMemberDto>? = null,
    /** room-audio: the shared audio-track index every member should re-request the remux with. */
    val audioIndex: Int? = null,
)

@Serializable
data class HeartbeatResponseDto(val commands: List<SessionCommandDto> = emptyList())

/** ffmpeg pipeline facts for a remux session (server -> admin); labels are formatted client-side. */
@Serializable
data class RemuxDiagDto(
    val videoCodec: String,
    val transcodeVideo: Boolean,
    val videoEncoder: String,
    val nativeVideoCopy: Boolean,
    val audioCodec: String,
    val audioChannels: Int? = null,
    val audioLabel: String? = null,
    val subtitleCount: Int,
    val segmentCount: Int,
    val timeshift: Boolean,
    val providerKey: String,
    val connectionLimit: Int,
    val ffmpegRunning: Boolean,
    val durationSec: Double? = null,
    val lastLog: String? = null,
)

@Serializable
data class SessionStreamDto(
    val engine: String,
    val direct: Boolean,
    val audioTranscoded: Boolean,
    val preparing: Boolean,
    val remux: RemuxDiagDto? = null,
)

/** One active viewer (server -> admin dashboard). */
@Serializable
data class SessionDto(
    val id: String,
    val ip: String,
    val userAgent: String,
    val playlistName: String? = null,
    val title: String,
    val kind: String,
    val logo: String? = null,
    val positionMs: Long,
    val durationMs: Long,
    val paused: Boolean,
    val live: Boolean,
    val startedAtMs: Long,
    val lastSeenMs: Long,
    val stream: SessionStreamDto,
    /** Set when this viewer is in a watch-together room; [roomSize] counts its members. */
    val roomId: String? = null,
    val roomSize: Int = 0,
)

/**
 * In-memory registry of active web-client playback sessions. A player heartbeats
 * every few seconds; sessions with no recent heartbeat are dropped. Commands the
 * admin enqueues are delivered on the session's next heartbeat.
 *
 * Web sessions only: the Android app plays through the shared core layer, not this
 * server, so it never appears here.
 */
class PlaybackSessionRegistry {

    class Live(
        val id: String,
        @Volatile var ip: String,
        @Volatile var userAgent: String,
        @Volatile var state: SessionHeartbeatDto,
        val startedAtMs: Long,
        @Volatile var lastSeenMs: Long,
        val commands: ConcurrentLinkedQueue<SessionCommandDto> = ConcurrentLinkedQueue(),
    )

    /** A watch-together room. The host owns it and can grant playback control to guests;
     *  everyone in [controllers] (the host plus whoever it allowed) can drive, the rest mirror. */
    private class Room(val id: String, @Volatile var hostId: String) {
        val members: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        val controllers: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        // The room shares one remux read, so one audio track: whichever a controller last chose.
        @Volatile var audioIndex: Int = 0
        // Members that have finished reloading after a track change; when it covers everyone the
        // room resumes together, so no one plays ahead while another is still buffering the switch.
        val ready: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    }

    private val sessions = ConcurrentHashMap<String, Live>()
    private val rooms = ConcurrentHashMap<String, Room>()
    private val memberRoom = ConcurrentHashMap<String, String>()
    // A host that declined a peer for some content isn't pestered with a modal again for it.
    private val declined = ConcurrentHashMap<String, MutableSet<String>>()
    private fun declineKey(peerId: String, contentKey: String) = "$peerId@$contentKey"
    // Signals a session's WebSocket to drain immediately; heartbeat draining is the fallback.
    private val wakes = ConcurrentHashMap<String, Channel<Unit>>()
    private fun wake(id: String) = wakes.computeIfAbsent(id) { Channel(Channel.CONFLATED) }

    /** Upsert session state from a heartbeat. Commands are drained separately - the HTTP
     *  heartbeat returns them, the WebSocket pushes them as they're queued. */
    fun update(ip: String, userAgent: String, dto: SessionHeartbeatDto) {
        val now = nowMs()
        sessions.compute(dto.id) { _, existing ->
            existing?.apply { this.ip = ip; this.userAgent = userAgent; state = dto; lastSeenMs = now }
                ?: Live(dto.id, ip, userAgent, dto, now, now)
        }
    }

    /** Upsert from a heartbeat and drain any commands queued for this session (HTTP fallback). */
    fun heartbeat(ip: String, userAgent: String, dto: SessionHeartbeatDto): List<SessionCommandDto> {
        update(ip, userAgent, dto)
        return drainCommands(dto.id)
    }

    /** Queue a command for [id]; false when no such live session. */
    fun enqueue(id: String, command: SessionCommandDto): Boolean {
        val live = sessions[id] ?: return false
        live.commands.add(command)
        wake(id).trySend(Unit)
        return true
    }

    /** Deliver an admin command. A pause/play aimed at someone in a watch-together room drives
     *  the whole room, whatever the target's role - the operator's action then can't be undone
     *  by another member's next sync. A message still goes to just that viewer. */
    fun command(id: String, command: SessionCommandDto): Boolean {
        if (command.type == "pause" || command.type == "play") {
            val room = memberRoom[id]?.let { rooms[it] }
            if (room != null) {
                room.members.forEach { enqueue(it, command) }
                return true
            }
        }
        return enqueue(id, command)
    }

    /** Other live viewers watching the same [contentKey] as [selfId] - watch-together candidates. */
    fun sameContentPeers(selfId: String, contentKey: String): List<Live> {
        if (contentKey.isBlank()) return emptyList()
        return active().filter { it.id != selfId && it.state.contentKey == contentKey }
    }

    /** Ask [hostId] to admit [peerId] into a watch-together room; false when the host is gone. */
    fun requestJoin(hostId: String, peerId: String, peerName: String, contentKey: String): Boolean {
        if (hostId == peerId || !sessions.containsKey(hostId)) return false
        val quiet = declined[hostId]?.contains(declineKey(peerId, contentKey)) == true
        return enqueue(hostId, SessionCommandDto(
            type = "join-request", peerId = peerId, peerName = peerName, quiet = quiet,
        ))
    }

    /** The host's answer to a join request. On accept both share a room; on decline it's
     *  remembered so the same peer can't pop another modal for the same content. */
    fun answerJoin(hostId: String, peerId: String, hostName: String, contentKey: String, accept: Boolean): Boolean {
        if (!sessions.containsKey(peerId)) return false
        if (!accept) {
            declined.computeIfAbsent(hostId) { java.util.concurrent.ConcurrentHashMap.newKeySet() }
                .add(declineKey(peerId, contentKey))
            return enqueue(peerId, SessionCommandDto(type = "join-response", accepted = false))
        }
        declined[hostId]?.remove(declineKey(peerId, contentKey))
        val room = memberRoom[hostId]?.let { rooms[it] } ?: Room("r-$hostId", hostId).also {
            it.members.add(hostId)
            it.controllers.add(hostId)
            rooms[it.id] = it
            memberRoom[hostId] = it.id
        }
        room.members.add(peerId)
        memberRoom[peerId] = room.id
        enqueue(peerId, SessionCommandDto(type = "join-response", accepted = true))
        pushRoomState(room)
        return true
    }

    /** A guest asks the room's host to let it control playback too. */
    fun requestControl(fromId: String, fromName: String): Boolean {
        val room = memberRoom[fromId]?.let { rooms[it] } ?: return false
        if (fromId in room.controllers) return true
        return enqueue(room.hostId, SessionCommandDto(
            type = "control-request", peerId = fromId, peerName = fromName,
        ))
    }

    /** The host's answer to a control request. Only the host may grant; on grant the guest
     *  joins [controllers] and can drive playback alongside everyone else already allowed. */
    fun grantControl(hostId: String, peerId: String, grant: Boolean): Boolean {
        val room = memberRoom[hostId]?.let { rooms[it] } ?: return false
        if (room.hostId != hostId || peerId !in room.members) return false
        if (grant) { room.controllers.add(peerId); pushRoomState(room) }
        return enqueue(peerId, SessionCommandDto(type = "control-response", accepted = grant))
    }

    /** The host hands a member control (or takes it back) directly, no request needed. */
    fun setControl(hostId: String, targetId: String, grant: Boolean): Boolean {
        val room = memberRoom[hostId]?.let { rooms[it] } ?: return false
        if (room.hostId != hostId || targetId == hostId || targetId !in room.members) return false
        if (grant) room.controllers.add(targetId) else room.controllers.remove(targetId)
        pushRoomState(room)
        enqueue(targetId, SessionCommandDto(type = "control-response", accepted = grant))
        return true
    }

    /** The host removes [targetId] from the room. */
    fun kick(hostId: String, targetId: String): Boolean {
        val room = memberRoom[hostId]?.let { rooms[it] } ?: return false
        if (room.hostId != hostId || targetId == hostId || targetId !in room.members) return false
        enqueue(targetId, SessionCommandDto(type = "room-ended"))
        removeFromRoom(room, targetId)
        return true
    }

    /** The room [id] is in and how many are in it, for the activity dashboard. Null if none. */
    fun roomOf(id: String): Pair<String, Int>? {
        val roomId = memberRoom[id] ?: return null
        val room = rooms[roomId] ?: return null
        return roomId to room.members.size
    }

    /** The share group that owns [id]'s provider connection: its room when in one (so the whole
     *  room reads the file once), otherwise itself (a lone viewer with its own read/seat). */
    fun shareGroup(id: String): String = memberRoom[id] ?: id

    /** Every member of [id]'s room (so a read forming the room can free their solo seats),
     *  or empty when [id] is watching alone. */
    fun roomMembers(id: String): Set<String> =
        memberRoom[id]?.let { rooms[it]?.members?.toSet() } ?: emptySet()

    /** The audio track a room member must remux with, so everyone shares one read. Null when solo. */
    fun roomAudio(id: String): Int? = memberRoom[id]?.let { rooms[it]?.audioIndex }

    /** A controller picks the room's shared audio track; every member re-requests the remux with
     *  it, so the room stays on one provider connection. Ignored from a non-controller. */
    fun setRoomAudio(fromId: String, index: Int): Boolean {
        val room = memberRoom[fromId]?.let { rooms[it] } ?: return false
        if (fromId !in room.controllers) return false
        room.audioIndex = index
        // Start a reload barrier: everyone switches, and nobody resumes until all have reported in.
        room.ready.clear()
        room.members.forEach { enqueue(it, SessionCommandDto(type = "room-audio", audioIndex = index)) }
        return true
    }

    /** A member finished reloading the shared track; once every member has, release the room to
     *  play again in step. Best-effort - a client also fails open on its own timeout. */
    fun markReady(sid: String): Boolean {
        val room = memberRoom[sid]?.let { rooms[it] } ?: return false
        room.ready.add(sid)
        if (room.ready.containsAll(room.members)) {
            room.ready.clear()
            room.members.forEach { enqueue(it, SessionCommandDto(type = "room-go")) }
        }
        return true
    }

    private fun roster(room: Room): List<RoomMemberDto> = room.members.map { id ->
        RoomMemberDto(
            id = id,
            name = sessions[id]?.state?.name?.takeIf { it.isNotBlank() } ?: "Someone",
            host = id == room.hostId,
            controller = id in room.controllers,
        )
    }

    /** Push the current roster to every member, so each renders who's in and their rights. */
    private fun pushRoomState(room: Room) {
        val members = roster(room)
        room.members.forEach { enqueue(it, SessionCommandDto(type = "room-state", members = members)) }
    }

    /** Mirror a controller's [state] to the room's other members (non-controllers can't drive). */
    fun syncRoom(fromId: String, state: SyncStateDto) {
        val room = memberRoom[fromId]?.let { rooms[it] } ?: return
        if (fromId !in room.controllers) return
        room.members.filter { it != fromId }.forEach { member ->
            // Keep only the freshest sync queued, so a brief socket outage can't back them up.
            sessions[member]?.commands?.removeIf { it.type == "sync" }
            enqueue(member, SessionCommandDto(type = "sync", sync = state))
        }
    }

    /** Take [id] out of its room, dissolving it when only one lone member would be left,
     *  promoting a new host if the host left, and re-broadcasting the roster otherwise. */
    fun leaveRoom(id: String) {
        val room = memberRoom[id]?.let { rooms[it] } ?: return
        removeFromRoom(room, id)
    }

    // The room lives as long as anyone is in it - even a lone host, who can then admit someone
    // back - and only dissolves once empty. A departing host hands off to whoever remains.
    private fun removeFromRoom(room: Room, id: String) {
        memberRoom.remove(id)
        room.members.remove(id)
        room.controllers.remove(id)
        if (room.members.isEmpty()) { rooms.remove(room.id); return }
        if (room.hostId == id) {
            room.hostId = room.members.first()
            room.controllers.add(room.hostId)
        }
        pushRoomState(room)
    }

    /** Fires whenever a command is queued for [id]; the WebSocket drains on each signal. */
    fun commandSignal(id: String): ReceiveChannel<Unit> = wake(id)

    fun drainCommands(id: String): List<SessionCommandDto> {
        val live = sessions[id] ?: return emptyList()
        val out = ArrayList<SessionCommandDto>()
        while (true) out.add(live.commands.poll() ?: break)
        return out
    }

    fun remove(id: String) {
        leaveRoom(id)
        declined.remove(id)
        sessions.remove(id)
        wakes.remove(id)?.close()
    }

    /** Live sessions (stale ones pruned first), newest first. */
    fun active(): List<Live> {
        val cutoff = nowMs() - STALE_MS
        val stale = sessions.values.filter { it.lastSeenMs < cutoff }.map { it.id }
        stale.forEach { remove(it) }
        return sessions.values.sortedByDescending { it.startedAtMs }
    }

    companion object {
        /** Drop a session this long after its last heartbeat (client beats ~every 3s). */
        private const val STALE_MS = 12_000L
    }
}
