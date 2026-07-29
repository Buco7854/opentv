package com.buco7854.opentv.server

import com.sun.net.httpserver.HttpServer
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.net.URI
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SharedHlsCacheTest {
    private fun cipher() = StreamCipher(
        Base64.getEncoder().encodeToString(ByteArray(32) { (it + 1).toByte() }),
    )

    @Test
    fun `three room members share one manifest segment fetch and provider seat`() {
        val calls = ConcurrentHashMap<String, AtomicInteger>()
        val manifest = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXTINF:6,
            https://provider.invalid/credentials-must-not-escape.ts
            segment.ts
        """.trimIndent().encodeToByteArray()
        val segment = ByteArray(256 * 1024) { (it % 251).toByte() }
        val upstream = server { exchange ->
            val path = exchange.requestURI.path
            calls.computeIfAbsent(path) { AtomicInteger() }.incrementAndGet()
            Thread.sleep(100)
            val isManifest = path.endsWith(".m3u8") || path.endsWith("/manifest")
            val body = if (isManifest) manifest else segment
            exchange.responseHeaders.add(
                "Content-Type",
                when {
                    path.endsWith(".m3u8") -> "application/vnd.apple.mpegurl"
                    isManifest -> "application/octet-stream"
                    else -> "video/mp2t"
                },
            )
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        val connections = ProviderConnections()
        val gate = StreamGate(connections)
        val proxy = StreamProxy(ServerHttp(), cipher(), gate) { 1 }
        val group = "room-1"
        val members = setOf("one", "two", "three")
        val providerKey = providerKeyOf("http://127.0.0.1:${upstream.address.port}/live/index.m3u8")

        try {
            // The host was already playing solo when the room formed. Entering the explicit
            // shared path closes that read and makes room for the group's one logical seat.
            assertTrue(gate.admit("one", providerKey, 1))
            proxy.beginSharedHls(members)
            assertEquals(0, connections.distinctStreams(providerKey, null))

            testApplication {
                application {
                    routing {
                        get("/manifest/{member}") {
                            val member = assertNotNull(call.parameters["member"])
                            proxy.handleSharedHls(
                                call,
                                StreamCapability(
                                    "http://127.0.0.1:${upstream.address.port}/live/index.m3u8",
                                    member,
                                    hlsResource = true,
                                ),
                                "grant-$member",
                                group,
                                leaseGuard = {},
                                membershipGuard = {},
                                groupStillActive = { true },
                            )
                        }
                        get("/segment/{member}") {
                            val member = assertNotNull(call.parameters["member"])
                            proxy.handleSharedHls(
                                call,
                                StreamCapability(
                                    "http://127.0.0.1:${upstream.address.port}/live/segment.ts",
                                    member,
                                    hlsResource = true,
                                ),
                                "grant-$member",
                                group,
                                leaseGuard = {},
                                membershipGuard = {},
                                groupStillActive = { true },
                            )
                        }
                        get("/extensionless/{member}") {
                            val member = assertNotNull(call.parameters["member"])
                            proxy.handleSharedHls(
                                call,
                                StreamCapability(
                                    "http://127.0.0.1:${upstream.address.port}/live/manifest",
                                    member,
                                    hlsResource = true,
                                ),
                                "grant-$member",
                                group,
                                leaseGuard = {},
                                membershipGuard = {},
                                groupStillActive = { true },
                            )
                        }
                    }
                }

                val manifests = coroutineScope {
                    members.map { member ->
                        async { client.get("/manifest/$member") }
                    }.awaitAll()
                }
                manifests.forEach { response ->
                    assertEquals(HttpStatusCode.OK, response.status)
                    val served = response.bodyAsBytes().decodeToString()
                    assertFalse("provider.invalid" in served, served)
                    assertFalse("credentials-must-not-escape" in served, served)
                    assertTrue("/api/v1/shared-hls?u=" in served, served)
                }

                val extensionless = coroutineScope {
                    members.map { member ->
                        async { client.get("/extensionless/$member") }
                    }.awaitAll()
                }
                extensionless.forEach { response ->
                    assertEquals(HttpStatusCode.OK, response.status)
                    val served = response.bodyAsBytes().decodeToString()
                    assertFalse("provider.invalid" in served, served)
                    assertFalse("credentials-must-not-escape" in served, served)
                    assertTrue("/api/v1/shared-hls?u=" in served, served)
                }

                val segments = coroutineScope {
                    members.map { member ->
                        async { client.get("/segment/$member") }
                    }.awaitAll()
                }
                segments.forEach { response ->
                    assertEquals(HttpStatusCode.OK, response.status)
                    assertTrue(segment.contentEquals(response.bodyAsBytes()))
                }
            }

            assertEquals(1, calls["/live/index.m3u8"]?.get())
            assertEquals(1, calls["/live/manifest"]?.get())
            assertEquals(1, calls["/live/segment.ts"]?.get())
            assertEquals(1, connections.distinctStreams(providerKey, null))
        } finally {
            proxy.close()
            gate.close()
            connections.closeAll()
            upstream.stop(0)
        }
    }

    @Test
    fun `mid window resources stay playable while a drifting member cannot grow the cache`() =
        runBlocking {
            val calls = ConcurrentHashMap<String, AtomicInteger>()
            val segment = ByteArray(1024) { 7 }
            val upstream = server { exchange ->
                calls.computeIfAbsent(exchange.requestURI.path) { AtomicInteger() }.incrementAndGet()
                exchange.responseHeaders.add("Content-Type", "video/mp2t")
                exchange.sendResponseHeaders(200, segment.size.toLong())
                exchange.responseBody.use { it.write(segment) }
            }
            val connections = ProviderConnections()
            val gate = StreamGate(connections)
            val cache = SharedHlsCache(ServerHttp(), gate, { 1 })
            val group = "room-window"
            val base = "http://127.0.0.1:${upstream.address.port}/live"

            try {
                repeat(40) { index ->
                    cache.read(group, URI("$base/$index.ts"), null) { true }
                }
                val bounded = cache.stats(group)
                assertTrue(bounded.mediaEntries <= 24, bounded.toString())
                assertTrue(bounded.entries <= 32, bounded.toString())
                assertTrue(bounded.bytes <= 64L * 1024 * 1024, bounded.toString())

                // A joiner requesting the middle of the retained live window gets the cached
                // bytes, while an old drifting request is fetched once and merely evicts LRU data.
                val midCalls = calls["/live/30.ts"]?.get()
                assertTrue(
                    segment.contentEquals(
                        cache.read(group, URI("$base/30.ts"), null) { true }.bytes,
                    ),
                )
                assertEquals(midCalls, calls["/live/30.ts"]?.get())

                val beforeOld = calls["/live/0.ts"]?.get()
                cache.read(group, URI("$base/0.ts"), null) { true }
                assertEquals((beforeOld ?: 0) + 1, calls["/live/0.ts"]?.get())
                val afterDrift = cache.stats(group)
                assertTrue(afterDrift.mediaEntries <= 24, afterDrift.toString())
                assertTrue(afterDrift.bytes <= 64L * 1024 * 1024, afterDrift.toString())
            } finally {
                cache.close()
                gate.close()
                connections.closeAll()
                upstream.stop(0)
            }
        }

    @Test
    fun `entering shared HLS releases only solo proxy seats not per viewer transcodes`() {
        val connections = ProviderConnections()
        val gate = StreamGate(connections)
        val proxy = StreamProxy(ServerHttp(), cipher(), gate) { 2 }
        val provider = "provider"
        try {
            assertTrue(gate.admit("member", provider, 2))
            assertTrue(gate.admit(transcodeGateId("member"), provider, 2))

            proxy.beginSharedHls(setOf("member"))

            assertFalse(connections.isOpen("member"))
            assertTrue(connections.isOpen(transcodeGateId("member")))
            assertEquals(1, connections.distinctStreams(provider, null))
        } finally {
            proxy.close()
            gate.close()
            connections.closeAll()
        }
    }

    @Test
    fun `dropping the last room member closes a parked reader and releases its seat`() =
        runBlocking {
            val responseStarted = CountDownLatch(1)
            val finishHandler = CountDownLatch(1)
            val upstream = server { exchange ->
                exchange.responseHeaders.add("Content-Type", "video/mp2t")
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use { output ->
                    output.write(ByteArray(64 * 1024) { 3 })
                    output.flush()
                    responseStarted.countDown()
                    finishHandler.await(5, TimeUnit.SECONDS)
                    runCatching { output.write(ByteArray(64 * 1024) { 4 }) }
                }
            }
            val connections = ProviderConnections()
            val gate = StreamGate(connections)
            val cache = SharedHlsCache(ServerHttp(), gate, { 1 })
            val group = "room-reader"
            val url = "http://127.0.0.1:${upstream.address.port}/live/parked.ts"
            val read = async(Dispatchers.Default) {
                runCatching { cache.read(group, URI(url), null) { true } }
            }

            try {
                assertTrue(responseStarted.await(2, TimeUnit.SECONDS))
                withTimeout(2_000) {
                    while (cache.stats(group).readers != 1) delay(10)
                }
                assertEquals(1, connections.distinctStreams(providerKeyOf(url), null))

                cache.drop(group)

                assertFalse(cache.stats(group).active)
                assertEquals(0, cache.stats(group).readers)
                assertEquals(0, connections.distinctStreams(providerKeyOf(url), null))
                assertTrue(read.await().isFailure)
            } finally {
                finishHandler.countDown()
                cache.close()
                gate.close()
                connections.closeAll()
                upstream.stop(0)
            }
        }

    private fun server(
        handler: (com.sun.net.httpserver.HttpExchange) -> Unit,
    ): HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/", handler)
        start()
    }
}
