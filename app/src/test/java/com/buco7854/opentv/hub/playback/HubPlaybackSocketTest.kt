package com.buco7854.opentv.hub.playback

import com.buco7854.opentv.contract.HeartbeatResponseDto
import com.buco7854.opentv.contract.PlaybackCreateRequest
import com.buco7854.opentv.contract.PlaybackLeaseDto
import com.buco7854.opentv.contract.RemuxStartDto
import com.buco7854.opentv.contract.SessionCommandDto
import com.buco7854.opentv.contract.SessionHeartbeatDto
import com.buco7854.opentv.contract.SyncStateDto
import com.buco7854.opentv.contract.WebSocketAccessDto
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HubPlaybackSocketTest {

    @Test
    fun clientOnlyReadyEventIsNotAValidServerCommand() {
        assertFalse(SessionCommandDto(type = "ready", sequence = 1).isProtocolCommand())
    }

    @Test
    fun acceptThenImmediateCloseUsesIncreasingBoundedBackoff() = runTest {
        val server = MockWebServer()
        repeat(7) { server.enqueue(immediateClose()) }
        server.start()
        val scheduled = LinkedBlockingQueue<Long>()
        val api = SocketApi(server.url("/").toString())
        val socket = HubPlaybackSocket(
            api = api,
            client = OkHttpClient(),
            scope = backgroundScope,
            onReconnectScheduled = scheduled::add,
        )
        try {
            socket.start("lease-1", {}, {}, {})
            runCurrent()
            assertEquals("lease-1", api.takeAccessWithinTest())

            val expected = listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 15_000L)
            expected.forEachIndexed { index, delayMs ->
                assertEquals(delayMs, scheduled.takeWithinTest())
                advanceTimeBy(delayMs)
                runCurrent()
                assertEquals("lease-1", api.takeAccessWithinTest())
                assertEquals(index + 2, api.accessCalls.get())
            }
        } finally {
            socket.stop()
            server.shutdown()
        }
    }

    @Test
    fun connectionThatStaysOpenForThresholdResetsBackoff() = runTest {
        val server = MockWebServer()
        server.enqueue(immediateClose())
        val stableSocket = AtomicReference<WebSocket>()
        val stableOpen = CountDownLatch(1)
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        stableSocket.set(webSocket)
                        stableOpen.countDown()
                    }
                },
            ),
        )
        server.enqueue(immediateClose())
        server.start()
        val scheduled = LinkedBlockingQueue<Long>()
        val clientOpens = CountDownLatch(2)
        val api = SocketApi(server.url("/").toString())
        val socket = HubPlaybackSocket(
            api = api,
            client = OkHttpClient(),
            scope = backgroundScope,
            onReconnectScheduled = scheduled::add,
            onConnectionOpened = clientOpens::countDown,
        )
        try {
            socket.start("lease-1", {}, {}, {})
            runCurrent()
            assertEquals(1_000L, scheduled.takeWithinTest())
            advanceTimeBy(1_000)
            runCurrent()
            assertTrue(stableOpen.await(5, TimeUnit.SECONDS))
            assertTrue(clientOpens.await(5, TimeUnit.SECONDS))

            advanceTimeBy(5_000)
            runCurrent()
            stableSocket.get().close(1011, "unstable again")

            assertEquals(1_000L, scheduled.takeWithinTest())
        } finally {
            socket.stop()
            server.shutdown()
        }
    }

    @Test
    fun malformedCommandDoesNotResetBackoffOrReachTheController() = runTest {
        val server = MockWebServer()
        server.enqueue(immediateClose())
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send(
                            SERVER_JSON.encodeToString(
                                SessionCommandDto(type = "not-a-command", sequence = 99),
                            ),
                        )
                        webSocket.close(1011, "broken protocol")
                    }
                },
            ),
        )
        server.start()
        val scheduled = LinkedBlockingQueue<Long>()
        val delivered = AtomicInteger()
        val api = SocketApi(server.url("/").toString())
        val socket = HubPlaybackSocket(
            api = api,
            client = OkHttpClient(),
            scope = backgroundScope,
            onReconnectScheduled = scheduled::add,
        )
        try {
            socket.start("lease-1", { delivered.incrementAndGet() }, {}, {})
            runCurrent()
            assertEquals(1_000L, scheduled.takeWithinTest())

            advanceTimeBy(1_000)
            runCurrent()

            assertEquals(2_000L, scheduled.takeWithinTest())
            assertEquals(0, delivered.get())
        } finally {
            socket.stop()
            server.shutdown()
        }
    }

    @Test
    fun validCommandResetsBackoffBeforeTheFiveSecondThreshold() = runTest {
        val server = MockWebServer()
        server.enqueue(immediateClose())
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send(
                            SERVER_JSON.encodeToString(
                                SessionCommandDto(type = "pause", sequence = 1),
                            ),
                        )
                        webSocket.close(1011, "connection lost")
                    }
                },
            ),
        )
        server.start()
        val scheduled = LinkedBlockingQueue<Long>()
        val delivered = AtomicReference<SessionCommandDto?>()
        val api = SocketApi(server.url("/").toString())
        val socket = HubPlaybackSocket(
            api = api,
            client = OkHttpClient(),
            scope = backgroundScope,
            onReconnectScheduled = scheduled::add,
        )
        try {
            socket.start("lease-1", delivered::set, {}, {})
            runCurrent()
            assertEquals(1_000L, scheduled.takeWithinTest())

            advanceTimeBy(1_000)
            runCurrent()

            assertEquals(1_000L, scheduled.takeWithinTest())
            assertEquals(SessionCommandDto(type = "pause", sequence = 1), delivered.get())
        } finally {
            socket.stop()
            server.shutdown()
        }
    }

    @Test
    fun stopCancelsAPendingReconnectTimer() = runTest {
        val server = MockWebServer()
        server.enqueue(immediateClose())
        server.start()
        val scheduled = LinkedBlockingQueue<Long>()
        val api = SocketApi(server.url("/").toString())
        val socket = HubPlaybackSocket(
            api = api,
            client = OkHttpClient(),
            scope = backgroundScope,
            onReconnectScheduled = scheduled::add,
        )
        try {
            socket.start("lease-1", {}, {}, {})
            runCurrent()
            assertEquals(1_000L, scheduled.takeWithinTest())

            socket.stop()
            advanceTimeBy(60_000)
            runCurrent()

            assertEquals(1, api.accessCalls.get())
        } finally {
            socket.stop()
            server.shutdown()
        }
    }

    @Test
    fun terminalLeaseEndedCloseDoesNotReconnect() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.close(1000, "lease ended")
                    }
                },
            ),
        )
        server.start()
        val scheduled = LinkedBlockingQueue<Long>()
        val terminal = CountDownLatch(1)
        val api = SocketApi(server.url("/").toString())
        val socket = HubPlaybackSocket(
            api = api,
            client = OkHttpClient(),
            scope = backgroundScope,
            onReconnectScheduled = scheduled::add,
        )
        try {
            socket.start("lease-1", {}, terminal::countDown, {})
            runCurrent()

            assertTrue(terminal.await(5, TimeUnit.SECONDS))
            advanceTimeBy(60_000)
            runCurrent()

            assertEquals(1, api.accessCalls.get())
            assertFalse(scheduled.poll(100, TimeUnit.MILLISECONDS) != null)
        } finally {
            socket.stop()
            server.shutdown()
        }
    }

    @Test
    fun restartingForANewLeaseIgnoresTheOldSocketsCloseCallback() = runTest {
        val server = MockWebServer()
        val firstOpen = CountDownLatch(1)
        val secondOpen = CountDownLatch(1)
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        firstOpen.countDown()
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, reason)
                    }
                },
            ),
        )
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        secondOpen.countDown()
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, reason)
                    }
                },
            ),
        )
        server.start()
        val scheduled = LinkedBlockingQueue<Long>()
        val api = SocketApi(server.url("/").toString())
        val socket = HubPlaybackSocket(
            api = api,
            client = OkHttpClient(),
            scope = backgroundScope,
            onReconnectScheduled = scheduled::add,
        )
        try {
            socket.start("lease-1", {}, {}, {})
            runCurrent()
            assertTrue(firstOpen.await(5, TimeUnit.SECONDS))

            socket.start("lease-2", {}, {}, {})
            runCurrent()
            assertTrue(secondOpen.await(5, TimeUnit.SECONDS))

            assertEquals(null, scheduled.poll(500, TimeUnit.MILLISECONDS))
            assertEquals(2, api.accessCalls.get())
        } finally {
            socket.stop()
            server.shutdown()
        }
    }

    private fun immediateClose() =
        MockResponse().withWebSocketUpgrade(
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.close(1011, "accepted then closed")
                }
            },
        )

    private fun LinkedBlockingQueue<Long>.takeWithinTest(): Long =
        requireNotNull(poll(5, TimeUnit.SECONDS)) { "Reconnect was not scheduled" }

    private class SocketApi(
        override val baseUrl: String,
    ) : HubPlaybackApi {
        val accessCalls = AtomicInteger()
        private val accessLeases = LinkedBlockingQueue<String>()

        fun takeAccessWithinTest(): String =
            requireNotNull(accessLeases.poll(5, TimeUnit.SECONDS)) {
                "WebSocket access callback was not received"
            }

        override suspend fun webSocketAccess(leaseId: String): WebSocketAccessDto {
            accessCalls.incrementAndGet()
            accessLeases.add(leaseId)
            return WebSocketAccessDto("token", Long.MAX_VALUE)
        }

        override suspend fun createLease(request: PlaybackCreateRequest): PlaybackLeaseDto =
            error("Not used")

        override suspend fun heartbeat(
            leaseId: String,
            heartbeat: SessionHeartbeatDto,
        ): HeartbeatResponseDto = error("Not used")

        override suspend fun sync(leaseId: String, state: SyncStateDto) = error("Not used")

        override suspend fun refreshMediaGrant(leaseId: String): HubMediaGrant = error("Not used")

        override suspend fun startRemux(
            startUrl: String,
            audioTrackIndex: Int,
            timeshift: Boolean,
            mediaGrant: String,
        ): RemuxStartDto = error("Not used")

        override suspend fun stopRemux(
            leaseId: String,
            remuxId: String,
            mediaGrant: String,
        ) = error("Not used")

        override suspend fun endLease(leaseId: String) = error("Not used")
    }
}

private val SERVER_JSON = Json { encodeDefaults = true }
