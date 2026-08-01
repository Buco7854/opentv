package com.buco7854.opentv.ui.player

import com.buco7854.opentv.contract.RoomMemberDto
import com.buco7854.opentv.contract.SessionCommandDto
import com.buco7854.opentv.contract.SyncStateDto
import com.buco7854.opentv.contract.WatchIntentPeer
import com.buco7854.opentv.contract.WatchIntentResponse
import com.buco7854.opentv.hub.HubDuplicatePlaybackException
import com.buco7854.opentv.hub.playback.isProtocolCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

internal data class PendingJoinRequest(
    val peerId: String,
    val peerName: String,
    val requestId: String,
    val quiet: Boolean,
)

internal data class PendingControlRequest(
    val peerId: String,
    val peerName: String,
)

internal enum class WatchTogetherNoticeKind {
    ADMIN_MESSAGE,
    JOIN_REQUEST,
    CONTROL_REQUEST,
    JOINED,
    JOIN_DECLINED,
    CONTROL_GRANTED,
    CONTROL_DENIED,
    ROOM_ENDED,
    ACTION_FAILED,
}

internal data class WatchTogetherNotice(
    val id: Long,
    val kind: WatchTogetherNoticeKind,
    val text: String? = null,
)

internal data class WatchTogetherState(
    val selfId: String? = null,
    val peers: List<WatchIntentPeer> = emptyList(),
    val members: List<RoomMemberDto> = emptyList(),
    val joinRequests: List<PendingJoinRequest> = emptyList(),
    val controlRequests: List<PendingControlRequest> = emptyList(),
    val checking: Boolean = false,
    val choosing: Boolean = false,
    /** This account already plays this title elsewhere: joining is the only way to watch here. */
    val requiresJoin: Boolean = false,
    /** The required join was declined, so nothing will play on this device. */
    val duplicateRefused: Boolean = false,
    val blocked: Boolean = false,
    val loading: Boolean = false,
    val transitioning: Boolean = false,
    val notice: WatchTogetherNotice? = null,
) {
    val inRoom: Boolean get() = members.isNotEmpty()
    val self: RoomMemberDto? get() = members.firstOrNull { it.id == selfId }
    val isHost: Boolean get() = self?.host == true
    val canControl: Boolean get() = self?.controller == true
    val available: Boolean
        get() = inRoom || peers.isNotEmpty() || joinRequests.isNotEmpty()
    val hasPending: Boolean
        get() = joinRequests.isNotEmpty() || controlRequests.isNotEmpty()
}

internal sealed interface WatchTogetherPlaybackEvent {
    data object Ready : WatchTogetherPlaybackEvent
    data class Changed(val seek: Boolean) : WatchTogetherPlaybackEvent
}

internal interface WatchTogetherPlayer {
    val events: Flow<WatchTogetherPlaybackEvent>
    val positionMs: Long
    val paused: Boolean
    val playbackRate: Double
    val isLive: Boolean

    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun setPlaybackRate(rate: Double)
}

internal interface WatchTogetherHub {
    val commands: Flow<SessionCommandDto>
    val selfId: String?
    val direct: Boolean

    suspend fun intent(): WatchIntentResponse?
    suspend fun requestJoin(peerId: String)
    suspend fun answerJoin(requestId: String, accept: Boolean)
    suspend fun requestControl()
    suspend fun grantControl(peerId: String, grant: Boolean)
    suspend fun setControl(targetId: String, grant: Boolean)
    suspend fun kick(targetId: String)
    suspend fun setRoomAudio(audioTrackIndex: Int)
    suspend fun ready(generation: Long)

    /** Tells the server this device declined a required join. */
    suspend fun watchAlone()
    suspend fun leave()
    suspend fun sendSync(state: SyncStateDto)
}

/**
 * Owns Android's watch-together state machine. The hub controller still owns
 * the lease; this layer only translates room commands into player and room actions.
 */
internal class WatchTogetherCoordinator(
    private val hub: WatchTogetherHub,
    private val scope: CoroutineScope,
    private val reloadAudio: suspend (audioIndex: Int, positionMs: Long) -> Unit,
    private val reloadAfterLeave: suspend (positionMs: Long) -> Unit = {},
    private val onRoomMembershipChanged: (inRoom: Boolean) -> Boolean = { false },
    private val sharesRoomRead: () -> Boolean = { true },
    private val onStartMedia: suspend (inRoom: Boolean) -> Unit = {},
    private val onProviderCapacity: suspend () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutableState = MutableStateFlow(WatchTogetherState())
    val state: StateFlow<WatchTogetherState> = mutableState.asStateFlow()

    private var player: WatchTogetherPlayer? = null
    private var playerEventsJob: Job? = null
    private var anchorJob: Job? = null
    private var reloadJob: Job? = null
    private var intentChecked = false
    private var noticeId = 0L
    private var commandLeaseId: String? = null
    private var lastAppliedCommandSequence: Long? = null
    private var lastApplied: AppliedSync? = null
    private var pendingSeek: PendingSeek? = null
    private var pendingSync: SyncStateDto? = null
    private var pendingForcedPaused: Boolean? = null
    private var pendingRoomAudio: PendingRoomAudio? = null
    private var roomMembershipReloadPending = false
    // This is the server-issued room barrier generation, not a device-local counter.
    private var barrierGeneration = 0L
    private var reloadRequested = false
    private var readySent = false
    private var readyFloorJob: Job? = null
    private var readyJob: Job? = null
    private var failOpenJob: Job? = null

    init {
        scope.launch {
            hub.commands.collect(::handleCommand)
        }
    }

    fun attachPlayer(next: WatchTogetherPlayer?) {
        playerEventsJob?.cancel()
        playerEventsJob = null
        player = next
        if (next == null) return
        if (mutableState.value.choosing || mutableState.value.loading) next.pause()
        playerEventsJob = scope.launch {
            next.events.collect { event ->
                when (event) {
                    WatchTogetherPlaybackEvent.Ready -> onPlaybackReady()
                    is WatchTogetherPlaybackEvent.Changed ->
                        publishSync(seek = event.seek, guarded = true)
                }
            }
        }
        val sync = pendingSync
        val forcedPaused = pendingForcedPaused
        pendingSync = null
        pendingForcedPaused = null
        sync?.let(::applySync)
        forcedPaused?.let(::applyForcedPlayback)
        pendingRoomAudio?.takeIf { mutableState.value.inRoom }?.let { pending ->
            pendingRoomAudio = null
            beginAudioBarrier(pending.audioIndex, pending.generation, resumePending = true)
        }
    }

    suspend fun checkIntent(force: Boolean = false) {
        adoptCurrentLease()
        if (intentChecked && !force) return
        intentChecked = true
        mutate {
            copy(
                peers = if (force) emptyList() else peers,
                checking = true,
                choosing = false,
                blocked = false,
            )
        }
        var startSolo = false
        var atCapacity = false
        try {
            // Match the web client: hold media for the preflight, but fail open
            // after a bounded wait so an unhealthy intent endpoint cannot hang play.
            val intent = withTimeoutOrNull(INTENT_TIMEOUT_MS) { hub.intent() }
            if (intent == null) {
                mutate { copy(checking = false) }
                startSolo = true
            } else {
                // A required join can only be satisfied by this account's own devices; another
                // user's session is not the thing blocking us and offering it would mislead.
                val offered = if (intent.requiresJoin) {
                    intent.sameContent.filter { it.sameAccount }
                } else {
                    intent.sameContent
                }
                val choosing = offered.isNotEmpty()
                mutate {
                    copy(
                        selfId = hub.selfId,
                        peers = offered,
                        checking = false,
                        choosing = choosing,
                        requiresJoin = intent.requiresJoin,
                        duplicateRefused = false,
                        blocked = intent.full,
                    )
                }
                if (choosing) {
                    player?.pause()
                } else if (intent.full) {
                    atCapacity = true
                } else {
                    startSolo = true
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            mutate { copy(checking = false) }
            startSolo = true
        }
        if (atCapacity) onProviderCapacity()
        if (startSolo) onStartMedia(false)
    }

    fun watchAlone() {
        val current = mutableState.value
        mutate { copy(choosing = false) }
        if (current.requiresJoin) {
            scope.launch {
                mutate { copy(duplicateRefused = false, transitioning = true) }
                try {
                    // The duplicate can disappear after intent was checked. A successful
                    // authoritative check therefore admits solo playback; only the typed
                    // duplicate response is a refusal.
                    hub.watchAlone()
                    mutate { copy(requiresJoin = false) }
                    onStartMedia(false)
                    player?.play()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: HubDuplicatePlaybackException) {
                    duplicatePlaybackRefused()
                } catch (_: Throwable) {
                    mutate { copy(choosing = true) }
                    showNotice(WatchTogetherNoticeKind.ACTION_FAILED)
                } finally {
                    mutate { copy(transitioning = false) }
                }
            }
            return
        }
        if (current.blocked) {
            scope.launch { onProviderCapacity() }
        } else {
            scope.launch {
                onStartMedia(false)
                player?.play()
            }
        }
    }

    /** The server deleted this lease; only leaving and starting a new lease can continue. */
    fun duplicatePlaybackRefused() {
        mutate {
            copy(
                peers = emptyList(),
                choosing = false,
                requiresJoin = true,
                duplicateRefused = true,
                transitioning = false,
            )
        }
    }

    suspend fun askToJoin(peerId: String) {
        // Before the call, not after: a join between two of this account's own devices is
        // admitted without approval, so room-state can arrive while requestJoin is still
        // in flight and would otherwise be overwritten by a stale transitioning=true.
        mutate { copy(transitioning = true, choosing = false) }
        try {
            hub.requestJoin(peerId)
        } catch (cancelled: CancellationException) {
            restoreJoinChoiceAfterFailure()
            throw cancelled
        } catch (_: Throwable) {
            if (restoreJoinChoiceAfterFailure()) {
                showNotice(WatchTogetherNoticeKind.ACTION_FAILED)
            }
        }
    }

    /** False when room-state already proved that the request reached the server. */
    private fun restoreJoinChoiceAfterFailure(): Boolean {
        if (mutableState.value.inRoom) return false
        mutate { copy(transitioning = false, choosing = peers.isNotEmpty()) }
        return true
    }

    suspend fun answerJoin(requestId: String, accept: Boolean) = action {
        hub.answerJoin(requestId, accept)
        mutate {
            copy(joinRequests = joinRequests.filterNot { it.requestId == requestId })
        }
    }

    suspend fun requestControl() = action {
        hub.requestControl()
    }

    suspend fun answerControl(peerId: String, grant: Boolean) = action {
        hub.grantControl(peerId, grant)
        mutate {
            copy(controlRequests = controlRequests.filterNot { it.peerId == peerId })
        }
    }

    suspend fun setControl(targetId: String, grant: Boolean) = action {
        hub.setControl(targetId, grant)
    }

    suspend fun kick(targetId: String) = action {
        hub.kick(targetId)
    }

    suspend fun selectRoomAudio(audioTrackIndex: Int): Boolean {
        val current = mutableState.value
        if (!current.inRoom || !current.canControl) return false
        action { hub.setRoomAudio(audioTrackIndex) }
        return true
    }

    suspend fun leave() {
        val currentPlayer = player
        val mustReload = currentPlayer != null && !hub.direct
        val resumeAfterReload = mustReload && currentPlayer?.paused == false
        val positionMs = currentPlayer?.positionMs?.coerceAtLeast(0) ?: 0
        if (mustReload) currentPlayer?.pause()
        mutate { copy(transitioning = true) }
        try {
            hub.leave()
            onRoomMembershipChanged(false)
            resetRoom()
            if (mustReload) {
                reloadAfterLeave(positionMs)
                if (resumeAfterReload) currentPlayer?.play()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            if (resumeAfterReload) currentPlayer?.play()
            showNotice(WatchTogetherNoticeKind.ACTION_FAILED)
        } finally {
            mutate { copy(transitioning = false) }
        }
    }

    fun dismissNotice(id: Long) {
        mutate {
            if (notice?.id == id) copy(notice = null) else this
        }
    }

    internal suspend fun handleCommand(command: SessionCommandDto) {
        if (!command.isProtocolCommand() || !adoptCurrentLease()) return
        val sequence = command.sequence ?: return
        if (lastAppliedCommandSequence?.let { sequence <= it } == true) return
        lastAppliedCommandSequence = sequence
        when (command.type) {
            "pause" -> applyForcedPlayback(paused = true)
            "play" -> applyForcedPlayback(paused = false)
            "message" -> command.text?.takeIf(String::isNotBlank)?.let {
                showNotice(WatchTogetherNoticeKind.ADMIN_MESSAGE, it)
            }
            "sync" -> command.sync?.let(::applySync)
            "join-request" -> handleJoinRequest(command)
            "join-response" -> {
                mutate { copy(transitioning = false) }
                showNotice(
                    if (command.accepted == true) {
                        WatchTogetherNoticeKind.JOINED
                    } else {
                        WatchTogetherNoticeKind.JOIN_DECLINED
                    },
                )
                if (command.accepted != true) mutate { copy(choosing = peers.isNotEmpty()) }
            }
            "control-request" -> handleControlRequest(command)
            "control-response" -> showNotice(
                if (command.accepted == true) {
                    WatchTogetherNoticeKind.CONTROL_GRANTED
                } else {
                    WatchTogetherNoticeKind.CONTROL_DENIED
                },
            )
            "room-state" -> command.members?.let(::replaceRoster)
            "room-audio" -> {
                val audioIndex = command.audioIndex ?: return
                val generation = command.generation?.takeIf { it > 0 } ?: return
                beginAudioBarrier(audioIndex, generation)
            }
            "room-go" -> command.generation?.takeIf { it > 0 }?.let(::finishAudioBarrier)
            "room-ended" -> roomEnded()
        }
    }

    private fun handleJoinRequest(command: SessionCommandDto) {
        val peerId = command.peerId ?: return
        val requestId = command.requestId ?: return
        val request = PendingJoinRequest(
            peerId = peerId,
            peerName = command.peerName.orEmpty(),
            requestId = requestId,
            quiet = command.quiet,
        )
        mutate {
            if (joinRequests.any { it.requestId == requestId }) {
                this
            } else {
                copy(joinRequests = joinRequests + request)
            }
        }
        if (!command.quiet) {
            showNotice(WatchTogetherNoticeKind.JOIN_REQUEST, request.peerName)
        }
    }

    private fun handleControlRequest(command: SessionCommandDto) {
        val peerId = command.peerId ?: return
        val request = PendingControlRequest(peerId, command.peerName.orEmpty())
        mutate {
            if (controlRequests.any { it.peerId == peerId }) {
                this
            } else {
                copy(controlRequests = controlRequests + request)
            }
        }
        showNotice(WatchTogetherNoticeKind.CONTROL_REQUEST, request.peerName)
    }

    private fun replaceRoster(roster: List<RoomMemberDto>) {
        val previous = mutableState.value
        val ids = roster.mapTo(mutableSetOf(), RoomMemberDto::id)
        mutate {
            copy(
                selfId = hub.selfId,
                members = roster,
                peers = emptyList(),
                choosing = false,
                // Being in the room is the answer to the prompt; neither it nor the
                // refusal should outlive the thing they were asking about.
                requiresJoin = if (roster.isNotEmpty()) false else requiresJoin,
                duplicateRefused = if (roster.isNotEmpty()) false else duplicateRefused,
                blocked = if (roster.isNotEmpty() && sharesRoomRead()) false else blocked,
                transitioning = false,
                joinRequests = joinRequests.filterNot { it.peerId in ids },
                controlRequests = controlRequests.filterNot { it.peerId in ids },
            )
        }
        val current = mutableState.value
        if (previous.inRoom != current.inRoom) {
            roomMembershipReloadPending = onRoomMembershipChanged(current.inRoom)
            if (current.inRoom) {
                scope.launch { onStartMedia(true) }
            }
        }
        updateAnchor()
        pendingRoomAudio?.takeIf { current.inRoom && player != null }?.let { pending ->
            pendingRoomAudio = null
            beginAudioBarrier(pending.audioIndex, pending.generation, resumePending = true)
        }
        if (current.isHost && roster.size > previous.members.size && roster.size >= 2) {
            publishSync(seek = true, guarded = false)
        }
    }

    private fun applyForcedPlayback(paused: Boolean) {
        val currentPlayer = player
        if (currentPlayer == null) {
            pendingForcedPaused = paused
            return
        }
        lastApplied = AppliedSync(
            currentPlayer.positionMs,
            paused,
            currentPlayer.playbackRate,
            clock(),
        )
        if (paused) currentPlayer.pause() else currentPlayer.play()
    }

    private fun applySync(sync: SyncStateDto) {
        val currentPlayer = player
        if (currentPlayer == null) {
            pendingSync = sync
            pendingForcedPaused = null
            return
        }
        lastApplied = AppliedSync(sync.positionMs, sync.paused, sync.rate, clock())
        if (sync.paused != currentPlayer.paused) {
            if (sync.paused) currentPlayer.pause() else currentPlayer.play()
        }
        if (sync.rate > 0 && abs(sync.rate - currentPlayer.playbackRate) > RATE_EPSILON) {
            currentPlayer.setPlaybackRate(sync.rate)
        }
        if (currentPlayer.isLive) return
        val pending = pendingSeek
        if (!sync.seek && pending != null &&
            clock() - pending.atMs < SETTLE_MS &&
            abs(sync.positionMs - pending.positionMs) > SETTLE_TOLERANCE_MS
        ) {
            return
        }
        val threshold = if (sync.seek) SEEK_SNAP_MS else ANCHOR_DRIFT_MS
        if (abs(currentPlayer.positionMs - sync.positionMs) > threshold) {
            currentPlayer.seekTo(sync.positionMs.coerceAtLeast(0))
        }
        if (sync.seek) pendingSeek = PendingSeek(sync.positionMs, clock())
    }

    private fun beginAudioBarrier(
        audioIndex: Int,
        generation: Long,
        resumePending: Boolean = false,
    ) {
        if (!mutableState.value.inRoom) {
            pendingRoomAudio = PendingRoomAudio(audioIndex, generation)
            return
        }
        if (generation == barrierGeneration && !resumePending) return
        // Direct playback has every audio track in-band, so there is nothing to
        // reload. Acknowledge anyway: the server only broadcasts room-go once
        // EVERY member reports ready, so staying silent would strand the peers
        // that do have to switch format.
        if (hub.direct && !roomMembershipReloadPending) {
            barrierGeneration = generation
            readyFloorJob?.cancel()
            readyJob?.cancel()
            reloadJob?.cancel()
            readySent = false
            requestReady(generation, requireLoading = false)
            return
        }
        val currentPlayer = player
        if (currentPlayer == null) {
            barrierGeneration = generation
            reloadRequested = false
            readySent = false
            readyFloorJob?.cancel()
            readyJob?.cancel()
            failOpenJob?.cancel()
            reloadJob?.cancel()
            pendingRoomAudio = PendingRoomAudio(audioIndex, generation)
            return
        }
        barrierGeneration = generation
        readyFloorJob?.cancel()
        readyJob?.cancel()
        reloadJob?.cancel()
        readySent = false
        prepareAudioBarrier(currentPlayer, generation)
        val positionMs = currentPlayer.positionMs
        if (hub.direct) {
            roomMembershipReloadPending = false
            reloadRequested = true
            readyFloorJob = scope.launch {
                delay(READY_FLOOR_MS)
                requestReady(generation)
            }
            return
        }
        reloadJob = scope.launch {
            try {
                reloadAudio(audioIndex, positionMs)
                if (generation == barrierGeneration) {
                    reloadRequested = true
                    readyFloorJob = scope.launch {
                        delay(READY_FLOOR_MS)
                        requestReady(generation)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                showNotice(WatchTogetherNoticeKind.ACTION_FAILED)
            }
        }
    }

    private fun prepareAudioBarrier(currentPlayer: WatchTogetherPlayer, generation: Long) {
        barrierGeneration = generation
        reloadRequested = false
        pendingRoomAudio = null
        readyFloorJob?.cancel()
        readyJob?.cancel()
        failOpenJob?.cancel()
        reloadJob?.cancel()
        mutate { copy(loading = true) }
        currentPlayer.pause()
        failOpenJob = scope.launch {
            delay(BARRIER_FAIL_OPEN_MS)
            if (generation == barrierGeneration && mutableState.value.loading) {
                reloadJob?.cancel()
                reloadJob = null
                readyJob?.cancel()
                mutate { copy(loading = false) }
                player?.play()
            }
        }
    }

    private fun onPlaybackReady() {
        val generation = barrierGeneration
        if (!mutableState.value.loading || !reloadRequested) return
        requestReady(generation)
    }

    private fun requestReady(generation: Long, requireLoading: Boolean = true) {
        if (generation != barrierGeneration || readySent ||
            (requireLoading && !mutableState.value.loading)
        ) return
        readySent = true
        readyJob?.cancel()
        readyJob = scope.launch {
            repeat(READY_MAX_ATTEMPTS) { attempt ->
                if (generation != barrierGeneration ||
                    (requireLoading && !mutableState.value.loading)
                ) return@launch
                if (requireLoading) player?.pause()
                try {
                    hub.ready(generation)
                    return@launch
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    if (attempt == READY_MAX_ATTEMPTS - 1) {
                        showNotice(WatchTogetherNoticeKind.ACTION_FAILED)
                        return@launch
                    }
                    delay(READY_RETRY_BASE_MS * (1L shl attempt))
                }
            }
        }
    }

    private fun finishAudioBarrier(generation: Long) {
        if (generation != barrierGeneration) return
        readyFloorJob?.cancel()
        readyJob?.cancel()
        failOpenJob?.cancel()
        reloadJob?.cancel()
        reloadJob = null
        reloadRequested = false
        readySent = false
        if (!mutableState.value.loading) return
        mutate { copy(loading = false) }
        player?.play()
    }

    private suspend fun roomEnded() {
        try {
            hub.leave()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            Unit
        }
        resetRoom()
        showNotice(WatchTogetherNoticeKind.ROOM_ENDED)
    }

    private fun resetRoom() {
        barrierGeneration = 0
        readyFloorJob?.cancel()
        readyJob?.cancel()
        failOpenJob?.cancel()
        reloadJob?.cancel()
        reloadJob = null
        reloadRequested = false
        readySent = false
        pendingRoomAudio = null
        pendingSync = null
        pendingForcedPaused = null
        roomMembershipReloadPending = false
        intentChecked = false
        mutate {
            copy(
                members = emptyList(),
                joinRequests = emptyList(),
                controlRequests = emptyList(),
                loading = false,
                transitioning = false,
            )
        }
        updateAnchor()
    }

    private fun adoptCurrentLease(): Boolean {
        val current = hub.selfId ?: return false
        val previous = commandLeaseId
        if (previous == current) return true
        commandLeaseId = current
        lastAppliedCommandSequence = null
        if (previous != null) {
            resetRoom()
            mutate {
                copy(
                    selfId = current,
                    peers = emptyList(),
                    checking = false,
                    choosing = false,
                    blocked = false,
                )
            }
        }
        return true
    }

    private fun publishSync(seek: Boolean, guarded: Boolean) {
        val current = mutableState.value
        val currentPlayer = player ?: return
        if (!current.inRoom || !current.canControl || current.loading) return
        val sync = SyncStateDto(
            positionMs = currentPlayer.positionMs.coerceAtLeast(0),
            paused = currentPlayer.paused,
            rate = currentPlayer.playbackRate.takeIf { it > 0 } ?: 1.0,
            seek = seek,
        )
        if (!guarded && !seek && isSeekSettling(currentPlayer)) return
        if (seek) pendingSeek = PendingSeek(sync.positionMs, clock())
        val applied = lastApplied
        if (guarded && applied != null &&
            clock() - applied.atMs < ECHO_GUARD_MS &&
            applied.paused == sync.paused &&
            abs(applied.rate - sync.rate) < RATE_EPSILON &&
            abs(applied.positionMs - sync.positionMs) < SETTLE_TOLERANCE_MS
        ) {
            return
        }
        scope.launch { hub.sendSync(sync) }
    }

    private fun isSeekSettling(currentPlayer: WatchTogetherPlayer): Boolean {
        val pending = pendingSeek ?: return false
        if (clock() - pending.atMs > SETTLE_MS ||
            abs(currentPlayer.positionMs - pending.positionMs) < SETTLE_TOLERANCE_MS
        ) {
            pendingSeek = null
            return false
        }
        return true
    }

    private suspend fun action(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            showNotice(WatchTogetherNoticeKind.ACTION_FAILED)
        }
    }

    private fun showNotice(kind: WatchTogetherNoticeKind, text: String? = null) {
        mutate { copy(notice = WatchTogetherNotice(++noticeId, kind, text)) }
    }

    private fun updateAnchor() {
        anchorJob?.cancel()
        anchorJob = null
        val current = mutableState.value
        if (!current.isHost || current.members.size < 2) return
        anchorJob = scope.launch {
            while (true) {
                delay(ANCHOR_MS)
                if (!mutableState.value.loading) {
                    publishSync(seek = false, guarded = false)
                }
            }
        }
    }

    private inline fun mutate(transform: WatchTogetherState.() -> WatchTogetherState) {
        mutableState.value = mutableState.value.transform()
    }

    private data class AppliedSync(
        val positionMs: Long,
        val paused: Boolean,
        val rate: Double,
        val atMs: Long,
    )

    private data class PendingSeek(
        val positionMs: Long,
        val atMs: Long,
    )

    private data class PendingRoomAudio(
        val audioIndex: Int,
        val generation: Long,
    )

    private companion object {
        const val ANCHOR_MS = 2_000L
        const val SEEK_SNAP_MS = 750L
        const val ANCHOR_DRIFT_MS = 4_000L
        const val SETTLE_MS = 4_000L
        const val SETTLE_TOLERANCE_MS = 1_500L
        const val ECHO_GUARD_MS = 800L
        const val INTENT_TIMEOUT_MS = 4_000L
        const val READY_FLOOR_MS = 4_000L
        const val READY_MAX_ATTEMPTS = 3
        const val READY_RETRY_BASE_MS = 500L
        const val BARRIER_FAIL_OPEN_MS = 12_000L
        const val RATE_EPSILON = 0.001
    }
}
