package com.buco7854.opentv.hub.playback

import com.buco7854.opentv.contract.ClientCapabilitiesDto
import com.buco7854.opentv.contract.PlaybackCreateRequest
import com.buco7854.opentv.contract.PlaybackLeaseDto
import com.buco7854.opentv.contract.RemuxStartDto
import com.buco7854.opentv.contract.SessionCommandDto
import com.buco7854.opentv.contract.SessionHeartbeatDto
import com.buco7854.opentv.contract.SyncStateDto
import com.buco7854.opentv.contract.WatchIntentResponse
import com.buco7854.opentv.hub.HubCapacityException
import com.buco7854.opentv.hub.HubDuplicatePlaybackException
import com.buco7854.opentv.hub.HubForbiddenException
import com.buco7854.opentv.hub.HubGoneException
import com.buco7854.opentv.hub.HubNotFoundException
import com.buco7854.opentv.hub.HubUnauthorizedException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl

sealed interface HubPlaybackState {
    data object Preparing : HubPlaybackState

    /**
     * The lease and control runtime exist, but no provider media read has been
     * opened yet.
     */
    data object LeaseCreated : HubPlaybackState

    data class Playing(
        val target: String,
        val direct: Boolean,
        val grant: String,
        val audioTracks: List<String> = emptyList(),
        val selectedAudioTrackIndex: Int? = null,
    ) : HubPlaybackState

    data object Revoked : HubPlaybackState
    data object SignedOut : HubPlaybackState
    data object DuplicatePlayback : HubPlaybackState

    data class AtCapacity(
        val retryAfterMs: Long?,
    ) : HubPlaybackState

    data class Failed(
        val cause: Throwable,
    ) : HubPlaybackState
}

data class HubPlaybackSnapshot(
    val title: String = "",
    val kind: String = "live",
    val logo: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val paused: Boolean = false,
    val live: Boolean = false,
    val engine: String = "exoplayer",
    val direct: Boolean = false,
    val audioTranscoded: Boolean = false,
    val preparing: Boolean = false,
    val remuxId: String? = null,
)

/**
 * Owns one server playback lease. A controller is single-use so catch-up always
 * creates a fresh owner rather than mutating an existing lease.
 */
class HubPlaybackController(
    private val api: HubPlaybackApi,
    private val clock: () -> Long,
    private val scope: CoroutineScope,
    private val snapshotProvider: () -> HubPlaybackSnapshot,
    private val socket: HubPlaybackSocket? = null,
) {
    private val stateMutable = MutableStateFlow<HubPlaybackState>(HubPlaybackState.Preparing)
    val state: StateFlow<HubPlaybackState> = stateMutable.asStateFlow()

    private val commandsMutable = MutableSharedFlow<SessionCommandDto>(extraBufferCapacity = 64)
    val commands: Flow<SessionCommandDto> = commandsMutable.asSharedFlow()

    private val started = AtomicBoolean(false)
    private val mediaStarted = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    private val leaseLock = Any()
    private val leaseReleaseMutex = Mutex()
    private val grantMutex = Mutex()
    private val remuxMutex = Mutex()
    private val syncMutex = Mutex()
    @Volatile private var lease: PlaybackLeaseDto? = null
    @Volatile private var grant: HubMediaGrant? = null
    private var heartbeatJob: Job? = null
    private var rotationJob: Job? = null
    private var catchup = false
    private var remux: RemuxStartDto? = null
    private var grantReceivedAtMs = 0L
    private var grantRotationFailures = 0
    private var leaseReleased = false
    @Volatile private var liveRoom = false

    suspend fun start(
        contentId: String,
        capabilities: ClientCapabilitiesDto,
    ): Boolean =
        startLease(
            PlaybackCreateRequest(
                contentId = contentId,
                capabilities = capabilities,
            ),
        )

    suspend fun startCatchUp(
        contentId: String,
        catchupStartMs: Long,
        catchupDurationMs: Long,
        capabilities: ClientCapabilitiesDto,
    ): Boolean =
        startLease(
            PlaybackCreateRequest(
                contentId = contentId,
                mode = "catchup",
                catchupStartMs = catchupStartMs,
                catchupDurationMs = catchupDurationMs,
                capabilities = capabilities,
            ),
        )

    /**
     * Opens provider-backed media only after watch intent has been checked.
     * Supplying room membership on the first call keeps direct live playback
     * from briefly opening a solo stream before switching to shared HLS or the TS relay.
     */
    suspend fun startMedia(inRoom: Boolean = false) {
        if (lease == null || isTerminalOrStopped()) return
        if (!mediaStarted.compareAndSet(false, true)) return
        liveRoom = inRoom
        requestRemux(0)
    }

    suspend fun requestRemux(audioTrackIndex: Int): RemuxStartDto? {
        return remuxMutex.withLock { requestRemuxLocked(audioTrackIndex) }
    }

    private suspend fun requestRemuxLocked(audioTrackIndex: Int): RemuxStartDto? {
        val currentLease = lease ?: return null
        if (isTerminalOrStopped()) return null
        stateMutable.value = HubPlaybackState.Preparing
        return try {
            val next = api.startRemux(
                currentLease.remuxStartUrl,
                audioTrackIndex.coerceAtLeast(0),
                catchup,
                currentGrant() ?: currentLease.mediaGrant,
            )
            if (isTerminalOrStopped()) {
                ignoreCleanupFailure {
                    api.stopRemux(currentLease.id, next.id, currentGrant() ?: currentLease.mediaGrant)
                }
                return null
            }
            val previous = remux
            remux = next
            if (previous != null && previous.id != next.id) {
                ignoreCleanupFailure {
                    api.stopRemux(currentLease.id, previous.id, currentGrant() ?: currentLease.mediaGrant)
                }
            }
            if (isTerminalOrStopped()) return null
            stateMutable.value = HubPlaybackState.Playing(
                target = absoluteMediaUrl(next.playlistUrl),
                direct = false,
                grant = requireNotNull(currentGrant()),
                audioTracks = next.audioTracks,
                selectedAudioTrackIndex = next.audio,
            )
            next
        } catch (error: HubNotFoundException) {
            if (isTerminalOrStopped()) return null
            // 404 is not lease death. no_extra_tracks means direct play is the successful result.
            if (error.code == NO_EXTRA_TRACKS) {
                val previous = remux
                remux = null
                if (previous != null) {
                    ignoreCleanupFailure {
                        api.stopRemux(
                            currentLease.id,
                            previous.id,
                            currentGrant() ?: currentLease.mediaGrant,
                        )
                    }
                }
                if (isTerminalOrStopped()) return null
                playDirect(currentLease)
                null
            } else {
                fail(error)
                null
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            if (!isTerminalOrStopped()) handleOperationFailure(error)
            null
        }
    }

    suspend fun onMediaRequestFailed(statusCode: Int) {
        when (statusCode) {
            // The server uses 410 for an expired grant. A 403 is an authorization
            // outcome, so refreshing grants would only create an unbounded loop.
            403 -> becomeTerminal(HubPlaybackState.Revoked)
            410 -> becomeTerminal(HubPlaybackState.Revoked)
            409 -> becomeTerminal(HubPlaybackState.DuplicatePlayback)
            429 -> atCapacity(null)
            // A missing manifest or segment is recoverable and never means the lease died.
            404 -> Unit
        }
    }

    suspend fun sendSync(sync: SyncStateDto) {
        syncMutex.withLock {
            val id = lease?.id ?: return@withLock
            if (isTerminalOrStopped()) return@withLock
            if (socket?.sendSync(sync) != true) {
                try {
                    api.sync(id, sync)
                } catch (error: Throwable) {
                    handleControlFailure(error)
                }
            }
        }
    }

    fun currentGrant(): String? = grant?.token

    fun leaseId(): String? = lease?.id

    /** True only when this lease advertises a real shared transport for its live source. */
    fun sharesLiveRoomRead(): Boolean {
        val currentLease = lease ?: return false
        return if (currentLease.hasHlsSource()) {
            currentLease.sharedHlsUrl != null
        } else {
            currentLease.relayUrl != null
        }
    }

    fun setLiveRoom(inRoom: Boolean): Boolean {
        if (isTerminalOrStopped()) return false
        liveRoom = inRoom
        val currentLease = lease ?: return false
        val current = stateMutable.value as? HubPlaybackState.Playing ?: return false
        if (!current.direct) return false
        val target = if (inRoom) currentLease.roomLiveUrl() else currentLease.streamUrl
        val absolute = target?.let(::absoluteMediaUrl) ?: return false
        if (absolute == current.target) return false
        return stateMutable.compareAndSet(current, current.copy(target = absolute))
    }

    suspend fun watchIntent(): WatchIntentResponse? =
        withLease { api.watchIntent(it) }

    /**
     * Confirms independent-play admission before opening media. A same-account duplicate raises
     * HubDuplicatePlaybackException so the UI can render the server's typed refusal verbatim.
     */
    suspend fun watchAlone() {
        withLease { api.watchAlone(it) }
    }

    suspend fun requestJoin(peerId: String) {
        withLease { api.requestJoin(it, peerId) }
    }

    suspend fun answerJoin(requestId: String, accept: Boolean) {
        withLease { api.answerJoin(it, requestId, accept) }
    }

    suspend fun requestControl() {
        withLease { api.requestControl(it) }
    }

    suspend fun grantControl(peerId: String, grant: Boolean) {
        withLease { api.grantControl(it, peerId, grant) }
    }

    suspend fun setControl(targetId: String, grant: Boolean) {
        withLease { api.setControl(it, targetId, grant) }
    }

    suspend fun kick(targetId: String) {
        withLease { api.kick(it, targetId) }
    }

    suspend fun setRoomAudio(audioTrackIndex: Int) {
        withLease { api.setRoomAudio(it, audioTrackIndex.coerceAtLeast(0)) }
    }

    suspend fun ready(generation: Long) {
        withLease { api.ready(it, generation) }
    }

    suspend fun leaveRoom() {
        withLease { api.leaveRoom(it) }
    }

    suspend fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        cancelRuntime()
        releaseLease()
    }

    suspend fun providerAtCapacity() {
        if (!isTerminalOrStopped()) atCapacity(null)
    }

    private suspend fun startLease(request: PlaybackCreateRequest): Boolean {
        check(started.compareAndSet(false, true)) { "HubPlaybackController owns only one lease" }
        stateMutable.value = HubPlaybackState.Preparing
        catchup = request.mode == "catchup"
        try {
            val created = api.createLease(request)
            val endImmediately = synchronized(leaseLock) {
                if (stopped.get()) {
                    true
                } else {
                    lease = created
                    grant = HubMediaGrant(created.mediaGrant, created.mediaGrantExpiresAtMs)
                    grantReceivedAtMs = clock()
                    stateMutable.value = HubPlaybackState.LeaseCreated
                    startRuntime(created)
                    false
                }
            }
            if (endImmediately) {
                ignoreCleanupFailure { api.endLease(created.id) }
                return false
            }
            return true
        } catch (error: Throwable) {
            handleOperationFailure(error)
            return false
        }
    }

    private fun startRuntime(created: PlaybackLeaseDto) {
        scheduleGrantRotation()
        socket?.start(
            leaseId = created.id,
            onCommand = { commandsMutable.tryEmit(it) },
            onTerminal = { scope.launch { becomeTerminal(HubPlaybackState.Revoked) } },
            onFailure = { error -> scope.launch { handleControlFailure(error) } },
        )
        heartbeatJob = scope.launch {
            while (isActive && !isTerminalOrStopped()) {
                heartbeat(created.id)
                delay(HEARTBEAT_MS)
            }
        }
    }

    private suspend fun heartbeat(leaseId: String) {
        val beat = snapshotProvider().toDto(leaseId)
        if (socket?.sendHeartbeat(beat) == true) return
        try {
            api.heartbeat(leaseId, beat).commands.forEach { commandsMutable.emit(it) }
        } catch (error: Throwable) {
            handleControlFailure(error)
        }
    }

    private suspend fun rotateGrant() {
        val currentLease = lease ?: return
        if (isTerminalOrStopped()) return
        grantMutex.withLock {
            if (isTerminalOrStopped()) return@withLock
            try {
                grant = api.refreshMediaGrant(currentLease.id)
                grantReceivedAtMs = clock()
                grantRotationFailures = 0
                updatePlayingGrant()
                scheduleGrantRotation()
            } catch (error: Throwable) {
                handleControlFailure(error)
                if (!isTerminalOrStopped()) {
                    grantRotationFailures++
                    if (grantRotationFailures >= MAX_GRANT_ROTATION_FAILURES) {
                        fail(error)
                    } else {
                        scheduleGrantRotation(GRANT_RETRY_MS)
                    }
                }
            }
        }
    }

    private fun scheduleGrantRotation(delayMs: Long = grantRotationDelay()) {
        rotationJob?.cancel()
        rotationJob = scope.launch {
            delay(delayMs)
            rotateGrant()
        }
    }

    /**
     * A grant is issued for ten server minutes. Convert that server-side lifetime
     * once into a relative timer; never compare the absolute expiry to a drifting
     * device wall clock. The margin is 10%, clamped to 30..60 seconds.
     */
    private fun grantRotationDelay(): Long {
        val expiresAt = grant?.expiresAtMs ?: return GRANT_RETRY_MS
        val serverIssuedAt = expiresAt - MEDIA_GRANT_TTL_MS
        val serverLifetime = (expiresAt - serverIssuedAt).coerceAtLeast(MIN_GRANT_MARGIN_MS)
        val margin = (serverLifetime / 10).coerceIn(MIN_GRANT_MARGIN_MS, MAX_GRANT_MARGIN_MS)
        val localDeadline = grantReceivedAtMs + serverLifetime - margin
        return (localDeadline - clock()).coerceAtLeast(GRANT_RETRY_MS)
    }

    private fun updatePlayingGrant() {
        val token = currentGrant() ?: return
        stateMutable.update { current ->
            if (current is HubPlaybackState.Playing) {
                current.copy(grant = token)
            } else {
                current
            }
        }
    }

    private suspend fun playDirect(currentLease: PlaybackLeaseDto) {
        val streamUrl = if (liveRoom) currentLease.roomLiveUrl() else currentLease.streamUrl
        stateMutable.value = if (streamUrl != null) {
            HubPlaybackState.Playing(
                absoluteMediaUrl(streamUrl),
                direct = true,
                grant = requireNotNull(currentGrant()),
                audioTracks = emptyList(),
                selectedAudioTrackIndex = null,
            )
        } else {
            val error = IllegalStateException("Lease has no direct playback URL")
            fail(error)
            return
        }
    }

    private fun absoluteMediaUrl(url: String): String =
        if (url.startsWith("http://") || url.startsWith("https://")) {
            url
        } else {
            requireNotNull(api.baseUrl.toHttpUrl().resolve(url)) { "Invalid hub media URL" }.toString()
        }

    private fun PlaybackLeaseDto.roomLiveUrl(): String? =
        if (hasHlsSource()) {
            // A newer server advertises the untouched shared-HLS path explicitly. Against an
            // older server, keep direct-playing the solo HLS URL rather than sending HLS to the
            // raw-TS relay; capacity UI remains blocked because sharesLiveRoomRead() is false.
            sharedHlsUrl ?: streamUrl
        } else {
            relayUrl
        }

    private fun PlaybackLeaseDto.hasHlsSource(): Boolean =
        streamUrl
            ?.substringAfter('?', "")
            ?.split('&')
            ?.firstOrNull { it.startsWith("u=") }
            ?.substringAfter('=')
            ?.startsWith("h.") == true

    private suspend fun handleControlFailure(error: Throwable) {
        when (error) {
            is CancellationException -> throw error
            is HubGoneException -> becomeTerminal(HubPlaybackState.Revoked)
            is HubForbiddenException -> becomeTerminal(HubPlaybackState.Revoked)
            is HubUnauthorizedException -> becomeTerminal(HubPlaybackState.SignedOut)
            is HubDuplicatePlaybackException ->
                becomeTerminal(HubPlaybackState.DuplicatePlayback)
            is HubCapacityException -> atCapacity(error.retryAfterMs)
            is HubNotFoundException -> Unit
            // Anything else is a transport problem the retry loops already handle. Said
            // out loud because a silent branch here is what let an unclassified failure
            // pass for one worth retrying.
            else -> Unit
        }
    }

    private suspend fun handleOperationFailure(error: Throwable) {
        when (error) {
            is CancellationException -> throw error
            is HubGoneException -> becomeTerminal(HubPlaybackState.Revoked)
            is HubForbiddenException -> becomeTerminal(HubPlaybackState.Revoked)
            is HubUnauthorizedException -> becomeTerminal(HubPlaybackState.SignedOut)
            is HubDuplicatePlaybackException ->
                becomeTerminal(HubPlaybackState.DuplicatePlayback)
            is HubCapacityException -> atCapacity(error.retryAfterMs)
            else -> fail(error)
        }
    }

    private suspend fun becomeTerminal(terminal: HubPlaybackState) {
        if (stateMutable.value.isTerminal()) return
        stateMutable.value = terminal
        cancelRuntime()
        releaseLease()
    }

    private suspend fun cancelRuntime() {
        val currentJob = currentCoroutineContext()[Job]
        heartbeatJob?.takeUnless { it === currentJob }?.cancel()
        heartbeatJob = null
        rotationJob?.takeUnless { it === currentJob }?.cancel()
        rotationJob = null
        socket?.stop()
    }

    private suspend fun fail(error: Throwable) {
        stateMutable.value = HubPlaybackState.Failed(error)
        cancelRuntime()
        releaseLease()
    }

    private suspend fun atCapacity(retryAfterMs: Long?) {
        stateMutable.value = HubPlaybackState.AtCapacity(retryAfterMs)
        cancelRuntime()
        releaseLease()
    }

    private suspend fun releaseLease() {
        val id = synchronized(leaseLock) { lease?.id } ?: return
        leaseReleaseMutex.withLock {
            if (leaseReleased) return@withLock
            try {
                api.endLease(id)
                leaseReleased = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                Unit
            }
        }
    }

    private fun isTerminalOrStopped(): Boolean =
        stopped.get() ||
            stateMutable.value is HubPlaybackState.Revoked ||
            stateMutable.value is HubPlaybackState.SignedOut ||
            stateMutable.value is HubPlaybackState.DuplicatePlayback ||
            stateMutable.value is HubPlaybackState.AtCapacity ||
            stateMutable.value is HubPlaybackState.Failed

    private suspend fun ignoreCleanupFailure(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            Unit
        }
    }

    private suspend fun <T> withLease(block: suspend (String) -> T): T? {
        val id = lease?.id ?: return null
        if (isTerminalOrStopped()) return null
        return try {
            block(id)
        } catch (error: Throwable) {
            handleControlFailure(error)
            throw error
        }
    }

    private fun HubPlaybackSnapshot.toDto(id: String) = SessionHeartbeatDto(
        id = id,
        title = title,
        kind = kind,
        logo = logo,
        positionMs = positionMs,
        durationMs = durationMs,
        paused = paused,
        live = live,
        engine = engine,
        direct = direct,
        audioTranscoded = audioTranscoded,
        preparing = preparing,
        remuxId = remuxId,
    )

    private companion object {
        const val HEARTBEAT_MS = 3_000L
        const val MEDIA_GRANT_TTL_MS = 10 * 60_000L
        const val MIN_GRANT_MARGIN_MS = 30_000L
        const val MAX_GRANT_MARGIN_MS = 60_000L
        const val GRANT_RETRY_MS = 3_000L
        const val MAX_GRANT_ROTATION_FAILURES = 5
        const val NO_EXTRA_TRACKS = "no_extra_tracks"
    }
}

private fun HubPlaybackState.isTerminal(): Boolean =
    this is HubPlaybackState.Revoked ||
        this is HubPlaybackState.SignedOut ||
        this is HubPlaybackState.DuplicatePlayback ||
        this is HubPlaybackState.AtCapacity ||
        this is HubPlaybackState.Failed
