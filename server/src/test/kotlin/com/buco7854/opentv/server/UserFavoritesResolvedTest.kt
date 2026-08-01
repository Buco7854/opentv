package com.buco7854.opentv.server

import com.buco7854.opentv.contract.UserFavoritesResolvedDto
import com.buco7854.opentv.core.log.CoreLog
import com.buco7854.opentv.core.model.Channel
import com.buco7854.opentv.core.model.ChannelKind
import com.buco7854.opentv.core.model.Playlist
import com.buco7854.opentv.core.net.ConditionalFetcher
import com.buco7854.opentv.core.repo.AccountRepository
import com.buco7854.opentv.core.repo.EpgRepository
import com.buco7854.opentv.core.repo.PlaylistRepository
import com.buco7854.opentv.core.repo.XtreamRepository
import com.buco7854.opentv.core.storage.Storage
import com.buco7854.opentv.core.xtream.XtreamApi
import com.buco7854.opentv.serverdata.UserRole
import com.buco7854.opentv.serverdata.UserStatus
import com.buco7854.opentv.serverdata.createOpenTvServerStorage
import com.buco7854.opentv.serverdata.db.UserFavoriteRow
import com.buco7854.opentv.serverdata.db.UserPlaylistGrantRow
import com.buco7854.opentv.serverdata.db.UserRow
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UserFavoritesResolvedTest {
    @Test
    fun favorites_endpoint_spans_grants_without_merging_or_leaking_rows() = testApplication {
        val fixture = Fixture()
        try {
            val seeded = fixture.seed()
            application {
                install(ContentNegotiation) { json() }
                routing {
                    route("/api/v1") {
                        apiSecurityBoundary(
                            ApiSecurity(
                                ApiAuthenticator {
                                    ApiPrincipal(
                                        subject = USER_ID,
                                        username = "viewer",
                                        displayName = "Viewer",
                                        roles = setOf(UserRole.USER),
                                    )
                                },
                            ),
                            clientIp = { "127.0.0.1" },
                        ) {
                            favoriteRoutes(fixture.service)
                        }
                    }
                }
            }

            val initial = client.get("/api/v1/favorites/resolved")
            assertEquals(HttpStatusCode.OK, initial.status)
            val favorites = JSON.decodeFromString<UserFavoritesResolvedDto>(initial.bodyAsText())

            assertEquals(setOf(seeded.firstPlaylist, seeded.secondPlaylist), favorites.live.mapTo(mutableSetOf()) { it.playlistId })
            assertEquals(listOf("Same title", "Same title"), favorites.live.map { it.name }.sorted())
            assertEquals(setOf(seeded.firstContent, seeded.secondContent), favorites.live.mapTo(mutableSetOf()) { it.contentId })
            assertFalse(favorites.live.any { it.contentId == seeded.deniedContent })
            assertFalse(favorites.live.any { it.contentId == seeded.otherUsersContent })

            fixture.service.removeFavorite(fixture.actor, seeded.firstPlaylist, seeded.firstContent)

            val afterRemoval = JSON.decodeFromString<UserFavoritesResolvedDto>(
                client.get("/api/v1/favorites/resolved").bodyAsText(),
            )
            assertEquals(listOf(seeded.secondContent), afterRemoval.live.map { it.contentId })
            assertTrue(
                seeded.secondContent in fixture.db.activity().favorites(USER_ID).map { it.contentId },
                "removing the duplicate from one playlist must retain the other playlist's row",
            )

            fixture.db.grants().revoke(USER_ID, seeded.secondPlaylist)

            val afterRevocation = JSON.decodeFromString<UserFavoritesResolvedDto>(
                client.get("/api/v1/favorites/resolved").bodyAsText(),
            )
            assertTrue(afterRevocation.live.isEmpty(), "a revoked playlist must contribute nothing")
        } finally {
            fixture.close()
        }
    }

    private class Fixture {
        private val directory: Path = Files.createTempDirectory("user-favorites-resolved")
        private val persistence = createOpenTvServerStorage(directory.resolve("opentv.db").toString())
        val storage: Storage = persistence.catalog
        val db = persistence.database
        private val content = ContentIdentityService(db, storage)
        private val auth = AuthService(db, authConfig(), directory)
        private val downloads: DownloadManager
        val service: PlaylistApplicationService
        val actor = Actor(
            userId = USER_ID,
            authSessionId = "session",
            username = "viewer",
            displayName = "Viewer",
            roles = setOf(UserRole.USER),
            authMethod = "PASSWORD",
            clientKind = "BROWSER",
        )

        init {
            val log = CoreLog { _, _ -> }
            val xtreamApi = XtreamApi { error("panel access is not used by this test") }
            val account = AccountRepository(xtreamApi, log)
            val fetcher = ConditionalFetcher { _, _, _ -> error("refresh is not used by this test") }
            val playlists = PlaylistRepository(storage, xtreamApi, fetcher, log, account)
            val epg = EpgRepository(storage, fetcher)
            val settings = ServerSettings(directory, pageSize = 50)
            downloads = DownloadManager(
                db,
                ServerHttp(),
                settings,
                directory,
                ProviderConnections(),
                connectionLimit = { Int.MAX_VALUE },
            )
            service = PlaylistApplicationService(
                storage,
                playlists,
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

        suspend fun seed(): Seeded {
            db.users().insert(user(USER_ID, "viewer", "Viewer"))
            db.users().insert(user(OTHER_USER_ID, "other", "Other"))

            val (firstPlaylist, firstRows) = playlist(
                "First",
                listOf("Same title" to "https://first.example/live", "Other only" to "https://first.example/other"),
            )
            val (secondPlaylist, secondRows) = playlist(
                "Second",
                listOf("Same title" to "https://second.example/live"),
            )
            val (deniedPlaylist, deniedRows) = playlist(
                "Denied",
                listOf("Denied favorite" to "https://denied.example/live"),
            )
            db.grants().grant(UserPlaylistGrantRow(USER_ID, firstPlaylist, 1_000L))
            db.grants().grant(UserPlaylistGrantRow(USER_ID, secondPlaylist, 1_000L))

            val firstContent = firstRows.getValue("Same title")
            val otherUsersContent = firstRows.getValue("Other only")
            val secondContent = secondRows.getValue("Same title")
            val deniedContent = deniedRows.getValue("Denied favorite")
            db.activity().addFavorite(UserFavoriteRow(USER_ID, firstContent, 1_000L))
            db.activity().addFavorite(UserFavoriteRow(USER_ID, secondContent, 2_000L))
            // A stale row can survive a grant revocation; the read must still filter it.
            db.activity().addFavorite(UserFavoriteRow(USER_ID, deniedContent, 3_000L))
            db.activity().addFavorite(UserFavoriteRow(OTHER_USER_ID, otherUsersContent, 4_000L))

            return Seeded(
                firstPlaylist,
                secondPlaylist,
                firstContent,
                secondContent,
                deniedContent,
                otherUsersContent,
            )
        }

        private suspend fun playlist(
            name: String,
            entries: List<Pair<String, String>>,
        ): Pair<Long, Map<String, String>> {
            val playlistId = storage.playlists.insert(
                Playlist(name = name, url = "https://${name.lowercase()}.example/playlist.m3u"),
            )
            storage.channels.insertAll(entries.mapIndexed { index, (title, url) ->
                Channel(
                    playlistId = playlistId,
                    name = title,
                    url = url,
                    logo = null,
                    groupTitle = "Live",
                    tvgId = null,
                    kind = ChannelKind.LIVE,
                    seriesKey = null,
                    season = null,
                    episode = null,
                    position = index,
                )
            })
            content.reconcilePlaylist(playlistId)
            return playlistId to entries.associate { (title, url) ->
                val channel = requireNotNull(storage.channels.getByUrl(playlistId, url))
                title to content.channel(channel).contentId
            }
        }

        fun close() {
            service.close()
            downloads.close()
            storage.close()
            directory.toFile().deleteRecursively()
        }
    }

    private data class Seeded(
        val firstPlaylist: Long,
        val secondPlaylist: Long,
        val firstContent: String,
        val secondContent: String,
        val deniedContent: String,
        val otherUsersContent: String,
    )

    private companion object {
        const val USER_ID = "viewer-id"
        const val OTHER_USER_ID = "other-id"
        val JSON = Json { ignoreUnknownKeys = false }

        fun user(id: String, username: String, displayName: String) = UserRow(
            id = id,
            username = username,
            normalizedUsername = username,
            displayName = displayName,
            status = UserStatus.ACTIVE,
            manualRole = UserRole.USER,
            oidcAdmin = false,
            createdAtMs = 1_000L,
            updatedAtMs = 1_000L,
            lastLoginAtMs = null,
        )

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
