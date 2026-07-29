package com.buco7854.opentv.server

import com.buco7854.opentv.data.createRoomStorage
import com.buco7854.opentv.serverdata.AuthMethod
import com.buco7854.opentv.serverdata.ClientKind
import com.buco7854.opentv.serverdata.DownloadBlobStatus
import com.buco7854.opentv.serverdata.UserRole
import com.buco7854.opentv.serverdata.UserStatus
import com.buco7854.opentv.serverdata.createServerUserDatabase
import com.buco7854.opentv.serverdata.db.ContentIdentityRow
import com.buco7854.opentv.serverdata.db.DownloadBlobRow
import com.buco7854.opentv.serverdata.db.UserDownloadRow
import com.buco7854.opentv.serverdata.db.UserRow
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DownloadFileRouteTest {
    @Test
    fun `running blob serves only its available snapshot with ranges`() =
        withDownloadServer(
            status = DownloadBlobStatus.RUNNING,
            contents = "abcdef",
            totalBytes = 10,
        ) { fixture ->
            val listed = fixture.service.list(fixture.actor).single()
            assertNotNull(listed.fileToken)
            assertEquals(10, listed.totalBytes)
            assertEquals(6, listed.downloadedBytes)

            val whole = client.get(fixture.fileUrl())
            assertEquals(HttpStatusCode.OK, whole.status)
            assertEquals("6", whole.headers[HttpHeaders.ContentLength])
            assertEquals("abcdef", whole.bodyAsText())

            val range = client.get(fixture.fileUrl()) {
                header(HttpHeaders.Range, "bytes=2-")
            }
            assertEquals(HttpStatusCode.PartialContent, range.status)
            assertEquals("bytes 2-5/6", range.headers[HttpHeaders.ContentRange])
            assertEquals("cdef", range.bodyAsText())
        }

    @Test
    fun `range beyond a running snapshot is unsatisfied rather than complete`() =
        withDownloadServer(
            status = DownloadBlobStatus.RUNNING,
            contents = "abcdef",
            totalBytes = 10,
        ) { fixture ->
            val response = client.get(fixture.fileUrl()) {
                header(HttpHeaders.Range, "bytes=6-")
            }

            assertEquals(HttpStatusCode.RequestedRangeNotSatisfiable, response.status)
            assertEquals("bytes */6", response.headers[HttpHeaders.ContentRange])
            assertEquals("", response.bodyAsText())
        }

    @Test
    fun `done blob retains completed file range behavior`() =
        withDownloadServer(
            status = DownloadBlobStatus.DONE,
            contents = "abcdef",
            totalBytes = 6,
        ) { fixture ->
            val response = client.get(fixture.fileUrl()) {
                header(HttpHeaders.Range, "bytes=3-4")
            }

            assertEquals(HttpStatusCode.PartialContent, response.status)
            assertEquals("bytes 3-4/6", response.headers[HttpHeaders.ContentRange])
            assertEquals("de", response.bodyAsText())
        }

    @Test
    fun `inconsistent done blob is not exposed as a completed file`() =
        withDownloadServer(
            status = DownloadBlobStatus.DONE,
            contents = "partial",
            totalBytes = 20,
        ) { fixture ->
            val listed = fixture.service.list(fixture.actor).single()
            assertEquals(null, listed.fileToken)
            assertEquals(HttpStatusCode.NotFound, client.get(fixture.fileUrl()).status)
        }

    @Test
    fun `suspended and not-owned downloads remain unavailable`() =
        withDownloadServer(
            status = DownloadBlobStatus.RUNNING,
            contents = "abcdef",
            totalBytes = 10,
            suspended = true,
        ) { fixture ->
            assertEquals(HttpStatusCode.NotFound, client.get(fixture.fileUrl()).status)
            assertEquals(
                HttpStatusCode.NotFound,
                client.get(fixture.fileUrl(userId = "other")).status,
            )
        }

    @Test
    fun `file capability is required and bound to the requested download`() =
        withDownloadServer(
            status = DownloadBlobStatus.RUNNING,
            contents = "abcdef",
            totalBytes = 10,
        ) { fixture ->
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.get("/api/v1/downloads/download-1/file").status,
            )
            val wrongDownload = fixture.cipher
                .encryptDownloadFile("owner", "different-download")
                .token
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.get(
                    "/api/v1/downloads/download-1/file?token=$wrongDownload",
                ).status,
            )
        }

    @Test
    fun `blob path outside the managed download directory is rejected`() =
        withDownloadServer(
            status = DownloadBlobStatus.RUNNING,
            contents = "abcdef",
            totalBytes = 10,
            unsafePath = true,
        ) { fixture ->
            assertEquals(HttpStatusCode.NotFound, client.get(fixture.fileUrl()).status)
        }

    @Test
    fun `failed fetch with partial bytes is not served as a completed file`() =
        withDownloadServer(
            status = DownloadBlobStatus.FAILED,
            contents = "partial",
            totalBytes = 20,
        ) { fixture ->
            assertEquals(HttpStatusCode.NotFound, client.get(fixture.fileUrl()).status)
        }

    private fun withDownloadServer(
        status: String,
        contents: String,
        totalBytes: Long,
        suspended: Boolean = false,
        unsafePath: Boolean = false,
        block: suspend ApplicationTestBuilder.(Fixture) -> Unit,
    ) = testApplication {
        val dir = Files.createTempDirectory("download-file-route")
        val storage = createRoomStorage(dir.resolve("catalog.db").toString())
        val db = createServerUserDatabase(dir.resolve("users.db").toString())
        val settings = ServerSettings(dir, pageSize = 50)
        val cipher = StreamCipher(settings.streamKey)
        val manager = DownloadManager(
            db,
            ServerHttp(),
            settings,
            dir,
            ProviderConnections(),
            connectionLimit = { Int.MAX_VALUE },
        )
        try {
            val path = if (unsafePath) {
                dir.resolve("escape.bin")
            } else {
                dir.resolve("user-downloads/movie.bin")
            }
            Files.createDirectories(path.parent)
            Files.writeString(path, contents)
            db.users().insert(user("owner"))
            db.users().insert(user("other"))
            db.content().upsert(ContentIdentityRow("content-1", 1, 1, "opaque", 1, 1, false))
            db.downloads().upsertBlob(
                DownloadBlobRow(
                    id = "blob-1",
                    contentId = "content-1",
                    title = "Movie",
                    sourceUrl = "https://provider.invalid/movie",
                    filePath = path.toString(),
                    status = status,
                    totalBytes = totalBytes,
                    downloadedBytes = contents.length.toLong(),
                    error = if (status == DownloadBlobStatus.FAILED) "Provider failed" else null,
                    createdAtMs = 1,
                    updatedAtMs = 1,
                ),
            )
            db.downloads().upsertUserDownload(
                UserDownloadRow("download-1", "owner", "blob-1", true, suspended, 1, 1),
            )
            val config = authConfig()
            val auth = AuthService(db, config, dir)
            val service = DownloadApplicationService(
                manager,
                ContentIdentityService(db, storage),
                auth,
                cipher,
            )
            val fixture = Fixture(service, cipher)
            application {
                install(ContentNegotiation) { json(Json { encodeDefaults = true }) }
                install(PartialContent)
                installOpenTvErrorResponses()
                routing {
                    route("/api/v1") {
                        downloadFileRoutes(service)
                    }
                }
            }
            block(fixture)
        } finally {
            manager.close()
            db.close()
            storage.close()
            dir.toFile().deleteRecursively()
        }
    }

    private fun user(id: String) = UserRow(
        id = id,
        username = id,
        normalizedUsername = id,
        displayName = id,
        status = UserStatus.ACTIVE,
        manualRole = UserRole.USER,
        oidcAdmin = false,
        createdAtMs = 1,
        updatedAtMs = 1,
        lastLoginAtMs = null,
    )

    private fun authConfig() = AuthConfig(
        publicUrl = URI("https://tv.example.com"),
        passwordEnabled = true,
        encryptionKey = ByteArray(32) { it.toByte() },
        initialAdmin = null,
        mfaRequiredRoles = emptySet(),
        oidc = null,
        secureCookies = true,
        webAuthnRpId = "tv.example.com",
        webAuthnOrigin = "https://tv.example.com",
        sessionIdleMs = 24 * 60 * 60_000L,
        sessionAbsoluteMs = 30L * 24 * 60 * 60_000L,
    )

    private data class Fixture(
        val service: DownloadApplicationService,
        val cipher: StreamCipher,
    ) {
        val actor = Actor(
            userId = "owner",
            authSessionId = "session",
            username = "owner",
            displayName = "Owner",
            roles = setOf(UserRole.USER, UserRole.ADMIN),
            authMethod = AuthMethod.PASSWORD,
            clientKind = ClientKind.BROWSER,
        )

        fun fileUrl(userId: String = "owner"): String {
            val token = cipher.encryptDownloadFile(userId, "download-1").token
            return "/api/v1/downloads/download-1/file?token=$token"
        }
    }
}
