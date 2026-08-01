package com.buco7854.opentv.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiveRelayLifecycleTest {
    @Test
    fun `dropping the final relay member closes its provider read and frees its seat immediately`() =
        testApplication {
            val listener = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
            val requestAccepted = CountDownLatch(1)
            val executor = Executors.newSingleThreadExecutor()
            val upstreamDisconnected = executor.submit<Boolean> {
                listener.accept().use { connection ->
                    val reader = connection.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    requestAccepted.countDown()
                    connection.soTimeout = 1_000
                    try {
                        reader.read() == -1
                    } catch (_: SocketTimeoutException) {
                        false
                    }
                }
            }
            val connections = ProviderConnections()
            val relay = LiveRelay(ServerHttp(), connections, { false })
            application {
                routing {
                    get("/") {
                        relay.stream(
                            call = call,
                            url = "http://127.0.0.1:${listener.localPort}/live.ts",
                            group = "room",
                            providerKey = "provider",
                            limit = 1,
                            sid = "lease",
                            capabilities = MediaCapabilities.BROWSER,
                            leaseGuard = {},
                        )
                    }
                }
            }

            try {
                coroutineScope {
                    val response = async(Dispatchers.Default) {
                        runCatching { client.get("/").bodyAsBytes() }
                    }
                    assertTrue(requestAccepted.await(2, TimeUnit.SECONDS))
                    assertEquals(1, connections.distinctStreams("provider", null))

                    relay.drop("lease")

                    assertTrue(
                        upstreamDisconnected.get(2, TimeUnit.SECONDS),
                        "an explicitly ended relay kept its provider request for the reconnect window",
                    )
                    assertEquals(0, connections.distinctStreams("provider", null))
                    response.await()
                }
            } finally {
                relay.close()
                listener.close()
                executor.shutdownNow()
                connections.closeAll()
            }
        }
}
