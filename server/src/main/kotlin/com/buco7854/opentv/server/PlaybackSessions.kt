package com.buco7854.opentv.server

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.UUID

/**
 * In-memory registry of active web-client playback sessions. A player heartbeats
 * every few seconds; sessions with no recent heartbeat are dropped. Commands the
 * admin enqueues are delivered on the session's next heartbeat.
 *
 * Web sessions only: the Android app plays through the shared core layer, not this
 * server, so it never appears here.
 */
class PlaybackSessionRegistry(
    private val clock: ServerClock = ServerClock.SYSTEM,
    private val staleMs: Long = DEFAULT_STALE_MS,
    private val cleanup: PlaybackLeaseCleanup = NoopPlaybackLeaseCleanup,
) : AutoCloseable {
    private val reaperScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        reaperScope.launch {
            while (isActive) {
                delay(staleMs.coerceAtLeast(1_000L))
                active()
            }
        }
    }

    class Live(
        val id: String,
        val userId: String,
        val authSessionId: String,
        val username: String,
        val displayName: String,
        val clientKind: String,
        val playlistId: Long,
        val contentId: String,
        internal val sourceUrl: String,
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
        // Members whose browser can't decode HEVC. While any are present the shared read must
        // transcode to H.264; empty means everyone can decode it and it's copied.
        val noHevc: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    }

    private val sessions = ConcurrentHashMap<String, Live>()
    private val rooms = ConcurrentHashMap<String, Room>()
    private val memberRoom = ConcurrentHashMap<String, String>()
    // A host that declined a peer for some content isn't pestered with a modal again for it.
    private val declined = ConcurrentHashMap<String, MutableSet<String>>()
    private fun declineKey(peerId: String, contentKey: String) = "$peerId@$contentKey"
    private data class PendingJoin(
        val id: String,
        val requesterId: String,
        val targetId: String,
        val contentId: String,
        val expiresAtMs: Long,
    )
    private val pendingJoins = ConcurrentHashMap<String, PendingJoin>()
    // Signals a session's WebSocket to drain immediately; heartbeat draining is the fallback.
    private val wakes = ConcurrentHashMap<String, Channel<Unit>>()
    private fun wake(id: String) = wakes.computeIfAbsent(id) { Channel(Channel.CONFLATED) }

    /** The watch-together room [id] belongs to, or null when it's watching alone. */
    private fun roomFor(id: String): Room? = memberRoom[id]?.let { rooms[it] }

    /** Upsert session state from a heartbeat. Commands are drained separately - the HTTP
     *  heartbeat returns them, the WebSocket pushes them as they're queued. */
    fun create(
        actor: Actor,
        playlistId: Long,
        contentId: String,
        sourceUrl: String,
        ip: String,
        userAgent: String,
    ): Live {
        val now = clock.nowMs()
        val id = UUID.randomUUID().toString()
        val heartbeat = SessionHeartbeatDto(id = id)
        return Live(
            id, actor.userId, actor.authSessionId, actor.username, actor.displayName,
            actor.clientKind, playlistId, contentId, sourceUrl, ip, userAgent, heartbeat, now, now,
        ).also { sessions[id] = it }
    }

    fun owned(actor: Actor, id: String): Live {
        val live = sessions[id] ?: throw PlaybackRevokedException()
        if (live.userId != actor.userId || live.authSessionId != actor.authSessionId) {
            throw PlaybackRevokedException()
        }
        return live
    }

    fun update(actor: Actor, ip: String, userAgent: String, dto: SessionHeartbeatDto) {
        owned(actor, dto.id)
        val now = clock.nowMs()
        sessions.compute(dto.id) { _, existing ->
            existing?.apply {
                this.ip = ip
                this.userAgent = userAgent
                state = dto
                lastSeenMs = now
            }
        }
    }

    /** Upsert from a heartbeat and drain any commands queued for this session (HTTP fallback). */
    fun heartbeat(actor: Actor, ip: String, userAgent: String, dto: SessionHeartbeatDto): List<SessionCommandDto> {
        update(actor, ip, userAgent, dto)
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
            roomFor(id)?.let { broadcast(it, command); return true }
        }
        return enqueue(id, command)
    }

    /** Other live viewers watching the same [contentKey] as [selfId] - watch-together candidates. */
    fun sameContentPeers(selfId: String, contentKey: String): List<Live> {
        if (contentKey.isBlank()) return emptyList()
        return active().filter { it.id != selfId && it.contentId == contentKey }
    }

    /** Ask [hostId] to admit [peerId] into a watch-together room; false when the host is gone. */
    @Synchronized
    fun requestJoin(targetId: String, requesterId: String, peerName: String, contentKey: String): String? {
        val target = sessions[targetId] ?: return null
        val requester = sessions[requesterId] ?: return null
        if (targetId == requesterId || target.contentId != contentKey ||
            requester.contentId != contentKey
        ) return null
        val targetRoom = roomFor(targetId)
        if (targetRoom != null && targetRoom.hostId != targetId) return null
        val requesterRoom = roomFor(requesterId)
        if (requesterRoom != null && requesterRoom.id == targetRoom?.id) return null
        val now = clock.nowMs()
        pendingJoins.entries.removeIf {
            it.value.expiresAtMs <= now ||
                (it.value.requesterId == requesterId && it.value.targetId == targetId)
        }
        val request = PendingJoin(
            UUID.randomUUID().toString(), requesterId, targetId, contentKey, now + JOIN_REQUEST_TTL_MS,
        )
        pendingJoins[request.id] = request
        val quiet = declined[targetId]?.contains(declineKey(requesterId, contentKey)) == true
        if (!enqueue(targetId, SessionCommandDto(
                type = "join-request",
                peerId = requesterId,
                peerName = peerName,
                requestId = request.id,
                quiet = quiet,
            ))
        ) {
            pendingJoins.remove(request.id)
            return null
        }
        return request.id
    }

    /** The host's answer to a join request. On accept both share a room; on decline it's
     *  remembered so the same peer can't pop another modal for the same content. */
    @Synchronized
    fun answerJoin(targetId: String, requestId: String, accept: Boolean): Boolean {
        val request = pendingJoins[requestId] ?: return false
        if (request.targetId != targetId || request.expiresAtMs <= clock.nowMs()) {
            if (request.expiresAtMs <= clock.nowMs()) pendingJoins.remove(requestId)
            return false
        }
        pendingJoins.remove(requestId)
        val target = sessions[targetId] ?: return false
        val requester = sessions[request.requesterId] ?: return false
        if (target.contentId != request.contentId || requester.contentId != request.contentId) return false
        if (!accept) {
            declined.computeIfAbsent(targetId) { java.util.concurrent.ConcurrentHashMap.newKeySet() }
                .add(declineKey(request.requesterId, request.contentId))
            return enqueue(
                request.requesterId,
                SessionCommandDto(type = "join-response", accepted = false, requestId = requestId),
            )
        }
        val targetRoom = roomFor(targetId)
        if (targetRoom != null && targetRoom.hostId != targetId) return false
        roomFor(request.requesterId)?.let { previous ->
            if (previous.id == targetRoom?.id) return false
            removeFromRoom(previous, request.requesterId)
        }
        declined[targetId]?.remove(declineKey(request.requesterId, request.contentId))
        val room = targetRoom ?: Room("r-$targetId", targetId).also {
            it.members.add(targetId)
            it.controllers.add(targetId)
            rooms[it.id] = it
            memberRoom[targetId] = it.id
        }
        room.members.add(request.requesterId)
        memberRoom[request.requesterId] = room.id
        enqueue(
            request.requesterId,
            SessionCommandDto(type = "join-response", accepted = true, requestId = requestId),
        )
        pushRoomState(room)
        return true
    }

    /** A guest asks the room's host to let it control playback too. */
    @Synchronized
    fun requestControl(fromId: String, fromName: String): Boolean {
        val room = roomFor(fromId) ?: return false
        if (fromId in room.controllers) return true
        return enqueue(room.hostId, SessionCommandDto(
            type = "control-request", peerId = fromId, peerName = fromName,
        ))
    }

    /** The host's answer to a control request. Only the host may grant; on grant the guest
     *  joins [controllers] and can drive playback alongside everyone else already allowed. */
    @Synchronized
    fun grantControl(hostId: String, peerId: String, grant: Boolean): Boolean {
        val room = roomFor(hostId) ?: return false
        if (room.hostId != hostId || peerId !in room.members) return false
        if (grant) { room.controllers.add(peerId); pushRoomState(room) }
        return enqueue(peerId, SessionCommandDto(type = "control-response", accepted = grant))
    }

    /** The host hands a member control (or takes it back) directly, no request needed. */
    @Synchronized
    fun setControl(hostId: String, targetId: String, grant: Boolean): Boolean {
        val room = roomFor(hostId) ?: return false
        if (room.hostId != hostId || targetId == hostId || targetId !in room.members) return false
        if (grant) room.controllers.add(targetId) else room.controllers.remove(targetId)
        pushRoomState(room)
        enqueue(targetId, SessionCommandDto(type = "control-response", accepted = grant))
        return true
    }

    /** The host removes [targetId] from the room. */
    @Synchronized
    fun kick(hostId: String, targetId: String): Boolean {
        val room = roomFor(hostId) ?: return false
        if (room.hostId != hostId || targetId == hostId || targetId !in room.members) return false
        enqueue(targetId, SessionCommandDto(type = "room-ended"))
        terminate(targetId)
        return true
    }

    /** The room [id] is in and how many are in it, for the activity dashboard. Null if none. */
    @Synchronized
    fun roomOf(id: String): Pair<String, Int>? {
        val roomId = memberRoom[id] ?: return null
        val room = rooms[roomId] ?: return null
        return roomId to room.members.size
    }

    /** The share group that owns [id]'s provider connection: its room when in one (so the whole
     *  room reads the file once), otherwise itself (a lone viewer with its own read/seat). */
    @Synchronized
    fun shareGroup(id: String): String = memberRoom[id] ?: id

    /** Every member of [id]'s room (so a read forming the room can free their solo seats),
     *  or empty when [id] is watching alone. */
    @Synchronized
    fun roomMembers(id: String): Set<String> =
        roomFor(id)?.members?.toSet() ?: emptySet()

    /** The audio track a room member must remux with, so everyone shares one read. Null when solo. */
    @Synchronized
    fun roomAudio(id: String): Int? = roomFor(id)?.audioIndex

    /** Whether [id]'s shared read may copy HEVC: true only while every member can decode it.
     *  Records this member's own [clientCanHevc]; when that flips the room's answer, everyone
     *  reloads onto the new format. Solo viewers just get their own capability back. */
    @Synchronized
    fun roomHevc(id: String, clientCanHevc: Boolean): Boolean {
        val room = roomFor(id) ?: return clientCanHevc
        val before = room.noHevc.isEmpty()
        if (clientCanHevc) room.noHevc.remove(id) else room.noHevc.add(id)
        val after = room.noHevc.isEmpty()
        if (after != before) startReload(room)
        return after
    }

    /** A controller picks the room's shared audio track; every member re-requests the remux with
     *  it, so the room stays on one provider connection. Ignored from a non-controller. */
    @Synchronized
    fun setRoomAudio(fromId: String, index: Int): Boolean {
        val room = roomFor(fromId) ?: return false
        if (fromId !in room.controllers) return false
        room.audioIndex = index
        startReload(room)
        return true
    }

    /** A member finished reloading the shared track; once every member has, release the room to
     *  play again in step. Best-effort - a client also fails open on its own timeout. */
    @Synchronized
    fun markReady(sid: String): Boolean {
        val room = roomFor(sid) ?: return false
        room.ready.add(sid)
        if (room.ready.containsAll(room.members)) {
            room.ready.clear()
            broadcast(room, SessionCommandDto(type = "room-go"))
        }
        return true
    }

    private fun roster(room: Room): List<RoomMemberDto> = room.members.map { id ->
        RoomMemberDto(
            id = id,
            name = sessions[id]?.displayName?.takeIf { it.isNotBlank() } ?: "Someone",
            host = id == room.hostId,
            controller = id in room.controllers,
        )
    }

    /** Queue [command] for every member of [room]. */
    private fun broadcast(room: Room, command: SessionCommandDto) = room.members.forEach { enqueue(it, command) }

    /** Start a reload barrier: reset the ready set and have every member re-request the shared read
     *  (its audio track rides along), so nobody resumes until all are back. */
    private fun startReload(room: Room) {
        room.ready.clear()
        broadcast(room, SessionCommandDto(type = "room-audio", audioIndex = room.audioIndex))
    }

    /** Push the current roster to every member, so each renders who's in and their rights. */
    private fun pushRoomState(room: Room) = broadcast(room, SessionCommandDto(type = "room-state", members = roster(room)))

    /** Re-send the roster to [id] alone if it's still in a room, so a socket that just (re)connected
     *  - after a page refresh - picks its watch-together session back up instead of dropping out. */
    @Synchronized
    fun resendRoomState(id: String) {
        roomFor(id)?.let { enqueue(id, SessionCommandDto(type = "room-state", members = roster(it))) }
    }

    /** Mirror a controller's [state] to the room's other members (non-controllers can't drive). */
    @Synchronized
    fun syncRoom(fromId: String, state: SyncStateDto) {
        val room = roomFor(fromId) ?: return
        if (fromId !in room.controllers) return
        room.members.filter { it != fromId }.forEach { member ->
            // Keep only the freshest sync queued, so a brief socket outage can't back them up.
            sessions[member]?.commands?.removeIf { it.type == "sync" }
            enqueue(member, SessionCommandDto(type = "sync", sync = state))
        }
    }

    /** Take [id] out of its room, dissolving it when only one lone member would be left,
     *  promoting a new host if the host left, and re-broadcasting the roster otherwise. */
    @Synchronized
    fun leaveRoom(id: String) {
        val room = roomFor(id) ?: return
        removeFromRoom(room, id)
    }

    // The room lives as long as anyone is in it - even a lone host, who can then admit someone
    // back - and only dissolves once empty. A departing host hands off to whoever remains.
    private fun removeFromRoom(room: Room, id: String) {
        memberRoom.remove(id)
        room.members.remove(id)
        room.controllers.remove(id)
        room.ready.remove(id)
        room.noHevc.remove(id)
        // Cut any shared live connection this viewer was riding, now that it's no longer a member.
        runCatching { cleanup.memberLeaving(id) }
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

    @Synchronized
    fun remove(id: String) {
        val priorRoom = memberRoom[id]
        leaveRoom(id)
        declined.remove(id)
        pendingJoins.entries.removeIf {
            it.value.requesterId == id || it.value.targetId == id
        }
        if (sessions.remove(id) != null) {
            val unusedGroup = when {
                priorRoom == null -> id
                rooms.containsKey(priorRoom) -> null
                else -> priorRoom
            }
            runCatching { cleanup.leaseTerminated(id, unusedGroup) }
        }
        wakes.remove(id)?.close()
    }

    fun terminate(id: String) = remove(id)

    fun terminateSession(authSessionId: String) =
        sessions.values.filter { it.authSessionId == authSessionId }.forEach { remove(it.id) }

    fun terminateUser(userId: String) =
        sessions.values.filter { it.userId == userId }.forEach { remove(it.id) }

    fun terminatePlaylist(userId: String, playlistId: Long) =
        sessions.values.filter { it.userId == userId && it.playlistId == playlistId }
            .forEach { remove(it.id) }

    fun terminatePlaylist(playlistId: Long) =
        sessions.values.filter { it.playlistId == playlistId }.forEach { remove(it.id) }

    /** Live sessions (stale ones pruned first), newest first. */
    fun active(): List<Live> {
        val now = clock.nowMs()
        val cutoff = now - staleMs
        val stale = sessions.values.filter { it.lastSeenMs < cutoff }.map { it.id }
        stale.forEach { remove(it) }
        return sessions.values.sortedByDescending { it.startedAtMs }
    }

    companion object {
        /** Drop a session this long after its last heartbeat (client beats ~every 3s). */
        private const val DEFAULT_STALE_MS = 12_000L
        // Must outlive every media grant so stale lease-scoped URLs consistently return 410.
        private const val JOIN_REQUEST_TTL_MS = 60_000L
    }

    override fun close() {
        sessions.keys.toList().forEach(::remove)
        reaperScope.cancel()
    }
}

internal class PlaybackRevokedException : RuntimeException()
