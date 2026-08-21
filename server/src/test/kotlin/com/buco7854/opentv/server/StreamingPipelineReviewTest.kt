package com.buco7854.opentv.server

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.buco7854.opentv.core.log.CoreLog
import com.buco7854.opentv.core.model.Playlist
import com.buco7854.opentv.core.repo.AccountRepository
import com.buco7854.opentv.core.xtream.XtreamApi
import com.buco7854.opentv.data.createRoomStorage
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import com.sun.net.httpserver.HttpServer
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamingPipelineReviewTest {

    @Test
    fun `connection limits match an exact Xtream username path segment`() = runTest {
        val root = Files.createTempDirectory("connection-limits")
        val storage = createRoomStorage(root.resolve("catalog.db").toString())
        try {
            storage.playlists.insert(
                Playlist(
                    name = "Short username",
                    url = null,
                    xtreamBase = "https://provider.example:443",
                    xtreamUser = "ann",
                    xtreamPass = "first",
                )
            )
            storage.playlists.insert(
                Playlist(
                    name = "Stale password for same username",
                    url = null,
                    xtreamBase = "https://provider.example:443",
                    xtreamUser = "joann",
                    xtreamPass = "old-password",
                )
            )
            storage.playlists.insert(
                Playlist(
                    name = "Actual provider account",
                    url = null,
                    xtreamBase = "https://provider.example:443",
                    xtreamUser = "joann",
                    xtreamPass = "second",
                )
            )
            val account = AccountRepository(
                XtreamApi { url ->
                    val limit = when {
                        "password=second" in url -> 7
                        "username=joann" in url -> 3
                        else -> 1
                    }
                    """{"user_info":{"auth":"1","status":"Active","max_connections":"$limit"}}"""
                },
                CoreLog { _, _ -> },
            )
            val limits = ProviderConnectionLimits(storage, account, fallback = 2)

            assertEquals(
                7,
                limits.forUrl("https://provider.example:443/live/joann/second/42.ts"),
            )
        } finally {
            storage.close()
            deleteTree(root)
        }
    }

    @Test
    fun `concurrent viewers cannot both win the provider's final seat`() {
        val connections = ProviderConnections()
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(16)
        try {
            val results = (0 until 32).map { index ->
                executor.submit<Boolean> {
                    start.await()
                    connections.tryOpenStream(
                        "viewer-$index",
                        "provider",
                        "stream-$index",
                        1,
                    ) {}
                }
            }
            start.countDown()
            assertEquals(1, results.count { it.get(2, TimeUnit.SECONDS) })
            assertEquals(1, connections.distinctStreams("provider", null))
        } finally {
            executor.shutdownNow()
            connections.closeAll()
        }
    }

    @Test
    fun `an idle reaper snapshot cannot release a stream revived by a request`() {
        val now = AtomicLong(0)
        val connections = ProviderConnections(ServerClock(now::get))
        val gate = StreamGate(connections, ServerClock(now::get))
        try {
            assertTrue(gate.admit("viewer", "provider", 1))
            val staleSnapshot = now.get()
            now.set(25_000)
            gate.touch("viewer")

            gate.releaseIfStillIdle("viewer", staleSnapshot)

            assertTrue(connections.isOpen("viewer"), "stale reaper decision removed a live seat")
            assertEquals(1, connections.distinctStreams("provider", null))
        } finally {
            gate.close()
            connections.closeAll()
        }
    }

    @Test
    fun `a remux launch failure releases the provider seat`() = testApplication {
        val connections = ProviderConnections()
        val runner = MediaProcessRunner { request ->
            if (request.command.first() == "ffprobe") {
                Files.writeString(request.stdoutFile, playableProbe)
                MemoryProcess()
            } else {
                throw IllegalStateException("ffmpeg failed to start")
            }
        }
        val remux = RemuxService(ServerHttp(), connections, processRunner = runner)
        val result = remux.start(
            "https://provider.example/movie.mkv",
            0,
            MediaCapabilities.BROWSER,
            false,
            1,
            "viewer",
            emptySet(),
        )
        application {
            routing {
                get("/") {
                    remux.segment(result.id, 0, call)
                }
            }
        }

        try {
            client.get("/")
            assertFalse(
                connections.isOpen(result.id),
                "a failed ProcessBuilder.start must not strand the reserved provider seat",
            )
        } finally {
            remux.close()
            connections.closeAll()
        }
    }

    @Test
    fun `remux failures never return provider user info credentials`() = testApplication {
        val connections = ProviderConnections()
        val source =
            "https://secret-user:secret-pass@provider.example/movie/secret-user/secret-pass/1.mkv"
        val runner = MediaProcessRunner { request ->
            if (request.command.first() == "ffprobe") {
                Files.writeString(request.stdoutFile, playableProbe)
            } else {
                Files.writeString(request.appendStderrFile, "ffmpeg failed to read $source")
            }
            MemoryProcess()
        }
        val remux = RemuxService(ServerHttp(), connections, processRunner = runner)
        try {
            val result = remux.start(
                source,
                0,
                MediaCapabilities.BROWSER,
                false,
                2,
                "viewer",
                emptySet(),
            )
            application {
                routing {
                    get("/") { remux.segment(result.id, 0, call) }
                }
            }

            val response = client.get("/")
            assertEquals(HttpStatusCode.BadGateway, response.status)
            val body = response.bodyAsText()
            assertFalse("secret-user" in body, body)
            assertFalse("secret-pass" in body, body)
        } finally {
            remux.close()
            connections.closeAll()
        }
    }

    @Test
    fun `timeshift and seekable remuxes never share one session`() {
        val connections = ProviderConnections()
        val runner = MediaProcessRunner { request ->
            request.stdoutFile?.let { Files.writeString(it, playableProbe) }
            MemoryProcess()
        }
        val remux = RemuxService(ServerHttp(), connections, processRunner = runner)
        try {
            val seekable = remux.start(
                "https://provider.example/movie.mkv", 0, MediaCapabilities.BROWSER,
                false, 2, "viewer", emptySet(),
            )
            val timeshift = remux.start(
                "https://provider.example/movie.mkv", 0, MediaCapabilities.BROWSER,
                true, 2, "viewer", emptySet(),
            )
            assertTrue(seekable.id != timeshift.id)
        } finally {
            remux.close()
            connections.closeAll()
        }
    }

    @Test
    fun `a prepared remux adopts a reduced provider connection limit`() {
        val connections = ProviderConnections()
        val runner = MediaProcessRunner { request ->
            request.stdoutFile?.let { Files.writeString(it, playableProbe) }
            MemoryProcess()
        }
        val remux = RemuxService(ServerHttp(), connections, processRunner = runner)
        try {
            val first = remux.start(
                "https://provider.example/movie.mkv", 0, MediaCapabilities.BROWSER,
                false, 4, "viewer", emptySet(),
            )
            val reused = remux.start(
                "https://provider.example/movie.mkv", 0, MediaCapabilities.BROWSER,
                false, 1, "viewer", emptySet(),
            )
            assertEquals(first.id, reused.id)
            assertEquals(1, remux.diagnostics(reused.id)?.connectionLimit)
        } finally {
            remux.close()
            connections.closeAll()
        }
    }

    @Test
    fun `an out of range remux segment is refused before opening a provider connection`() =
        testApplication {
            val connections = ProviderConnections()
            val runner = MediaProcessRunner { request ->
                if (request.command.first() == "ffprobe") {
                    Files.writeString(request.stdoutFile, playableProbe)
                }
                MemoryProcess()
            }
            val remux = RemuxService(ServerHttp(), connections, processRunner = runner)
            val result = remux.start(
                "https://provider.example/movie.mkv",
                0,
                MediaCapabilities.BROWSER,
                false,
                1,
                "viewer",
                emptySet(),
            )
            application {
                routing {
                    get("/") {
                        remux.segment(result.id, Int.MAX_VALUE, call)
                    }
                }
            }

            try {
                assertEquals(HttpStatusCode.NotFound, client.get("/").status)
                assertFalse(connections.isOpen(result.id))
            } finally {
                remux.close()
                connections.closeAll()
            }
        }

    @Test
    fun `a naturally completed remux is reaped and releases its provider seat immediately`() =
        testApplication {
            val connections = ProviderConnections()
            val processes = CopyOnWriteArrayList<MemoryProcess>()
            val runner = MediaProcessRunner { request ->
                if (request.command.first() == "ffprobe") {
                    Files.writeString(request.stdoutFile, playableProbe)
                    MemoryProcess()
                } else {
                    val directory = requireNotNull(request.workingDirectory)
                    Files.write(directory.resolve("init.mp4"), byteArrayOf(1))
                    Files.write(directory.resolve("main0.m4s"), byteArrayOf(2))
                    MemoryProcess().also(processes::add)
                }
            }
            val remux = RemuxService(ServerHttp(), connections, processRunner = runner)
            val result = remux.start(
                "https://provider.example/movie.mkv",
                0,
                MediaCapabilities.BROWSER,
                false,
                1,
                "viewer",
                emptySet(),
            )
            application {
                routing {
                    get("/") {
                        remux.segment(result.id, 0, call)
                    }
                }
            }

            try {
                assertEquals(HttpStatusCode.OK, client.get("/").status)
                withTimeout(1_000) {
                    while (connections.isOpen(result.id)) delay(10)
                }
                assertTrue(processes.single().timedWaits > 0)
            } finally {
                remux.close()
                connections.closeAll()
            }
        }

    @Test
    fun `a timed out media probe is reaped before its reservation is released`() {
        val root = Files.createTempDirectory("probe-review")
        val processes = CopyOnWriteArrayList<TimeoutProcess>()
        var reservationClosed = false
        val probe = MediaProbe(
            ServerHttp(),
            MediaProcessRunner { TimeoutProcess().also(processes::add) },
            root,
        )

        try {
            assertFailsWith<IllegalStateException> {
                probe.inspect("https://provider.example/movie.mkv") {
                    AutoCloseable { reservationClosed = true }
                }
            }
            assertTrue(processes.isNotEmpty())
            assertTrue(processes.all { it.destroyed })
            assertTrue(
                processes.all { it.timedWaits >= 2 },
                "a destroyed ffprobe was never waited on/reaped",
            )
            assertTrue(reservationClosed)
        } finally {
            deleteTree(root)
        }
    }

    @Test
    fun `an interrupted quick probe does not launch a second provider process`() {
        val root = Files.createTempDirectory("probe-interrupt-review")
        var starts = 0
        val process = InterruptingProcess()
        val probe = MediaProbe(
            ServerHttp(),
            MediaProcessRunner { request ->
                starts++
                Files.writeString(request.stdoutFile, playableProbe)
                if (starts == 1) process else MemoryProcess()
            },
            root,
        )
        try {
            assertFailsWith<InterruptedException> {
                probe.inspect("https://provider.example/movie.mkv")
            }
            assertEquals(1, starts)
            assertTrue(process.destroyed)
        } finally {
            Thread.interrupted()
            deleteTree(root)
        }
    }

    @Test
    fun `a cancelled quick probe does not launch a second provider process`() {
        val root = Files.createTempDirectory("probe-cancel")
        val starts = java.util.concurrent.atomic.AtomicInteger()
        val probe = MediaProbe(
            ServerHttp(),
            MediaProcessRunner {
                starts.incrementAndGet()
                throw CancellationException("request cancelled")
            },
            root,
        )
        try {
            assertFailsWith<CancellationException> {
                probe.inspect("https://provider.example/movie/user/pass/1.mp4")
            }
            assertEquals(1, starts.get(), "cancellation incorrectly launched the unbounded retry")
        } finally {
            deleteTree(root)
        }
    }

    @Test
    fun `an empty probe cannot be mistaken for a direct playable source`() {
        val connections = ProviderConnections()
        val runner = MediaProcessRunner { request ->
            request.stdoutFile?.let {
                Files.writeString(it, """{"streams":[],"format":{"duration":"120.0"}}""")
            }
            MemoryProcess()
        }
        val remux = RemuxService(ServerHttp(), connections, processRunner = runner)

        try {
            assertFailsWith<IllegalStateException> {
                remux.start(
                    "https://provider.example/not-media",
                    0,
                    MediaCapabilities.BROWSER,
                    false,
                    1,
                    "viewer",
                    emptySet(),
                )
            }
        } finally {
            remux.close()
            connections.closeAll()
        }
    }

    @Test
    fun `non finite and absurd segment plans are rejected`() {
        val root = Files.createTempDirectory("segment-plan-review")
        val probe = MediaProbe(ServerHttp(), MediaProcessRunner { MemoryProcess() }, root)
        try {
            assertFailsWith<IllegalArgumentException> {
                probe.segmentStarts(null, 2.0, Double.NaN)
            }
            assertFailsWith<IllegalArgumentException> {
                probe.segmentStarts(null, 2.0, 200_003.0)
            }
        } finally {
            deleteTree(root)
        }
    }

    @Test
    fun `remux playlists use protocol decimal points regardless of server locale`() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.FRANCE)
        try {
            val root = Files.createTempDirectory("playlist-locale-review")
            try {
                val session = remuxSession(root)
                val playlist = RemuxPlaylists.media(session)
                assertTrue("#EXTINF:2.500000," in playlist, playlist)
                assertFalse("#EXTINF:2,500000," in playlist, playlist)
            } finally {
                deleteTree(root)
            }
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `subtitle synchronization state stays bounded as sources are evicted`() {
        val root = Files.createTempDirectory("subtitle-lock-review")
        try {
            val store = SubtitleCueStore(root)
            repeat(600) { index ->
                store.merge(
                    "https://provider.example/movie/$index",
                    0,
                    "WEBVTT\n\n00:00:00.000 --> 00:00:01.000\ncue $index\n",
                )
            }
            val locks = SubtitleCueStore::class.java.getDeclaredField("locks").run {
                isAccessible = true
                get(store)
            }
            val count = when (locks) {
                is Map<*, *> -> locks.size
                is Array<*> -> locks.size
                else -> error("Unknown subtitle lock collection")
            }
            assertTrue(count <= 64, "evicted subtitle sources leaked $count lock objects")
        } finally {
            deleteTree(root)
        }
    }

    @Test
    fun `absolute urls hidden in HLS metadata are made opaque too`() {
        val cipher = testCipher()
        val connections = ProviderConnections()
        val gate = StreamGate(connections)
        val proxy = StreamProxy(ServerHttp(), cipher, gate) { 1 }
        val manifest = URI("https://provider.example/live/user/pass/index.m3u8")

        try {
            val rewritten = proxy.rewriteHls(
                """
                #EXTM3U
                #EXT-X-SESSION-DATA:DATA-ID="provider.help",VALUE="https://provider.example/live/user/pass/help"
                #EXT-X-SESSION-DATA:DATA-ID="provider.backup",VALUE="//provider.example/live/user/pass/backup"
                #EXTINF:6,
                segment.ts
                """.trimIndent(),
                manifest,
                "lease",
                "grant",
            )

            assertFalse("provider.example" in rewritten, rewritten)
            assertFalse("/live/user/pass/" in rewritten, rewritten)
        } finally {
            gate.close()
            connections.closeAll()
        }
    }

    @Test
    fun `stream proxy revalidates the lease while a long body is flowing`() {
        val upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        upstream.createContext("/") { exchange ->
            val body = ByteArray(256 * 1024) { 7 }
            exchange.responseHeaders.add("Content-Type", "video/mp2t")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        upstream.start()

        val connections = ProviderConnections()
        val gate = StreamGate(connections)
        val proxy = StreamProxy(ServerHttp(), testCipher(), gate) { 1 }
        var validations = 0
        val target = "http://127.0.0.1:${upstream.address.port}/stream.ts"

        try {
            testApplication {
                application {
                    routing {
                        get("/") {
                            proxy.handle(
                                call,
                                StreamCapability(target, "lease"),
                                "grant",
                            ) { validations++ }
                        }
                    }
                }
                assertEquals(256 * 1024, client.get("/").bodyAsBytes().size)
            }
            assertTrue(validations > 1, "the lease was checked only before the long response")
        } finally {
            proxy.drop("lease")
            gate.close()
            connections.closeAll()
            upstream.stop(0)
        }
    }

    @Test
    fun `dropping a proxy lease closes an upstream still waiting for response headers`() =
        testApplication {
            val listener = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
            val requestAccepted = CountDownLatch(1)
            val executor = Executors.newSingleThreadExecutor()
            val upstreamDisconnected = executor.submit<Boolean> {
                listener.accept().use { connection ->
                    val reader = connection.getInputStream()
                        .bufferedReader(Charsets.ISO_8859_1)
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
            val gate = StreamGate(connections)
            val proxy = StreamProxy(ServerHttp(), testCipher(), gate) { 1 }
            application {
                routing {
                    get("/") {
                        proxy.handle(
                            call,
                            StreamCapability(
                                "http://127.0.0.1:${listener.localPort}/stream.ts",
                                "lease",
                            ),
                            "grant",
                        ) {}
                    }
                }
            }

            try {
                coroutineScope {
                    val response = async(Dispatchers.Default) {
                        client.get("/").bodyAsBytes()
                    }
                    assertTrue(requestAccepted.await(2, TimeUnit.SECONDS))

                    response.cancelAndJoin()
                    proxy.drop("lease")

                    assertTrue(
                        upstreamDisconnected.get(2, TimeUnit.SECONDS),
                        "revoked proxy lease left the provider request physically open",
                    )
                }
            } finally {
                proxy.drop("lease")
                proxy.close()
                listener.close()
                executor.shutdownNow()
                gate.close()
                connections.closeAll()
            }
        }

    @Test
    fun `proxy connection failures do not log provider credentials`() = testApplication {
        val connections = ProviderConnections()
        val gate = StreamGate(connections)
        val proxy = StreamProxy(ServerHttp(), testCipher(), gate) { 1 }
        val logger = LoggerFactory.getLogger("opentv") as Logger
        val appender = ListAppender<ILoggingEvent>().also {
            it.start()
            logger.addAppender(it)
        }
        try {
            application {
                install(ContentNegotiation) { json() }
                routing {
                    get("/") {
                        proxy.handle(
                            call,
                            StreamCapability(
                                "http://secret-user:secret-pass@127.0.0.1:1/live/secret-user/secret-pass/1.ts",
                                "lease",
                            ),
                            "grant",
                        ) {}
                    }
                }
            }

            assertEquals(HttpStatusCode.BadGateway, client.get("/").status)
            proxy.rewriteHls(
                "#EXTM3U\nhttps://elsewhere.example/segment.ts",
                URI("http://secret-user:secret-pass@127.0.0.1:1/live/secret-user/secret-pass/index.m3u8"),
                "lease",
            )
            val logged = appender.list.joinToString("\n") { it.formattedMessage }
            assertFalse("secret-user" in logged, logged)
            assertFalse("secret-pass" in logged, logged)
        } finally {
            logger.detachAppender(appender)
            appender.stop()
            proxy.drop("lease")
            gate.close()
            connections.closeAll()
        }
    }

    @Test
    fun `an unsatisfied upstream range remains a range response`() {
        val upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        upstream.createContext("/") { exchange ->
            exchange.responseHeaders.add("Content-Range", "bytes */100")
            exchange.sendResponseHeaders(416, -1)
            exchange.close()
        }
        upstream.start()
        val connections = ProviderConnections()
        val gate = StreamGate(connections)
        val proxy = StreamProxy(ServerHttp(), testCipher(), gate) { 1 }
        val target = "http://127.0.0.1:${upstream.address.port}/movie.mp4"

        try {
            testApplication {
                application {
                    routing {
                        get("/") {
                            proxy.handle(call, StreamCapability(target, "lease"), "grant") {}
                        }
                    }
                }
                val response = client.get("/")
                assertEquals(HttpStatusCode.RequestedRangeNotSatisfiable, response.status)
                assertEquals("bytes */100", response.headers["Content-Range"])
            }
        } finally {
            proxy.drop("lease")
            gate.close()
            connections.closeAll()
            upstream.stop(0)
        }
    }

    @Test
    fun `malformed HLS is rejected rather than reflected with a provider url`() {
        val upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        upstream.createContext("/") { exchange ->
            val body = "broken https://provider.example/live/user/pass/segment.ts".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/vnd.apple.mpegurl")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        upstream.start()
        val connections = ProviderConnections()
        val gate = StreamGate(connections)
        val proxy = StreamProxy(ServerHttp(), testCipher(), gate) { 1 }
        val target = "http://127.0.0.1:${upstream.address.port}/index.m3u8"

        try {
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing {
                        get("/") {
                            proxy.handle(call, StreamCapability(target, "lease"), "grant") {}
                        }
                    }
                }
                val response = client.get("/")
                assertEquals(HttpStatusCode.BadGateway, response.status)
                assertFalse("provider.example" in response.bodyAsBytes().decodeToString())
            }
        } finally {
            proxy.drop("lease")
            gate.close()
            connections.closeAll()
            upstream.stop(0)
        }
    }

    @Test
    fun `audio transcoder revalidates while copying and drains its process`() = testApplication {
        val processes = CopyOnWriteArrayList<Process>()
        val commands = CopyOnWriteArrayList<List<String>>()
        val runner = MediaProcessRunner { request ->
            commands += request.command
            ProcessBuilder("sh", "-c", "head -c 262144 /dev/zero")
                .start()
                .also(processes::add)
        }
        val transcoder = AudioTranscoder(ServerHttp(), runner)
        var validations = 0
        application {
            routing {
                get("/") {
                    transcoder.stream(
                        "https://provider.example/live.ts",
                        call,
                        "lease",
                    ) { validations++ }
                }
            }
        }

        val bodySize = client.get("/").bodyAsBytes().size
        assertEquals(
            256 * 1024,
            bodySize,
            "validations=$validations processes=${processes.size} exit=${processes.singleOrNull()?.exitValue()}",
        )
        assertTrue(validations > 2, "only the two pre-response checks ran")
        assertFalse(processes.single().isAlive)
        val ffmpeg = commands.single()
        assertEquals("aac", ffmpeg[ffmpeg.indexOf("-c:a") + 1])
        assertEquals("192k", ffmpeg[ffmpeg.indexOf("-b:a") + 1])
        assertTrue(commands.none { it.first() == "ffprobe" })
    }

    @Test
    fun `audio rescue starts AAC directly instead of trusting another capability probe`() =
        testApplication {
            val commands = CopyOnWriteArrayList<List<String>>()
            val runner = MediaProcessRunner { request ->
                commands += request.command
                MemoryProcess(byteArrayOf(1))
            }
            val transcoder = AudioTranscoder(ServerHttp(), runner)
            application {
                routing {
                    get("/") {
                        transcoder.stream(
                            "https://provider.example/live.ts",
                            call,
                            "lease",
                        ) {}
                    }
                }
            }

            client.get("/").bodyAsBytes()
            val ffmpeg = commands.single()
            assertEquals("ffmpeg", ffmpeg.first())
            assertEquals("aac", ffmpeg[ffmpeg.indexOf("-c:a") + 1])
        }

    @Test
    fun `relay probe failure chooses safe AAC instead of an undecodable copy`() = testApplication {
        val commands = CopyOnWriteArrayList<List<String>>()
        val runner = MediaProcessRunner { request ->
            commands += request.command
            if (request.command.first() == "ffprobe") {
                request.stdoutFile?.let { Files.writeString(it, "") }
                MemoryProcess(exitCode = 1)
            } else {
                MemoryProcess(byteArrayOf(1))
            }
        }
        val connections = ProviderConnections()
        val relay = LiveRelay(ServerHttp(), connections, { true }, runner)
        application {
            routing {
                get("/") {
                    relay.stream(
                        call,
                        "https://provider.example/live.ts",
                        "room",
                        "provider.example",
                        1,
                        "lease",
                        MediaCapabilities.BROWSER,
                    ) {}
                }
            }
        }

        try {
            coroutineScope {
                val response = async { client.get("/").bodyAsBytes() }
                withTimeout(2_000) {
                    while (commands.none { it.first() == "ffmpeg" }) delay(10)
                }
                relay.close()
            response.await()
            }
            val ffmpeg = commands.first { it.first() == "ffmpeg" }
            assertEquals("aac", ffmpeg[ffmpeg.indexOf("-c:a") + 1])
            assertTrue(
                ffmpeg.windowed(2).contains(listOf("-map", "0:a:0?")),
                "the relay probed a:0 but allowed ffmpeg to auto-select a different audio track",
            )
        } finally {
            relay.close()
            connections.closeAll()
        }
    }

    @Test
    fun `closing a relay during its codec probe kills the probe without launching ffmpeg`() =
        testApplication {
            val probe = LatchingProcess()
            val commands = CopyOnWriteArrayList<List<String>>()
            val runner = MediaProcessRunner { request ->
                commands += request.command
                if (request.command.first() == "ffprobe") probe else MemoryProcess()
            }
            val connections = ProviderConnections()
            val relay = LiveRelay(ServerHttp(), connections, { true }, runner)
            application {
                routing {
                    get("/") {
                        relay.stream(
                            call, "https://provider.example/live.ts", "room", "provider", 1,
                            "lease", MediaCapabilities.BROWSER,
                        ) {}
                    }
                }
            }

            coroutineScope {
                val response = async { client.get("/").bodyAsBytes() }
                try {
                    withTimeout(1_000) { while (commands.isEmpty()) delay(10) }
                    relay.close()
                    withTimeout(1_000) { while (!probe.destroyed) delay(10) }
                    assertTrue(commands.none { it.first() == "ffmpeg" })
                } finally {
                    probe.destroyForcibly()
                    relay.close()
                    runCatching { response.await() }
                    connections.closeAll()
                }
            }
        }

    @Test
    fun `closing a relay cancels an upstream request still waiting for response headers`() =
        testApplication {
            val listener = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
            val requestAccepted = CountDownLatch(1)
            val executor = Executors.newSingleThreadExecutor()
            val upstreamDisconnected = executor.submit<Boolean> {
                listener.accept().use { connection ->
                    val reader = connection.getInputStream()
                        .bufferedReader(Charsets.ISO_8859_1)
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
                            call,
                            "http://127.0.0.1:${listener.localPort}/live.ts",
                            "room",
                            "provider",
                            1,
                            "lease",
                            MediaCapabilities.BROWSER,
                        ) {}
                    }
                }
            }

            try {
                coroutineScope {
                    val response = async(Dispatchers.Default) {
                        runCatching { client.get("/").bodyAsBytes() }
                    }
                    assertTrue(requestAccepted.await(2, TimeUnit.SECONDS))

                    relay.close()

                    assertTrue(
                        upstreamDisconnected.get(2, TimeUnit.SECONDS),
                        "relay retirement released its seat but left the physical HTTP request open",
                    )
                    response.await()
                }
            } finally {
                relay.close()
                listener.close()
                executor.shutdownNow()
                connections.closeAll()
            }
        }

    @Test
    fun `a changed room capability retires the previous physical relay before starting another`() =
        testApplication {
            val ffmpegs = CopyOnWriteArrayList<BlockingProcess>()
            val runner = MediaProcessRunner { request ->
                if (request.command.first() == "ffprobe") {
                    request.stdoutFile?.let { Files.writeString(it, "aac\n") }
                    MemoryProcess("aac\n".toByteArray())
                } else {
                    BlockingProcess().also(ffmpegs::add)
                }
            }
            val connections = ProviderConnections()
            val relay = LiveRelay(ServerHttp(), connections, { true }, runner)
            val browser = MediaCapabilities.BROWSER
            val native = browser.copy(audio = browser.audio + "ac3", selectsTracksInBand = true)
            application {
                routing {
                    get("/browser") {
                        relay.stream(
                            call, "https://provider.example/live.ts", "room", "provider", 1,
                            "browser", browser,
                        ) {}
                    }
                    get("/native") {
                        relay.stream(
                            call, "https://provider.example/live.ts", "room", "provider", 1,
                            "native", native,
                        ) {}
                    }
                }
            }

            try {
                coroutineScope {
                    val first = async { client.get("/browser").bodyAsBytes() }
                    withTimeout(2_000) { while (ffmpegs.size < 1) delay(10) }
                    val second = async { client.get("/native").bodyAsBytes() }
                    withTimeout(2_000) { while (ffmpegs.size < 2) delay(10) }
                    assertTrue(
                        ffmpegs.first().destroyed,
                        "the old capability pipeline still held a second physical provider connection",
                    )
                    relay.close()
                    first.await()
                    second.await()
                }
            } finally {
                relay.close()
                connections.closeAll()
            }
        }

    private fun remuxSession(root: java.nio.file.Path) = RemuxSession(
        id = "id",
        dir = root,
        url = "file:///movie.mkv",
        providerKey = "local",
        shareKey = "viewer",
        connectionLimit = 1,
        audioIndex = 0,
        durationSec = 5.0,
        segLenSec = 2.5,
        starts = listOf(0.0, 2.5),
        timeshift = false,
        transcodeVideo = false,
        videoCodec = "h264",
        audio = MediaStreamInfo(1, "audio", "aac", null, null, 2, false),
        subs = emptyList(),
        audioLabels = listOf("Audio 1"),
        subLabels = emptyList(),
        nativeVideoCopy = false,
        lastAccessMs = 0,
        startupStartedNs = 0,
        connectionLimitMs = 0,
        ffprobeMs = 0,
    )

    private fun testCipher() = StreamCipher(
        java.util.Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() }),
    )

    private open class MemoryProcess(
        bytes: ByteArray = ByteArray(0),
        private val exitCode: Int = 0,
    ) : Process() {
        private val input = ByteArrayInputStream(bytes)
        @Volatile
        var destroyed = false
        var timedWaits = 0

        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = input
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int {
            timedWaits++
            return exitCode
        }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            timedWaits++
            return true
        }
        override fun exitValue(): Int = exitCode
        override fun destroy() {
            destroyed = true
            input.close()
        }
        override fun destroyForcibly(): Process {
            destroy()
            return this
        }
        override fun isAlive(): Boolean = false
    }

    private class TimeoutProcess : MemoryProcess() {
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            timedWaits++
            return destroyed
        }

        override fun exitValue(): Int {
            if (!destroyed) throw IllegalThreadStateException()
            return 137
        }

        override fun isAlive(): Boolean = !destroyed
    }

    private class InterruptingProcess : MemoryProcess() {
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            throw InterruptedException("cancelled")
        }

        override fun isAlive(): Boolean = !destroyed
    }

    private class BlockingProcess : Process() {
        private val input = java.io.PipedInputStream()
        private val output = java.io.PipedOutputStream(input)
        @Volatile
        var destroyed = false
        var timedWaits = 0

        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = input
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int {
            while (!destroyed) Thread.sleep(10)
            return 137
        }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            timedWaits++
            return destroyed
        }
        override fun exitValue(): Int {
            if (!destroyed) throw IllegalThreadStateException()
            return 137
        }
        override fun destroy() {
            destroyed = true
            runCatching { output.close() }
            runCatching { input.close() }
        }
        override fun destroyForcibly(): Process {
            destroy()
            return this
        }
        override fun isAlive(): Boolean = !destroyed
    }

    private class LatchingProcess : Process() {
        @Volatile
        var destroyed = false

        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int {
            while (!destroyed) Thread.sleep(10)
            return 137
        }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!destroyed && System.nanoTime() < deadline) Thread.sleep(10)
            return destroyed
        }
        override fun exitValue(): Int {
            if (!destroyed) throw IllegalThreadStateException()
            return 137
        }
        override fun destroy() {
            destroyed = true
        }
        override fun destroyForcibly(): Process {
            destroy()
            return this
        }
        override fun isAlive(): Boolean = !destroyed
    }

    private fun deleteTree(root: java.nio.file.Path) {
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private companion object {
        val playableProbe = """
            {
              "streams": [
                {"index":0,"codec_type":"video","codec_name":"hevc"},
                {"index":1,"codec_type":"audio","codec_name":"aac","channels":2}
              ],
              "format":{"duration":"120.0"}
            }
        """.trimIndent()
    }
}
