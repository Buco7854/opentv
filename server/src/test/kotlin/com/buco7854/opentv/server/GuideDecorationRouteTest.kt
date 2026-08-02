package com.buco7854.opentv.server

import com.buco7854.opentv.contract.ProgrammeDto
import com.buco7854.opentv.core.log.CoreLog
import com.buco7854.opentv.core.model.Playlist
import com.buco7854.opentv.core.model.Programme
import com.buco7854.opentv.core.net.ConditionalFetcher
import com.buco7854.opentv.core.repo.AccountRepository
import com.buco7854.opentv.core.repo.EpgRepository
import com.buco7854.opentv.core.repo.PlaylistRepository
import com.buco7854.opentv.core.repo.XtreamRepository
import com.buco7854.opentv.core.xtream.XtreamApi
import com.buco7854.opentv.serverdata.UserRole
import com.buco7854.opentv.serverdata.UserStatus
import com.buco7854.opentv.serverdata.db.UserRow
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.net.URI
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class GuideDecorationRouteTest {
    @Test
    fun guideDecorationsRequireAndHonorAVisibleChannelScope() = testApplication {
        val fixture = Fixture()
        try {
            val playlistId = fixture.seed()
            application {
                installOpenTvErrorResponses()
                install(ContentNegotiation) { json(JSON) }
                routing {
                    route("/api/v1") {
                        apiSecurityBoundary(
                            ApiSecurity(
                                ApiAuthenticator {
                                    ApiPrincipal(
                                        subject = USER_ID,
                                        username = USER_ID,
                                        displayName = "Admin",
                                        roles = setOf(UserRole.ADMIN),
                                    )
                                },
                            ),
                            clientIp = { "127.0.0.1" },
                        ) {
                            playlistRoutes(fixture.service)
                        }
                    }
                }
            }

            val body = """{"tvgIds":["visible-a","visible-b"]}"""
            val nowResponse = client.post("/api/v1/playlists/$playlistId/now-airing") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            assertEquals(HttpStatusCode.OK, nowResponse.status)
            val nowAiring = JSON.decodeFromString<Map<String, ProgrammeDto>>(
                nowResponse.bodyAsText(),
            )
            assertEquals(setOf("visible-a", "visible-b"), nowAiring.keys)
            assertEquals("Newest active", nowAiring.getValue("visible-a").title)

            val idsResponse = client.post("/api/v1/playlists/$playlistId/guide-ids") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            assertEquals(HttpStatusCode.OK, idsResponse.status)
            assertEquals(
                setOf("visible-a", "visible-b"),
                JSON.decodeFromString<List<String>>(idsResponse.bodyAsText()).toSet(),
            )

            val empty = client.post("/api/v1/playlists/$playlistId/now-airing") {
                contentType(ContentType.Application.Json)
                setBody("""{"tvgIds":[]}""")
            }
            assertEquals(emptyMap(), JSON.decodeFromString<Map<String, ProgrammeDto>>(empty.bodyAsText()))

            assertNotEquals(
                HttpStatusCode.OK,
                client.get("/api/v1/playlists/$playlistId/now-airing").status,
            )

            val oversized = client.post("/api/v1/playlists/$playlistId/guide-ids") {
                contentType(ContentType.Application.Json)
                setBody(JSON.encodeToString(mapOf("tvgIds" to List(1_001) { "id-$it" })))
            }
            assertEquals(HttpStatusCode.BadRequest, oversized.status)
        } finally {
            fixture.close()
        }
    }

    private class Fixture : AutoCloseable {
        private val persistence = ServerTestPersistence("guide-decoration-route")
        private val storage = persistence.storage
        private val db = persistence.database
        private val auth = AuthService(db, authConfig(), persistence.directory)
        private val downloads: DownloadManager
        val service: PlaylistApplicationService

        init {
            val log = CoreLog { _, _ -> }
            val xtreamApi = XtreamApi { error("provider access is not used by this test") }
            val account = AccountRepository(xtreamApi, log)
            val fetcher = ConditionalFetcher { _, _, _ -> error("refresh is not used by this test") }
            val epg = EpgRepository(storage, fetcher)
            val settings = ServerSettings(persistence.directory, pageSize = 50)
            val content = ContentIdentityService(db, storage)
            downloads = DownloadManager(
                db,
                ServerHttp(),
                settings,
                persistence.directory,
                ProviderConnections(),
                connectionLimit = { Int.MAX_VALUE },
            )
            service = PlaylistApplicationService(
                storage,
                PlaylistRepository(storage, xtreamApi, fetcher, log, account),
                epg,
                XtreamRepository(storage, xtreamApi, epg, account, log),
                account,
                StreamCipher(settings.streamKey),
                auth,
                content,
                UserActivityService(db, auth, content),
                db,
                downloads,
            )
        }

        suspend fun seed(): Long {
            db.users().insert(
                UserRow(
                    id = USER_ID,
                    username = USER_ID,
                    normalizedUsername = USER_ID,
                    displayName = "Admin",
                    status = UserStatus.ACTIVE,
                    manualRole = UserRole.ADMIN,
                    oidcAdmin = false,
                    createdAtMs = 1,
                    updatedAtMs = 1,
                    lastLoginAtMs = null,
                ),
            )
            val playlistId = storage.playlists.insert(Playlist(name = "Guide", url = null))
            val now = System.currentTimeMillis()
            storage.epg.insertAll(
                listOf(
                    Programme(0, playlistId, "visible-a", "Older", null, now - 10_000, now + 10_000),
                    Programme(0, playlistId, "visible-a", "Newest active", null, now - 1_000, now + 10_000),
                    Programme(0, playlistId, "visible-b", "Visible B", null, now - 10_000, now + 10_000),
                    Programme(0, playlistId, "offscreen", "Hidden", null, now - 10_000, now + 10_000),
                ),
            )
            return playlistId
        }

        override fun close() {
            service.close()
            downloads.close()
            persistence.close()
        }
    }

    private companion object {
        const val USER_ID = "admin"
        val JSON = Json { ignoreUnknownKeys = true }

        fun authConfig() = AuthConfig(
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
    }
}
