package com.buco7854.opentv.server

import com.sun.net.httpserver.HttpServer
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

class ImageProxyDeadlineTest {
    @Test
    fun stalledBodyTimesOutEverySingleFlightWaiterAndTheNextFetchCanSucceed() = testApplication {
        val fixture = Fixture()
        try {
            application {
                install(ContentNegotiation) { json() }
                routing { get("/image") { fixture.proxy.image(call, fixture.target) } }
            }
            val responses = withTimeout(5_000) {
                coroutineScope {
                    List(4) { async { client.get("/image") } }.awaitAll()
                }
            }
            assertTrue(responses.all { it.status == HttpStatusCode.GatewayTimeout })
            assertEquals(1, fixture.requests.get(), "Concurrent image requests must share one upstream read")
            assertEquals(0, fixture.flightCount())

            fixture.recover()
            assertContentEquals(IMAGE, client.get("/image").bodyAsBytes())
            assertContentEquals(IMAGE, client.get("/image").bodyAsBytes())
            assertEquals(2, fixture.requests.get(), "Only a complete image should enter the cache")
        } finally {
            fixture.close()
        }
    }

    @Test
    fun aTimedOutChunkedImageDeletesItsPartialDiskSpool() = testApplication {
        val fixture = Fixture(chunked = true)
        try {
            application {
                install(ContentNegotiation) { json() }
                routing { get("/image") { fixture.proxy.image(call, fixture.target) } }
            }
            val response = coroutineScope {
                val request = async { client.get("/image") }
                withTimeout(3_000) {
                    fixture.started.await()
                    while (fixture.diskFileCount() == 0L) delay(5)
                }
                withTimeout(3_000) { request.await() }
            }
            assertEquals(HttpStatusCode.GatewayTimeout, response.status)
            assertEquals(0L, fixture.diskFileCount(), "A cancelled spool must not leak its partial file")
            assertEquals(0, fixture.flightCount())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun callerCancellationStaysCancellationAndReleasesTheImageFlight() = testApplication {
        val fixture = Fixture(timeoutMs = 5_000)
        try {
            application {
                install(ContentNegotiation) { json() }
                routing {
                    get("/cancel") {
                        try {
                            withTimeout(150) { fixture.proxy.image(call, fixture.target) }
                        } catch (_: CancellationException) {
                            call.respond(HttpStatusCode.RequestTimeout)
                        }
                    }
                    get("/image") { fixture.proxy.image(call, fixture.target) }
                }
            }
            assertEquals(HttpStatusCode.RequestTimeout, withTimeout(2_000) { client.get("/cancel") }.status)
            assertEquals(0, fixture.flightCount())
            fixture.recover()
            assertContentEquals(IMAGE, withTimeout(2_000) { client.get("/image").bodyAsBytes() })
        } finally {
            fixture.close()
        }
    }

    @Test
    fun staleCachedBytesRemainAvailableWhenRevalidationBodyStalls() = testApplication {
        val fixture = Fixture()
        fixture.stalled.set(false)
        try {
            application {
                install(ContentNegotiation) { json() }
                routing { get("/image") { fixture.proxy.image(call, fixture.target) } }
            }
            assertContentEquals(IMAGE, client.get("/image").bodyAsBytes())
            fixture.expireCachedImage()
            fixture.stalled.set(true)
            val stale = withTimeout(3_000) { client.get("/image") }
            assertEquals(HttpStatusCode.OK, stale.status)
            assertContentEquals(IMAGE, stale.bodyAsBytes())
            assertEquals(2, fixture.requests.get())
            assertEquals(0, fixture.flightCount())
        } finally {
            fixture.close()
        }
    }

    private class Fixture(chunked: Boolean = false, timeoutMs: Long = 750) : AutoCloseable {
        val stalled = AtomicBoolean(true)
        val requests = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        private val release = CountDownLatch(1)
        private val executor = Executors.newCachedThreadPool()
        private val upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = this@Fixture.executor
            createContext("/poster") { exchange ->
                requests.incrementAndGet()
                val stall = stalled.get()
                exchange.responseHeaders.add("Content-Type", "image/png")
                exchange.sendResponseHeaders(200, if (chunked) 0 else if (stall) 1024 else IMAGE.size.toLong())
                runCatching {
                    exchange.responseBody.use { output ->
                        if (stall) {
                            output.write(IMAGE[0].toInt())
                            output.flush()
                            started.complete(Unit)
                            release.await(10, TimeUnit.SECONDS)
                        } else {
                            output.write(IMAGE)
                        }
                    }
                }
                exchange.close()
            }
            start()
        }
        val target = "http://127.0.0.1:${upstream.address.port}/poster"
        private val http = ServerHttp()
        private val connections = ProviderConnections()
        private val gate = StreamGate(connections)
        val proxy = StreamProxy(
            http, StreamCipher(Base64.getEncoder().encodeToString(ByteArray(32))), gate, timeoutMs,
        ) { 1 }
        private val cacheDirectory = field("imageCacheDirectory") as Path

        fun recover() {
            stalled.set(false)
            release.countDown()
        }

        fun diskFileCount(): Long = Files.list(cacheDirectory).use { it.count() }
        fun flightCount(): Int = (field("imageLoads") as Map<*, *>).size

        fun expireCachedImage() {
            val cached = (field("imageCache") as Map<*, *>)[target]!!
            cached.javaClass.getDeclaredField("cachedAtMs").apply { isAccessible = true }.setLong(cached, 0)
        }

        private fun field(name: String): Any? = StreamProxy::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.get(proxy)

        override fun close() {
            release.countDown()
            upstream.stop(0)
            executor.shutdownNow()
            proxy.close()
            gate.close()
            connections.closeAll()
            http.client.shutdownNow()
            cacheDirectory.toFile().deleteRecursively()
        }
    }

    private companion object {
        val IMAGE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
    }
}
