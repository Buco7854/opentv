package com.buco7854.opentv.hub.playback

import com.buco7854.opentv.contract.ClientFrameDto
import com.buco7854.opentv.contract.SessionCommandDto
import com.buco7854.opentv.contract.SessionHeartbeatDto
import com.buco7854.opentv.contract.SyncStateDto
import com.buco7854.opentv.hub.HubEndpoints
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Per-lease command socket. A fresh short-lived token is minted immediately
 * before every connection attempt, including reconnects.
 */
class HubPlaybackSocket(
    private val api: HubPlaybackApi,
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
    private val json: Json = DEFAULT_JSON,
    private val onReconnectScheduled: (Long) -> Unit = {},
    private val onConnectionOpened: () -> Unit = {},
) {
    private val stopped = AtomicBoolean(true)
    private val runGeneration = AtomicLong()
    private val attempt = AtomicInteger()
    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var connected = false
    @Volatile private var reconnectJob: Job? = null
    @Volatile private var stabilityJob: Job? = null
    private var leaseId: String? = null
    private var onCommand: ((SessionCommandDto) -> Unit)? = null
    private var onTerminal: (() -> Unit)? = null
    private var onFailure: ((Throwable) -> Unit)? = null

    fun start(
        leaseId: String,
        onCommand: (SessionCommandDto) -> Unit,
        onTerminal: () -> Unit,
        onFailure: (Throwable) -> Unit,
    ) {
        stop()
        this.leaseId = leaseId
        this.onCommand = onCommand
        this.onTerminal = onTerminal
        this.onFailure = onFailure
        attempt.set(0)
        stopped.set(false)
        val generation = runGeneration.incrementAndGet()
        connect(generation)
    }

    fun sendHeartbeat(heartbeat: SessionHeartbeatDto): Boolean =
        send(ClientFrameDto(type = "heartbeat", heartbeat = heartbeat))

    fun sendSync(sync: SyncStateDto): Boolean =
        send(ClientFrameDto(type = "sync", sync = sync))

    fun stop() {
        stopped.set(true)
        runGeneration.incrementAndGet()
        reconnectJob?.cancel()
        reconnectJob = null
        stabilityJob?.cancel()
        stabilityJob = null
        connected = false
        webSocket?.close(1000, "client stopped")
        webSocket = null
    }

    private fun connect(generation: Long) {
        if (!isCurrent(generation)) return
        val id = leaseId ?: return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            try {
                val access = api.webSocketAccess(id)
                if (!isCurrent(generation)) return@launch
                val url = HubEndpoints.playbackSocket(api.baseUrl, id, access.token)
                val request = Request.Builder().url(url).build()
                webSocket = client.newWebSocket(request, listener(generation))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (!isCurrent(generation)) return@launch
                onFailure?.invoke(error)
                reconnectJob = null
                reconnect(generation)
            }
        }
    }

    private fun listener(generation: Long) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!isCurrent(generation)) {
                webSocket.close(1000, "client stopped")
                return
            }
            this@HubPlaybackSocket.webSocket = webSocket
            connected = true
            stabilityJob?.cancel()
            stabilityJob = scope.launch {
                delay(STABLE_CONNECTION_MS)
                markStable(webSocket, generation)
            }
            onConnectionOpened()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!isCurrent(generation)) return
            val command = runCatching {
                json.decodeFromString(SessionCommandDto.serializer(), text)
            }.getOrNull()?.takeIf(SessionCommandDto::isProtocolCommand) ?: return
            markStable(webSocket, generation)
            onCommand?.invoke(command)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            disconnected(webSocket, generation)
            if (!isCurrent(generation)) return
            if (code == 1000 && TERMINAL_REASON.containsMatchIn(reason)) {
                stop()
                onTerminal?.invoke()
            } else {
                reconnect(generation)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            disconnected(webSocket, generation)
            if (isCurrent(generation)) {
                onFailure?.invoke(t)
                reconnect(generation)
            }
        }
    }

    private fun disconnected(socket: WebSocket, generation: Long) {
        if (runGeneration.get() == generation && webSocket === socket) {
            webSocket = null
            connected = false
            stabilityJob?.cancel()
            stabilityJob = null
        }
    }

    private fun markStable(socket: WebSocket, generation: Long) {
        if (isCurrent(generation) && connected && webSocket === socket) {
            attempt.set(0)
            stabilityJob?.cancel()
            stabilityJob = null
        }
    }

    @Synchronized
    private fun reconnect(generation: Long) {
        if (!isCurrent(generation) || reconnectJob?.isActive == true) return
        val previousAttempt = attempt.getAndUpdate {
            (it + 1).coerceAtMost(MAX_RECONNECT_ATTEMPT)
        }
        val delayMs = (RECONNECT_BASE_MS *
            (1L shl previousAttempt.coerceAtMost(MAX_RECONNECT_ATTEMPT)))
            .coerceAtMost(RECONNECT_MAX_MS)
        reconnectJob = scope.launch {
            delay(delayMs)
            if (!isCurrent(generation)) return@launch
            reconnectJob = null
            connect(generation)
        }
        onReconnectScheduled(delayMs)
    }

    private fun isCurrent(generation: Long): Boolean =
        !stopped.get() && runGeneration.get() == generation

    private fun send(frame: ClientFrameDto): Boolean {
        val socket = webSocket
        if (!connected || socket == null) return false
        return socket.send(json.encodeToString(ClientFrameDto.serializer(), frame))
    }

    private companion object {
        const val RECONNECT_BASE_MS = 1_000L
        const val RECONNECT_MAX_MS = 15_000L
        const val MAX_RECONNECT_ATTEMPT = 4
        const val STABLE_CONNECTION_MS = 5_000L
        val TERMINAL_REASON = Regex("lease ended|revoked", RegexOption.IGNORE_CASE)
        val DEFAULT_JSON = Json { ignoreUnknownKeys = true }
    }
}

internal fun SessionCommandDto.isProtocolCommand(): Boolean {
    if (sequence?.let { it > 0 } != true) return false
    return when (type) {
        "pause", "play", "room-ended" -> true
        "message" -> text != null
        "sync" -> sync != null
        "join-request" -> peerId != null && requestId != null
        "join-response", "control-response" -> accepted != null
        "control-request" -> peerId != null
        "room-state" -> members != null
        "room-audio" -> audioIndex != null && generation?.let { it > 0 } == true
        "room-go" -> generation?.let { it > 0 } == true
        else -> false
    }
}
