package com.buco7854.opentv.ui.player

import com.buco7854.opentv.AppGraph
import com.buco7854.opentv.contract.ClientCapabilitiesDto
import com.buco7854.opentv.contract.RemuxStartDto
import com.buco7854.opentv.contract.SessionCommandDto
import com.buco7854.opentv.contract.SyncStateDto
import com.buco7854.opentv.contract.WatchIntentResponse
import com.buco7854.opentv.data.net.Http
import com.buco7854.opentv.data.net.OkHttpTransport
import com.buco7854.opentv.hub.playback.HubClientPlaybackApi
import com.buco7854.opentv.hub.playback.HubPlaybackController
import com.buco7854.opentv.hub.playback.HubPlaybackSnapshot
import com.buco7854.opentv.hub.playback.HubPlaybackSocket
import com.buco7854.opentv.hub.playback.HubPlaybackState
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal interface HubPlayerPlayback : WatchTogetherHub {
    val state: StateFlow<HubPlaybackState>
    override val commands: Flow<SessionCommandDto> get() = emptyFlow()
    override val selfId: String? get() = null
    override val direct: Boolean
        get() = (state.value as? HubPlaybackState.Playing)?.direct == true

    suspend fun start(contentId: String, capabilities: ClientCapabilitiesDto): Boolean
    suspend fun startCatchUp(
        contentId: String,
        startMs: Long,
        durationMs: Long,
        capabilities: ClientCapabilitiesDto,
    ): Boolean
    suspend fun startMedia(inRoom: Boolean = false)
    suspend fun providerAtCapacity()
    suspend fun requestRemux(audioTrackIndex: Int): RemuxStartDto?
    suspend fun retry(): Boolean
    suspend fun onMediaRequestFailed(statusCode: Int)
    fun updateSnapshot(snapshot: HubPlaybackSnapshot)
    fun currentGrant(): String?
    fun setLiveRoom(inRoom: Boolean): Boolean
    fun sharesLiveRoomRead(): Boolean
    fun stop()

    override suspend fun intent(): WatchIntentResponse? = null
    override suspend fun requestJoin(peerId: String) = Unit
    override suspend fun answerJoin(requestId: String, accept: Boolean) = Unit
    override suspend fun requestControl() = Unit
    override suspend fun grantControl(peerId: String, grant: Boolean) = Unit
    override suspend fun setControl(targetId: String, grant: Boolean) = Unit
    override suspend fun kick(targetId: String) = Unit
    override suspend fun setRoomAudio(audioTrackIndex: Int) = Unit
    override suspend fun ready(generation: Long) = Unit
    override suspend fun leave() = Unit
    override suspend fun sendSync(state: SyncStateDto) = Unit
}

internal class DefaultHubPlayerPlayback(
    private val graph: AppGraph,
    private val hubId: Long,
    private val scope: CoroutineScope,
) : HubPlayerPlayback {
    private val snapshot = AtomicReference(HubPlaybackSnapshot(engine = "native", preparing = true))
    private val mutableState = kotlinx.coroutines.flow.MutableStateFlow<HubPlaybackState>(
        HubPlaybackState.Preparing,
    )
    override val state: StateFlow<HubPlaybackState> = mutableState
    private val mutableCommands = MutableSharedFlow<SessionCommandDto>(extraBufferCapacity = 64)
    override val commands: Flow<SessionCommandDto> = mutableCommands.asSharedFlow()
    override val selfId: String? get() = controller?.leaseId()

    private val lifecycle = Mutex()
    private val closed = AtomicBoolean(false)
    private var controller: HubPlaybackController? = null
    private var stateJob: Job? = null
    private var commandJob: Job? = null
    private var request: StartRequest? = null

    override suspend fun start(contentId: String, capabilities: ClientCapabilitiesDto): Boolean =
        lifecycle.withLock {
            check(request == null) { "Hub player owns only one initial target" }
            request = StartRequest.Content(contentId, capabilities)
            startLocked(requireNotNull(request))
        }

    override suspend fun startCatchUp(
        contentId: String,
        startMs: Long,
        durationMs: Long,
        capabilities: ClientCapabilitiesDto,
    ): Boolean =
        lifecycle.withLock {
            check(request == null) { "Hub player owns only one initial target" }
            request = StartRequest.CatchUp(contentId, startMs, durationMs, capabilities)
            startLocked(requireNotNull(request))
        }

    override suspend fun retry(): Boolean =
        lifecycle.withLock {
            if (closed.get()) return@withLock false
            val retryRequest = request ?: return@withLock false
            controller?.stop()
            stateJob?.cancel()
            commandJob?.cancel()
            startLocked(retryRequest)
        }

    override suspend fun startMedia(inRoom: Boolean) {
        if (closed.get()) return
        controller?.startMedia(inRoom)
    }

    override suspend fun providerAtCapacity() {
        if (closed.get()) return
        controller?.providerAtCapacity()
    }

    override suspend fun requestRemux(audioTrackIndex: Int): RemuxStartDto? =
        if (closed.get()) null else controller?.requestRemux(audioTrackIndex)

    override suspend fun onMediaRequestFailed(statusCode: Int) {
        controller?.onMediaRequestFailed(statusCode)
    }

    override fun updateSnapshot(snapshot: HubPlaybackSnapshot) {
        this.snapshot.set(snapshot)
    }

    override fun currentGrant(): String? = controller?.currentGrant()

    override fun setLiveRoom(inRoom: Boolean): Boolean =
        controller?.setLiveRoom(inRoom) == true

    override fun sharesLiveRoomRead(): Boolean =
        controller?.sharesLiveRoomRead() == true

    override suspend fun intent(): WatchIntentResponse? = controller?.watchIntent()

    override suspend fun requestJoin(peerId: String) {
        controller?.requestJoin(peerId)
    }

    override suspend fun answerJoin(requestId: String, accept: Boolean) {
        controller?.answerJoin(requestId, accept)
    }

    override suspend fun requestControl() {
        controller?.requestControl()
    }

    override suspend fun grantControl(peerId: String, grant: Boolean) {
        controller?.grantControl(peerId, grant)
    }

    override suspend fun setControl(targetId: String, grant: Boolean) {
        controller?.setControl(targetId, grant)
    }

    override suspend fun kick(targetId: String) {
        controller?.kick(targetId)
    }

    override suspend fun setRoomAudio(audioTrackIndex: Int) {
        controller?.setRoomAudio(audioTrackIndex)
    }

    override suspend fun ready(generation: Long) {
        controller?.ready(generation)
    }

    override suspend fun leave() {
        controller?.leaveRoom()
    }

    override suspend fun sendSync(state: SyncStateDto) {
        controller?.sendSync(state)
    }

    override fun stop() {
        if (!closed.compareAndSet(false, true)) return
        graph.applicationScope.launch {
            lifecycle.withLock {
                stateJob?.cancel()
                stateJob = null
                commandJob?.cancel()
                commandJob = null
                controller?.stop()
            }
        }
    }

    private suspend fun startLocked(request: StartRequest): Boolean {
        if (closed.get()) return false
        mutableState.value = HubPlaybackState.Preparing
        val client = graph.hubs.clientFor(hubId)
        if (client == null || !client.isSignedIn) {
            mutableState.value = HubPlaybackState.SignedOut
            return false
        }
        val api = HubClientPlaybackApi(client, OkHttpTransport())
        val next = HubPlaybackController(
            api = api,
            clock = System::currentTimeMillis,
            scope = scope,
            snapshotProvider = snapshot::get,
            socket = HubPlaybackSocket(api, Http.ok, scope),
        )
        controller = next
        stateJob = scope.launch {
            next.state.collect { mutableState.value = it }
        }
        commandJob = scope.launch {
            next.commands.collect { mutableCommands.emit(it) }
        }
        return when (request) {
            is StartRequest.Content -> next.start(request.contentId, request.capabilities)
            is StartRequest.CatchUp -> next.startCatchUp(
                contentId = request.contentId,
                catchupStartMs = request.startMs,
                catchupDurationMs = request.durationMs,
                capabilities = request.capabilities,
            )
        }
    }

    private sealed interface StartRequest {
        val capabilities: ClientCapabilitiesDto

        data class Content(
            val contentId: String,
            override val capabilities: ClientCapabilitiesDto,
        ) : StartRequest

        data class CatchUp(
            val contentId: String,
            val startMs: Long,
            val durationMs: Long,
            override val capabilities: ClientCapabilitiesDto,
        ) : StartRequest
    }
}
