package com.buco7854.opentv.server

import com.buco7854.opentv.core.model.Channel
import com.buco7854.opentv.core.model.ChannelKind
import com.buco7854.opentv.data.db.PlaylistRow
import com.buco7854.opentv.serverdata.DownloadBlobStatus
import com.buco7854.opentv.serverdata.UserRole
import com.buco7854.opentv.serverdata.UserStatus
import com.buco7854.opentv.serverdata.db.ContentIdentityRow
import com.buco7854.opentv.serverdata.db.DownloadBlobRow
import com.buco7854.opentv.serverdata.db.UserDownloadRow
import com.buco7854.opentv.serverdata.db.UserRow
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class DownloadManagerTransferTest {
    @Test
    fun `zero byte provider response cannot complete a media download`() = withFixture { fixture ->
        fixture.server.createContext("/movie") { exchange ->
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        fixture.server.start()

        fixture.manager.enqueue("owner", fixture.identity, fixture.channel(fixture.url("/movie")))
        val blob = fixture.awaitSettledBlob()

        assertEquals(DownloadBlobStatus.FAILED, blob.status)
        assertNotEquals(DownloadBlobStatus.DONE, blob.status)
        assertEquals(0, blob.downloadedBytes)
    }

    @Test
    fun `short ranged responses continue until the declared total is present`() = withFixture { fixture ->
        val target = fixture.downloadPath
        Files.createDirectories(target.parent)
        Files.writeString(target, "abc")
        fixture.seedRunningBlob(target)
        fixture.server.createContext("/movie") { exchange ->
            when (exchange.requestHeaders.getFirst("Range")) {
                "bytes=3-" -> {
                    val bytes = "de".toByteArray()
                    exchange.responseHeaders.add("Content-Range", "bytes 3-4/6")
                    exchange.sendResponseHeaders(206, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                "bytes=5-" -> {
                    val bytes = "f".toByteArray()
                    exchange.responseHeaders.add("Content-Range", "bytes 5-5/6")
                    exchange.sendResponseHeaders(206, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                else -> {
                    exchange.sendResponseHeaders(500, -1)
                    exchange.close()
                }
            }
        }
        fixture.server.start()

        fixture.manager.start()
        val blob = fixture.awaitSettledBlob()

        assertEquals(DownloadBlobStatus.DONE, blob.status)
        assertEquals(6, blob.downloadedBytes)
        assertEquals(6, blob.totalBytes)
        assertEquals("abcdef", Files.readString(target))
    }

    @Test
    fun `mismatched Content-Range start cannot be appended`() = withFixture { fixture ->
        val target = fixture.downloadPath
        Files.createDirectories(target.parent)
        Files.writeString(target, "abc")
        fixture.seedRunningBlob(target)
        fixture.server.createContext("/movie") { exchange ->
            val bytes = "XYZ".toByteArray()
            exchange.responseHeaders.add("Content-Range", "bytes 0-2/6")
            exchange.sendResponseHeaders(206, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        fixture.server.start()

        fixture.manager.start()
        val blob = fixture.awaitSettledBlob()

        assertEquals(DownloadBlobStatus.FAILED, blob.status)
        assertEquals("abc", Files.readString(target))
    }

    @Test
    fun `provider ignoring resume cannot replace a blob already exposed to clients`() =
        withFixture { fixture ->
            val target = fixture.downloadPath
            Files.createDirectories(target.parent)
            Files.writeString(target, "abc")
            fixture.seedRunningBlob(target)
            fixture.server.createContext("/movie") { exchange ->
                val bytes = "XYZDEF".toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            fixture.server.start()

            fixture.manager.start()
            val blob = fixture.awaitSettledBlob()

            assertEquals(DownloadBlobStatus.FAILED, blob.status)
            assertEquals("abc", Files.readString(target))
        }

    @Test
    fun `matching 416 recovers a crash after the final server write`() = withFixture { fixture ->
        val target = fixture.downloadPath
        Files.createDirectories(target.parent)
        Files.writeString(target, "abc")
        fixture.seedRunningBlob(target, totalBytes = 3)
        fixture.server.createContext("/movie") { exchange ->
            exchange.responseHeaders.add("Content-Range", "bytes */3")
            exchange.sendResponseHeaders(416, -1)
            exchange.close()
        }
        fixture.server.start()

        fixture.manager.start()
        val blob = fixture.awaitSettledBlob()

        assertEquals(DownloadBlobStatus.DONE, blob.status)
        assertEquals(3L, blob.totalBytes)
        assertEquals(3L, blob.downloadedBytes)
        assertEquals("abc", Files.readString(target))
    }

    @Test
    fun `close cancels a blocked provider read before database teardown`() = withFixture { fixture ->
        val releaseResponse = CountDownLatch(1)
        fixture.server.createContext("/movie") { exchange ->
            exchange.sendResponseHeaders(200, 2)
            try {
                exchange.responseBody.write('a'.code)
                exchange.responseBody.flush()
                releaseResponse.await(10, TimeUnit.SECONDS)
            } finally {
                exchange.close()
            }
        }
        fixture.server.start()

        fixture.manager.enqueue("owner", fixture.identity, fixture.channel(fixture.url("/movie")))
        fixture.awaitDownloadedBytes(1)

        val closing = CompletableFuture.runAsync(fixture.manager::close)
        try {
            closing.get(2, TimeUnit.SECONDS)
        } finally {
            releaseResponse.countDown()
            runCatching { closing.get(2, TimeUnit.SECONDS) }
        }
    }

    private fun withFixture(block: suspend (Fixture) -> Unit) = runBlocking {
        val fixture = Fixture()
        try {
            block(fixture)
        } finally {
            fixture.close()
        }
    }

    private class Fixture {
        private val persistence = ServerTestPersistence("download-manager-transfer")
        private val dir = persistence.directory
        private val db = persistence.database
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val manager = DownloadManager(
            db = db,
            http = ServerHttp(),
            settings = ServerSettings(dir, pageSize = 50),
            dataDir = dir,
            connections = ProviderConnections(),
            connectionLimit = { Int.MAX_VALUE },
        )
        val identity = ContentIdentityRow(
            "content-1", 1, ChannelKind.MOVIE, "opaque", null, 1, false,
        )
        val downloadPath = dir.resolve("user-downloads/movie.bin")

        init {
            persistence.closeBeforeDatabase { server.stop(0) }
            persistence.closeBeforeDatabase(manager::close)
            runBlocking {
                db.playlistDao().insert(PlaylistRow(id = 1, name = "Provider", url = null))
                db.users().insert(
                    UserRow(
                        id = "owner",
                        username = "owner",
                        normalizedUsername = "owner",
                        displayName = "Owner",
                        status = UserStatus.ACTIVE,
                        manualRole = UserRole.USER,
                        oidcAdmin = false,
                        createdAtMs = 1,
                        updatedAtMs = 1,
                        lastLoginAtMs = null,
                    ),
                )
                db.content().upsert(identity)
            }
        }

        fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"

        fun channel(url: String) = Channel(
            playlistId = 1,
            name = "Movie",
            url = url,
            logo = null,
            groupTitle = "Movies",
            tvgId = null,
            kind = ChannelKind.MOVIE,
            seriesKey = null,
            season = null,
            episode = null,
            position = 0,
        )

        suspend fun seedRunningBlob(
            path: java.nio.file.Path,
            totalBytes: Long = 6,
        ) {
            db.downloads().upsertBlob(
                DownloadBlobRow(
                    id = "blob-1",
                    contentId = identity.contentId,
                    title = "Movie",
                    sourceUrl = url("/movie"),
                    filePath = path.toString(),
                    status = DownloadBlobStatus.RUNNING,
                    totalBytes = totalBytes,
                    downloadedBytes = 3,
                    error = null,
                    createdAtMs = 1,
                    updatedAtMs = 1,
                ),
            )
            db.downloads().upsertUserDownload(
                UserDownloadRow("download-1", "owner", "blob-1", true, false, 1, 1),
            )
        }

        suspend fun awaitSettledBlob(): DownloadBlobRow = withTimeout(10_000) {
            while (true) {
                val blob = db.downloads().blobForContent(identity.contentId)
                if (blob != null && blob.status !in setOf(
                        DownloadBlobStatus.QUEUED,
                        DownloadBlobStatus.RUNNING,
                    )
                ) {
                    return@withTimeout blob
                }
                delay(10)
            }
            error("unreachable")
        }

        suspend fun awaitDownloadedBytes(expected: Long) = withTimeout(10_000) {
            while (db.downloads().blobForContent(identity.contentId)?.downloadedBytes != expected) {
                delay(10)
            }
        }

        fun close() {
            persistence.close()
        }
    }
}
