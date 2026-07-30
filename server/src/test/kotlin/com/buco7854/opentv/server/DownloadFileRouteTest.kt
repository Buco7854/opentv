package com.buco7854.opentv.server

import com.buco7854.opentv.contract.UpdateUserRequestDto
import com.buco7854.opentv.core.model.Playlist
import com.buco7854.opentv.serverdata.AuthMethod
import com.buco7854.opentv.serverdata.ClientKind
import com.buco7854.opentv.serverdata.DownloadBlobStatus
import com.buco7854.opentv.serverdata.UserRole
import com.buco7854.opentv.serverdata.UserStatus
import com.buco7854.opentv.serverdata.createOpenTvServerStorage
import com.buco7854.opentv.serverdata.db.AuthSessionRow
import com.buco7854.opentv.serverdata.db.ContentIdentityRow
import com.buco7854.opentv.serverdata.db.DownloadBlobRow
import com.buco7854.opentv.serverdata.db.UserDownloadRow
import com.buco7854.opentv.serverdata.db.UserPlaylistGrantRow
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
import kotlin.test.assertFailsWith
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
                client.get(fixture.fileUrl(userId = "other", sessionId = "session-other")).status,
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
                .encryptDownloadFile("owner", "session-a", "different-download")
                .token
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.get(
                    "/api/v1/downloads/download-1/file?token=$wrongDownload",
                ).status,
            )
        }

    @Test
    fun `revoking one session kills only capabilities minted by that session`() =
        withDownloadServer(
            status = DownloadBlobStatus.RUNNING,
            contents = "abcdef",
            totalBytes = 10,
        ) { fixture ->
            val sessionAUrl = fixture.fileUrl(sessionId = "session-a")
            val sessionBUrl = fixture.fileUrl(sessionId = "session-b")
            assertEquals(HttpStatusCode.OK, client.get(sessionAUrl).status)
            assertEquals(HttpStatusCode.OK, client.get(sessionBUrl).status)

            fixture.auth.revokeSession(fixture.adminActor, "owner", "session-a")

            assertEquals(HttpStatusCode.Gone, client.get(sessionAUrl).status)
            assertEquals(HttpStatusCode.OK, client.get(sessionBUrl).status)
            assertFailsWith<UnauthenticatedApiException> {
                fixture.service.list(fixture.actor("session-a"))
            }
            assertNotNull(fixture.service.list(fixture.actor("session-b")).single().fileToken)
        }

    @Test
    fun `disabling a user kills capabilities from every session`() =
        withDownloadServer(
            status = DownloadBlobStatus.RUNNING,
            contents = "abcdef",
            totalBytes = 10,
        ) { fixture ->
            val sessionAUrl = fixture.fileUrl(sessionId = "session-a")
            val sessionBUrl = fixture.fileUrl(sessionId = "session-b")

            fixture.auth.adminUpdateUser(
                fixture.adminActor,
                "owner",
                UpdateUserRequestDto(status = UserStatus.DISABLED),
            )

            assertEquals(HttpStatusCode.Gone, client.get(sessionAUrl).status)
            assertEquals(HttpStatusCode.Gone, client.get(sessionBUrl).status)
            assertEquals(DownloadBlobStatus.PAUSED, fixture.db.downloads().blob("blob-1")?.status)
            assertEquals(true, fixture.db.downloads().userDownload("download-1")?.suspended)
        }

    @Test
    fun `request authenticated before all-session revocation cannot restore a suspended download`() =
        withDownloadServer(
            status = DownloadBlobStatus.RUNNING,
            contents = "abcdef",
            totalBytes = 10,
        ) { fixture ->
            val staleActor = fixture.actor
            fixture.auth.revokeSession(fixture.adminActor, "owner", null)

            assertFailsWith<UnauthenticatedApiException> {
                fixture.service.resume(staleActor, "download-1")
            }
            assertEquals(true, fixture.db.downloads().userDownload("download-1")?.suspended)
            assertEquals(false, fixture.db.downloads().userDownload("download-1")?.active)
            assertEquals(DownloadBlobStatus.PAUSED, fixture.db.downloads().blob("blob-1")?.status)
        }

    @Test
    fun `deleting a user kills every capability without cancelling a shared blob`() =
        withDownloadServer(
            status = DownloadBlobStatus.RUNNING,
            contents = "abcdef",
            totalBytes = 10,
            sharedOther = true,
        ) { fixture ->
            val sessionAUrl = fixture.fileUrl(sessionId = "session-a")
            val sessionBUrl = fixture.fileUrl(sessionId = "session-b")
            val otherUserUrl = fixture.fileUrl(
                userId = "other",
                sessionId = "session-other",
                downloadId = "download-other",
            )

            fixture.auth.adminDeleteUser(fixture.adminActor, "owner")

            assertEquals(HttpStatusCode.Gone, client.get(sessionAUrl).status)
            assertEquals(HttpStatusCode.Gone, client.get(sessionBUrl).status)
            assertEquals(HttpStatusCode.OK, client.get(otherUserUrl).status)
            assertEquals(DownloadBlobStatus.RUNNING, fixture.db.downloads().blob("blob-1")?.status)
            assertEquals(1, fixture.db.downloads().referenceCount("blob-1"))
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
        sharedOther: Boolean = false,
        block: suspend ApplicationTestBuilder.(Fixture) -> Unit,
    ) = testApplication {
        val dir = Files.createTempDirectory("download-file-route")
        val persistence = createOpenTvServerStorage(dir.resolve("opentv.db").toString())
        val storage = persistence.catalog
        val db = persistence.database
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
            db.users().insert(user("admin", UserRole.ADMIN))
            db.sessions().insert(session("session-a", "owner", 1))
            db.sessions().insert(session("session-b", "owner", 2))
            db.sessions().insert(session("session-other", "other", 3))
            val playlistId = storage.playlists.insert(Playlist(name = "Provider", url = null))
            db.grants().grant(UserPlaylistGrantRow("owner", playlistId, 1))
            if (sharedOther) {
                db.grants().grant(UserPlaylistGrantRow("other", playlistId, 1))
            }
            db.content().upsert(
                ContentIdentityRow("content-1", playlistId, 1, "opaque", null, 1, false),
            )
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
            if (sharedOther) {
                db.downloads().upsertUserDownload(
                    UserDownloadRow("download-other", "other", "blob-1", true, false, 1, 1),
                )
            }
            val config = authConfig()
            val cleanup = object : UserStateCleanupCoordinator {
                override suspend fun sessionRevoked(userId: String, authSessionId: String?) {
                    if (authSessionId == null) manager.suspendUserAccess(userId)
                }

                override suspend fun playlistGrantRevoked(userId: String, playlistId: Long) = Unit
                override suspend fun userDeleted(userId: String) = manager.scheduleOrphanCleanup()
                override suspend fun playlistDeleting(playlistId: Long) = Unit
                override suspend fun <T> admitPlayback(block: suspend () -> T): T = block()
            }
            val auth = AuthService(db, config, dir, cleanup = cleanup)
            val service = DownloadApplicationService(
                manager,
                ContentIdentityService(db, storage),
                auth,
                cipher,
            )
            val fixture = Fixture(service, auth, cipher, db)
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
            storage.close()
            dir.toFile().deleteRecursively()
        }
    }

    private fun user(id: String, role: String = UserRole.USER) = UserRow(
        id = id,
        username = id,
        normalizedUsername = id,
        displayName = id,
        status = UserStatus.ACTIVE,
        manualRole = role,
        oidcAdmin = false,
        createdAtMs = 1,
        updatedAtMs = 1,
        lastLoginAtMs = null,
    )

    private fun session(id: String, userId: String, tokenByte: Int): AuthSessionRow {
        val now = System.currentTimeMillis()
        return AuthSessionRow(
            id = id,
            userId = userId,
            tokenHash = ByteArray(32) { tokenByte.toByte() },
            csrfToken = "",
            authMethod = AuthMethod.PASSWORD,
            clientKind = ClientKind.BROWSER,
            tokenFamilyId = "family-$id",
            credentialVersion = 0,
            deviceId = null,
            deviceName = null,
            mfaSatisfiedAtMs = null,
            createdAtMs = now,
            lastSeenAtMs = now,
            idleExpiresAtMs = now + 24 * 60 * 60_000L,
            absoluteExpiresAtMs = now + 30L * 24 * 60 * 60_000L,
            revokedAtMs = null,
        )
    }

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
        val auth: AuthService,
        val cipher: StreamCipher,
        val db: com.buco7854.opentv.serverdata.db.OpenTvServerDatabase,
    ) {
        val actor get() = actor("session-a")

        val adminActor = Actor(
            userId = "admin",
            authSessionId = "admin-session",
            username = "admin",
            displayName = "Admin",
            roles = setOf(UserRole.USER, UserRole.ADMIN),
            authMethod = AuthMethod.PASSWORD,
            clientKind = ClientKind.BROWSER,
        )

        fun actor(sessionId: String) = Actor(
            userId = "owner",
            authSessionId = sessionId,
            username = "owner",
            displayName = "Owner",
            roles = setOf(UserRole.USER, UserRole.ADMIN),
            authMethod = AuthMethod.PASSWORD,
            clientKind = ClientKind.BROWSER,
        )

        fun fileUrl(
            userId: String = "owner",
            sessionId: String = "session-a",
            downloadId: String = "download-1",
        ): String {
            val token = cipher.encryptDownloadFile(userId, sessionId, downloadId).token
            return "/api/v1/downloads/$downloadId/file?token=$token"
        }
    }
}
