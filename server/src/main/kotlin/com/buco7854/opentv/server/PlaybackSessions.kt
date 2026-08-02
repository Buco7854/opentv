package com.buco7854.opentv.server

import com.buco7854.opentv.contract.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.UUID

/**
 * In-memory registry of active server-client playback sessions. A player heartbeats
 * every few seconds; sessions with no recent heartbeat are dropped. Commands the
 * admin enqueues are delivered on the session's next heartbeat.
 *
 * Each lease owns its client's codec report. Watch-together rooms reduce those reports
 * to one intersection because every member consumes the same shared media format.
 */
class PlaybackSessionRegistry(
    private val clock: ServerClock = ServerClock.SYSTEM,
    private val staleMs: Long = DEFAULT_STALE_MS,
    private val cleanup: PlaybackLeaseCleanup = NoopPlaybackLeaseCleanup,
    private val kickNoticeGraceMs: Long = DEFAULT_KICK_NOTICE_GRACE_MS,
    /**
     * The background reaper sweeps on the wall clock, independently of [clock].
     * Tests that drive a fake clock must leave it off, or a sweep landing mid-test
     * reaps their leases and fails an unrelated assertion at random.
     */
    reapInBackground: Boolean = true,
) : AutoCloseable {
    internal data class MediaScope(val group: String, val generation: Long)
    internal data class PlaybackIdentity(val contentId: String, val sourceUrl: String)

    private val reaperScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        if (reapInBackground) {
            reaperScope.launch {
                while (isActive) {
                    delay(staleMs.coerceAtLeast(1_000L))
                    active()
                }
            }
        }
    }

    class Live internal constructor(
        val id: String,
        val userId: String,
        val authSessionId: String,
        val username: String,
        val displayName: String,
        val clientKind: String,
        val playlistId: Long,
        val contentId: String,
        internal val sourceUrl: String,
        internal val liveSource: Boolean,
        @Volatile var ip: String,
        @Volatile var userAgent: String,
        @Volatile var state: SessionHeartbeatDto,
        val startedAtMs: Long,
        internal val startedOrder: Long,
        @Volatile var lastSeenMs: Long,
        internal val capabilities: MediaCapabilities,
        val commands: ConcurrentLinkedQueue<SessionCommandDto> = ConcurrentLinkedQueue(),
    ) {
        internal val playbackIdentity = PlaybackIdentity(contentId, sourceUrl)
        internal var commandSequence: Long = 0
    }

    /** A watch-together room. The host owns it and can grant playback control to guests;
     *  everyone in [controllers] (the host plus whoever it allowed) can drive, the rest mirror. */
    private class Room(
        val id: String,
        @Volatile var hostId: String,
        val playbackIdentity: PlaybackIdentity,
    ) {
        val members: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        val controllers: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        // A kick suppresses only this room's same-account automatic admission. Host approval
        // removes the account from this set, so this remains a rejoin prompt rather than a ban.
        val autoAdmissionExcludedUsers: MutableSet<String> =
            java.util.concurrent.ConcurrentHashMap.newKeySet()
        // The room shares one remux read, so one audio track: whichever a controller last chose.
        @Volatile var audioIndex: Int = 0
        // Members that have finished reloading after a track change; when it covers everyone the
        // room resumes together, so no one plays ahead while another is still buffering the switch.
        val ready: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        @Volatile var reloading: Boolean = false
        @Volatile var reloadTimeout: Job? = null
        // A room's reload barriers are ordered independently of command delivery. A ready or
        // room-go from an older generation can never complete/release the current barrier.
        @Volatile var barrierGeneration: Long = 0
        // Fixed per-lease reports. The shared read uses their intersection.
        val capabilities = ConcurrentHashMap<String, MediaCapabilities>()
    }

    private val sessions = ConcurrentHashMap<String, Live>()
    private var startedOrder: Long = 0
    private val rooms = ConcurrentHashMap<String, Room>()
    private val memberRoom = ConcurrentHashMap<String, String>()
    // A kicked lease remains command-readable only for the notice grace; it cannot re-enter a
    // room or authorize media during that interval.
    private val terminating: MutableSet<String> = ConcurrentHashMap.newKeySet()
    // A host that declined a peer for one playback variant isn't pestered again for that variant.
    private data class DeclineKey(val peerId: String, val playbackIdentity: PlaybackIdentity)
    private val declined = ConcurrentHashMap<String, MutableSet<DeclineKey>>()
    private data class PendingJoin(
        val id: String,
        val requesterId: String,
        val targetId: String,
        val playbackIdentity: PlaybackIdentity,
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
    @Synchronized
    internal fun create(
        actor: Actor,
        playlistId: Long,
        contentId: String,
        sourceUrl: String,
        ip: String,
        userAgent: String,
        capabilities: MediaCapabilities = MediaCapabilities.BROWSER,
        liveSource: Boolean = false,
    ): Live {
        val now = clock.nowMs()
        val id = UUID.randomUUID().toString()
        check(startedOrder < Long.MAX_VALUE) { "Playback lease order exhausted" }
        val order = ++startedOrder
        val heartbeat = SessionHeartbeatDto(id = id)
        return Live(
            id, actor.userId, actor.authSessionId, actor.username, actor.displayName,
            actor.clientKind, playlistId, contentId, sourceUrl, liveSource,
            ip, userAgent, heartbeat, now, order, now,
            capabilities,
        ).also { sessions[id] = it }
    }

    fun owned(actor: Actor, id: String): Live {
        val live = sessions[id] ?: throw PlaybackRevokedException()
        if (live.userId != actor.userId || live.authSessionId != actor.authSessionId) {
            throw PlaybackRevokedException()
        }
        return live
    }

    fun lease(id: String): Live =
        sessions[id] ?: throw PlaybackRevokedException()

    @Synchronized
    fun mediaAllowed(id: String): Boolean = sessions.containsKey(id) && id !in terminating

    /**
     * Keep an already-authorized media lease alive.
     *
     * This is deliberately separate from [lease]: an invalid grant must not be able to preserve
     * a guessed or leaked lease id. Media routes call it only after their capability check passes.
     */
    @Synchronized
    fun touch(id: String): Live {
        val live = sessions[id] ?: throw PlaybackRevokedException()
        live.lastSeenMs = clock.nowMs()
        return live
    }

    /**
     * Publish lease-owned runtime state atomically with respect to lease termination.
     *
     * Cleanup runs while holding this registry monitor, so it either sees state published by
     * [block] or wins first and prevents [block] from running against a tombstoned lease.
     */
    @Synchronized
    internal fun <T> withLiveLease(id: String, block: (Live) -> T): T {
        val live = sessions[id] ?: throw PlaybackRevokedException()
        return block(live)
    }

    @Synchronized
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
        synchronized(live) {
            check(live.commandSequence < Long.MAX_VALUE) { "Playback command sequence exhausted" }
            live.commandSequence++
            live.commands.add(command.copy(sequence = live.commandSequence))
            while (live.commands.size > MAX_QUEUED_COMMANDS_PER_LEASE) {
                live.commands.poll()
            }
        }
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

    /** Other viewers on [selfId]'s exact playback variant - watch-together candidates. */
    fun sameContentPeers(selfId: String, contentKey: String): List<Live> {
        if (contentKey.isBlank()) return emptyList()
        val self = sessions[selfId] ?: return emptyList()
        if (self.contentId != contentKey) return emptyList()
        return active().filter {
            it.id != selfId &&
                it.id !in terminating &&
                it.playbackIdentity == self.playbackIdentity
        }
    }

    /**
     * The older lease that prevents [id] from playing independently. The ordering makes only the
     * newcomer wait: discovering a second device must never revoke or stall the stream that was
     * already playing. Joining the older lease's room clears the conflict; leaving restores it.
     */
    @Synchronized
    fun sameAccountConflict(id: String): Live? {
        val self = sessions[id] ?: throw PlaybackRevokedException()
        val selfRoom = memberRoom[id]
        return active().firstOrNull { other ->
            other.startedOrder < self.startedOrder &&
                other.id !in terminating &&
                other.userId == self.userId &&
                other.playbackIdentity == self.playbackIdentity &&
                (selfRoom == null || memberRoom[other.id] != selfRoom)
        }
    }

    /** Atomically decide whether this lease may remain as an independent playback attempt. */
    @Synchronized
    fun watchAlone(id: String): Boolean {
        if (sameAccountConflict(id) == null) return true
        remove(id)
        return false
    }

    /** Ask [hostId] to admit [peerId] into a watch-together room; false when the host is gone. */
    @Synchronized
    fun requestJoin(targetId: String, requesterId: String, peerName: String, contentKey: String): String? {
        val target = sessions[targetId] ?: return null
        val requester = sessions[requesterId] ?: return null
        if (targetId in terminating || requesterId in terminating ||
            targetId == requesterId || target.contentId != contentKey ||
            requester.contentId != contentKey ||
            target.playbackIdentity != requester.playbackIdentity
        ) return null
        val targetRoom = roomFor(targetId)
        if (targetRoom != null && targetRoom.playbackIdentity != requester.playbackIdentity) {
            return null
        }
        val sameAccount = target.userId == requester.userId
        val autoAdmissionExcluded = sameAccount &&
            targetRoom?.autoAdmissionExcludedUsers?.contains(requester.userId) == true
        // A different account must still address the host. Another device of a member's own
        // account may follow that member unless a kick made host approval mandatory. In that
        // case a request aimed at the account's remaining device is routed to the actual host.
        if (targetRoom != null && targetRoom.hostId != targetId &&
            !sameAccount && !autoAdmissionExcluded
        ) return null
        val admissionTarget = if (autoAdmissionExcluded) {
            sessions[targetRoom.hostId] ?: return null
        } else {
            target
        }
        val requesterRoom = roomFor(requesterId)
        if (requesterRoom != null && requesterRoom.id == targetRoom?.id) {
            // Two own devices can race the same automatic admission. The first request may have
            // already pulled both into the room before the second reaches this monitor; from the
            // second caller's perspective the requested state is satisfied, so keep it idempotent.
            return UUID.randomUUID().toString().takeIf { sameAccount }
        }
        val now = clock.nowMs()
        pendingJoins.entries.removeIf {
            it.value.expiresAtMs <= now ||
                (it.value.requesterId == requesterId && it.value.targetId == admissionTarget.id)
        }
        val request = PendingJoin(
            UUID.randomUUID().toString(), requesterId, admissionTarget.id,
            requester.playbackIdentity, now + JOIN_REQUEST_TTL_MS,
        )
        if (sameAccount && !autoAdmissionExcluded) {
            // Every live lease for this account/variant must land in one room. With three devices,
            // joining only the selected newer lease would leave an older lease outside: the client
            // would receive room-state even though the media boundary still (correctly) refused it.
            // Anchor the batch on the oldest lease, whose playback was admitted first, so a blocked
            // newer device cannot become host and seek the established stream back to its idle
            // position. Existing rooms are never merged implicitly.
            return request.id.takeIf {
                admitSameAccountJoin(
                    selectedTarget = target,
                    requester = requester,
                    requestId = request.id,
                )
            }
        }
        pendingJoins[request.id] = request
        val declineKey = DeclineKey(requesterId, requester.playbackIdentity)
        val quiet = declined[admissionTarget.id]?.contains(declineKey) == true
        if (!enqueue(admissionTarget.id, SessionCommandDto(
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
     *  remembered so the same peer can't pop another modal for the same playback variant. */
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
        if (targetId in terminating || requester.id in terminating ||
            target.playbackIdentity != request.playbackIdentity ||
            requester.playbackIdentity != request.playbackIdentity
        ) return false
        if (!accept) {
            declined.computeIfAbsent(targetId) { java.util.concurrent.ConcurrentHashMap.newKeySet() }
                .add(DeclineKey(request.requesterId, request.playbackIdentity))
            return enqueue(
                request.requesterId,
                SessionCommandDto(type = "join-response", accepted = false, requestId = requestId),
            )
        }
        val targetRoom = roomFor(targetId)
        if (targetRoom != null && targetRoom.hostId != targetId) return false
        // Host approval cannot make an unrelated room satisfy this account's duplicate rule.
        // The destination must already contain every older own-device lease that blocks this
        // requester, or room-state would claim success while the media boundary still returned 409.
        if (hasOlderSameAccountLeaseOutside(requester, targetRoom?.id)) return false
        declined[targetId]?.remove(DeclineKey(request.requesterId, request.playbackIdentity))
        val admitted = admitJoin(
            target = target,
            requester = requester,
            targetRoom = targetRoom,
            requestId = requestId,
            requesterControls = false,
        )
        if (admitted) roomFor(requester.id)?.autoAdmissionExcludedUsers?.remove(requester.userId)
        return admitted
    }

    private fun hasOlderSameAccountLeaseOutside(requester: Live, roomId: String?): Boolean =
        sessions.values.any { other ->
            other.startedOrder < requester.startedOrder &&
                other.id !in terminating &&
                other.userId == requester.userId &&
                other.playbackIdentity == requester.playbackIdentity &&
                (roomId == null || memberRoom[other.id] != roomId)
        }

    /** Complete the already-authorized membership transition shared by automatic and approved joins. */
    private fun admitJoin(
        target: Live,
        requester: Live,
        targetRoom: Room?,
        requestId: String,
        requesterControls: Boolean,
    ): Boolean {
        if (target.playbackIdentity != requester.playbackIdentity ||
            targetRoom?.playbackIdentity?.let { it != requester.playbackIdentity } == true
        ) return false
        roomFor(requester.id)?.let { previous ->
            if (previous.id == targetRoom?.id) return false
            removeFromRoom(previous, requester.id)
        }
        val room = targetRoom ?: Room(
            "r-${target.id}", target.id, target.playbackIdentity,
        ).also {
            it.members.add(target.id)
            it.controllers.add(target.id)
            it.capabilities[target.id] = target.capabilities
            rooms[it.id] = it
            memberRoom[target.id] = it.id
        }
        room.members.add(requester.id)
        if (requesterControls) room.controllers.add(requester.id)
        room.capabilities[requester.id] = requester.capabilities
        memberRoom[requester.id] = room.id
        enqueue(
            requester.id,
            SessionCommandDto(type = "join-response", accepted = true, requestId = requestId),
        )
        pushRoomState(room)
        // Membership changes the share group used by live sharing and any required remux. Every
        // member renegotiates against the capability intersection. Fully capable direct-play VOD
        // deliberately remains lease-owned and may still consume one provider seat per member.
        startReload(room)
        return true
    }

    /**
     * Admit all of one account's live leases for one playback variant as one atomic own-device join.
     *
     * A lease already in another room makes the batch ambiguous: moving it would silently alter
     * that other room, while leaving it behind would preserve a media conflict. Refuse instead of
     * weakening the one-independent-playback rule. Solo leases may follow the oldest lease into
     * its room without approval, which is the same-account policy applied consistently to N
     * devices rather than only two.
     */
    private fun admitSameAccountJoin(
        selectedTarget: Live,
        requester: Live,
        requestId: String,
    ): Boolean {
        val devices = sessions.values
            .filter {
                it.id !in terminating &&
                    it.userId == requester.userId &&
                    it.playbackIdentity == requester.playbackIdentity
            }
            .sortedBy { it.startedOrder }
        val anchor = devices.firstOrNull() ?: return false
        val destination = roomFor(anchor.id)
        if (destination?.playbackIdentity?.let { it != requester.playbackIdentity } == true) {
            return false
        }
        if (devices.any { device ->
                roomFor(device.id)?.let { it.id != destination?.id } == true
            }
        ) return false
        val room = destination ?: Room(
            "r-${anchor.id}", anchor.id, anchor.playbackIdentity,
        ).also {
            it.members.add(anchor.id)
            it.controllers.add(anchor.id)
            it.capabilities[anchor.id] = anchor.capabilities
            rooms[it.id] = it
            memberRoom[anchor.id] = it.id
        }
        val addedControls = if (destination == null) {
            true
        } else {
            val controlSource = selectedTarget.takeIf { it.id in room.members } ?: anchor
            controlSource.id in room.controllers
        }
        devices.forEach { device ->
            if (device.id !in room.members) {
                room.members.add(device.id)
                if (addedControls) room.controllers.add(device.id)
                room.capabilities[device.id] = device.capabilities
                memberRoom[device.id] = room.id
            }
        }
        enqueue(
            requester.id,
            SessionCommandDto(type = "join-response", accepted = true, requestId = requestId),
        )
        pushRoomState(room)
        startReload(room)
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
        val target = sessions[targetId] ?: return false
        // Membership and access to the shared read end immediately. Keep the lease's command
        // channel alive only for a short bounded grace so room-ended can be pushed (or drained
        // by the HTTP fallback); the server-owned timer revokes the lease even if the client stalls.
        // Exclusion is by account within this room: another device cannot undo the kick through
        // own-account auto-admission, while approval clears it and room teardown forgets it.
        room.autoAdmissionExcludedUsers.add(target.userId)
        terminating.add(targetId)
        removeFromRoom(room, targetId)
        enqueue(targetId, SessionCommandDto(type = "room-ended"))
        reaperScope.launch {
            delay(kickNoticeGraceMs.coerceAtLeast(0))
            terminate(targetId)
        }
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

    /** The shared media route pins the room it entered; leaving or moving rooms revokes that use. */
    @Synchronized
    fun isShareGroupMember(id: String, group: String): Boolean =
        memberRoom[id] == group && rooms[group]?.members?.contains(id) == true

    /** A live room must use its room-owned relay/cache/remux rather than reopen one solo read per
     *  member. VOD is deliberately different: a fully capable direct player remains lease-owned. */
    @Synchronized
    fun requiresSharedLiveTransport(id: String): Boolean =
        lease(id).liveSource && roomFor(id) != null

    /** Scope a prepared media attachment to the exact solo/room generation that created it. */
    @Synchronized
    internal fun mediaScope(id: String): MediaScope =
        roomFor(id)?.let { MediaScope(it.id, it.barrierGeneration) }
            ?: MediaScope(lease(id).id, 0)

    /** Used by a room-owned upstream fetch, which may outlive the member that triggered it. */
    @Synchronized
    fun hasShareGroup(group: String): Boolean = rooms[group]?.members?.isNotEmpty() == true

    /** The audio track a room member must remux with, so everyone shares one read. Null when solo. */
    @Synchronized
    fun roomAudio(id: String): Int? = roomFor(id)?.audioIndex

    /** The format every member can decode. A solo lease keeps its complete report. */
    @Synchronized
    internal fun roomCapabilities(id: String): MediaCapabilities =
        roomFor(id)?.let(::effectiveCapabilities) ?: lease(id).capabilities

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
    fun markReady(sid: String, generation: Long): Boolean {
        val room = roomFor(sid) ?: return false
        if (generation != room.barrierGeneration) return false
        if (!room.reloading) return true
        room.ready.add(sid)
        finishReloadIfReady(room)
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
        check(room.barrierGeneration < Long.MAX_VALUE) { "Room barrier generation exhausted" }
        room.reloadTimeout?.cancel()
        room.barrierGeneration++
        // A remux prepared for the previous membership/capability generation must not remain
        // usable beside the replacement. Live transitions also cut every lease-owned transport;
        // direct-play VOD deliberately retains the established member's per-member seat.
        room.members.forEach { member ->
            runCatching {
                cleanup.mediaScopeChanging(
                    member,
                    dropTransports = sessions[member]?.liveSource == true,
                )
            }
        }
        room.reloading = true
        room.ready.clear()
        broadcast(
            room,
            SessionCommandDto(
                type = "room-audio",
                audioIndex = room.audioIndex,
                generation = room.barrierGeneration,
            ),
        )
        val generation = room.barrierGeneration
        room.reloadTimeout = reaperScope.launch {
            // Match the clients' lease-sized fail-open window. One dead or malicious member must
            // not leave the server replaying room-audio forever on every reconnect.
            delay(staleMs.coerceAtLeast(1))
            finishReloadOnTimeout(room.id, generation)
        }
    }

    private fun finishReloadIfReady(room: Room) {
        if (!room.reloading || !room.ready.containsAll(room.members)) return
        finishReload(room)
    }

    @Synchronized
    private fun finishReloadOnTimeout(roomId: String, generation: Long) {
        val room = rooms[roomId] ?: return
        if (!room.reloading || room.barrierGeneration != generation) return
        finishReload(room)
    }

    private fun finishReload(room: Room) {
        room.reloading = false
        room.ready.clear()
        room.reloadTimeout?.cancel()
        room.reloadTimeout = null
        broadcast(room, SessionCommandDto(type = "room-go", generation = room.barrierGeneration))
    }

    /** Push the current roster to every member, so each renders who's in and their rights. */
    private fun pushRoomState(room: Room) = broadcast(room, SessionCommandDto(type = "room-state", members = roster(room)))

    /** Re-send the roster to [id] alone if it's still in a room, so a socket that just (re)connected
     *  - after a page refresh - picks its watch-together session back up instead of dropping out. */
    @Synchronized
    fun resendRoomState(id: String) {
        roomFor(id)?.let { room ->
            enqueue(id, SessionCommandDto(type = "room-state", members = roster(room)))
            if (room.reloading) {
                enqueue(
                    id,
                    SessionCommandDto(
                        type = "room-audio",
                        audioIndex = room.audioIndex,
                        generation = room.barrierGeneration,
                    ),
                )
            } else if (room.barrierGeneration > 0) {
                // A delayed HTTP fallback may still hold the original room-go while this
                // reconnect's newer roster advances the client's sequence high-water mark.
                // Replaying the completed generation makes that delivery inversion harmless.
                enqueue(
                    id,
                    SessionCommandDto(type = "room-go", generation = room.barrierGeneration),
                )
            }
        }
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
        val capabilitiesBefore = effectiveCapabilities(room)
        memberRoom.remove(id)
        room.members.remove(id)
        room.controllers.remove(id)
        room.ready.remove(id)
        room.capabilities.remove(id)
        // Cut any shared live connection this viewer was riding, now that it's no longer a member.
        runCatching { cleanup.memberLeaving(id) }
        if (room.members.isEmpty()) {
            room.reloadTimeout?.cancel()
            room.reloadTimeout = null
            rooms.remove(room.id)
            runCatching { cleanup.shareGroupUnused(room.id) }
            return
        }
        if (room.hostId == id) {
            room.hostId = room.members.first()
            room.controllers.add(room.hostId)
        }
        pushRoomState(room)
        if (effectiveCapabilities(room) != capabilitiesBefore) {
            startReload(room)
        } else {
            finishReloadIfReady(room)
        }
    }

    private fun effectiveCapabilities(room: Room): MediaCapabilities =
        room.capabilities.values.reduceOrNull(MediaCapabilities::intersect)
            ?: MediaCapabilities.BROWSER

    /** Fires whenever a command is queued for [id]; the WebSocket drains on each signal. */
    fun commandSignal(id: String): ReceiveChannel<Unit> = wake(id)

    @Synchronized
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
        terminating.remove(id)
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
        val stale = sessions.values
            .filter { it.lastSeenMs < cutoff }
            .map { it.id to it.lastSeenMs }
        stale.forEach { (id, observedLastSeenMs) ->
            removeIfStillStale(id, observedLastSeenMs, cutoff)
        }
        return sessions.values.sortedByDescending { it.startedAtMs }
    }

    @Synchronized
    private fun removeIfStillStale(id: String, observedLastSeenMs: Long, cutoff: Long) {
        val live = sessions[id] ?: return
        if (live.lastSeenMs == observedLastSeenMs && live.lastSeenMs < cutoff) remove(id)
    }

    companion object {
        /** Drop a session this long after its last heartbeat (client beats ~every 3s). */
        private const val DEFAULT_STALE_MS = 12_000L
        /** Enough for a live socket/heartbeat drain, while keeping kick revocation tightly bounded. */
        private const val DEFAULT_KICK_NOTICE_GRACE_MS = 750L
        // Must outlive every media grant so stale lease-scoped URLs consistently return 410.
        private const val JOIN_REQUEST_TTL_MS = 60_000L
        /** Commands are best-effort state changes, not an unbounded reliable-delivery log. */
        internal const val MAX_QUEUED_COMMANDS_PER_LEASE = 256
    }

    override fun close() {
        sessions.keys.toList().forEach(::remove)
        reaperScope.cancel()
    }
}

internal class PlaybackRevokedException : RuntimeException()
