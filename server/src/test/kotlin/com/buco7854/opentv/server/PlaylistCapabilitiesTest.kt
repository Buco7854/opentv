package com.buco7854.opentv.server

import com.buco7854.opentv.contract.GroupKindRequest
import com.buco7854.opentv.contract.PlaylistOperation
import com.buco7854.opentv.contract.PlaylistOperationExecution
import com.buco7854.opentv.contract.PlaylistUpsertRequest
import com.buco7854.opentv.core.log.CoreLog
import com.buco7854.opentv.core.model.Channel
import com.buco7854.opentv.core.model.ChannelKind
import com.buco7854.opentv.core.model.Playlist
import com.buco7854.opentv.core.net.ConditionalFetch
import com.buco7854.opentv.core.net.ConditionalFetcher
import com.buco7854.opentv.core.repo.AccountRepository
import com.buco7854.opentv.core.repo.EpgRepository
import com.buco7854.opentv.core.repo.PlaylistRepository
import com.buco7854.opentv.core.repo.XtreamRepository
import com.buco7854.opentv.core.storage.Storage
import com.buco7854.opentv.core.xtream.XtreamApi
import com.buco7854.opentv.serverdata.AuthMethod
import com.buco7854.opentv.serverdata.ClientKind
import com.buco7854.opentv.serverdata.UserRole
import com.buco7854.opentv.serverdata.UserStatus
import com.buco7854.opentv.serverdata.createOpenTvServerStorage
import com.buco7854.opentv.serverdata.db.ContentIdentityRow
import com.buco7854.opentv.serverdata.db.OpenTvServerDatabase
import com.buco7854.opentv.serverdata.db.UserPlaylistGrantRow
import com.buco7854.opentv.serverdata.db.UserResumeRow
import com.buco7854.opentv.serverdata.db.UserRow
import java.net.URI
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PlaylistCapabilitiesTest {
    @Test
    fun capabilitiesArePerUserAndPerPlaylistKind() = withFixture { fixture ->
        val m3u = fixture.playlist(
            Playlist(
                name = "M3U",
                url = "https://provider.example/list.m3u",
                xtreamBase = "https://provider.example",
                xtreamUser = "user",
                xtreamPass = "pass",
            ),
        )
        val file = fixture.playlist(Playlist(name = "File", url = null))
        val xtream = fixture.playlist(
            Playlist(
                name = "Xtream",
                url = null,
                xtreamBase = "https://provider.example",
                xtreamUser = "user",
                xtreamPass = "pass",
            ),
        )
        fixture.grant(VIEWER, m3u)
        fixture.grant(VIEWER, file)
        fixture.grant(VIEWER, xtream)

        val viewerM3u = fixture.service.capabilities(fixture.viewer, m3u).byOperation()
        assertEquals(
            setOf(PlaylistOperation.CLEAR_WATCH_PROGRESS),
            viewerM3u.keys,
            "a category override is re-applied for everyone at every refresh, so it is " +
                "administration; only progress is genuinely the viewer's own",
        )
        assertEquals(
            PlaylistOperationExecution.IN_APP,
            viewerM3u.getValue(PlaylistOperation.CLEAR_WATCH_PROGRESS).execution,
        )

        val adminM3u = fixture.service.capabilities(fixture.admin, m3u).byOperation()
        assertEquals(
            setOf(
                PlaylistOperation.REFRESH,
                PlaylistOperation.EDIT,
                PlaylistOperation.DELETE,
                PlaylistOperation.CLEAR_WATCH_PROGRESS,
                PlaylistOperation.CORRECT_CATEGORY_TYPE,
                PlaylistOperation.VIEW_PROVIDER_ACCOUNT,
            ),
            adminM3u.keys,
        )
        assertEquals(
            "/browse/$m3u?manage=playlist",
            adminM3u.getValue(PlaylistOperation.REFRESH).browserPath,
        )
        assertEquals(
            "/browse/$m3u?manage=playlist",
            adminM3u.getValue(PlaylistOperation.EDIT).browserPath,
        )
        assertEquals(
            "/browse/$m3u?manage=playlist",
            adminM3u.getValue(PlaylistOperation.DELETE).browserPath,
        )
        assertEquals(
            "/account/$m3u",
            adminM3u.getValue(PlaylistOperation.VIEW_PROVIDER_ACCOUNT).browserPath,
        )
        assertEquals(
            PlaylistOperationExecution.BROWSER,
            adminM3u.getValue(PlaylistOperation.EDIT).execution,
        )

        val adminFile = fixture.service.capabilities(fixture.admin, file).byOperation()
        assertEquals(
            setOf(
                PlaylistOperation.EDIT,
                PlaylistOperation.DELETE,
                PlaylistOperation.CLEAR_WATCH_PROGRESS,
                PlaylistOperation.CORRECT_CATEGORY_TYPE,
            ),
            adminFile.keys,
            "a file import has nothing to refresh and no provider account panel",
        )

        val adminXtream = fixture.service.capabilities(fixture.admin, xtream).byOperation()
        assertEquals(
            setOf(
                PlaylistOperation.REFRESH,
                PlaylistOperation.EDIT,
                PlaylistOperation.DELETE,
                PlaylistOperation.CLEAR_WATCH_PROGRESS,
                PlaylistOperation.VIEW_PROVIDER_ACCOUNT,
            ),
            adminXtream.keys,
            "native Xtream categories come from the provider and cannot be corrected",
        )

        assertFailsWith<ForbiddenApiException> {
            fixture.service.capabilities(fixture.outsider, m3u)
        }
    }

    @Test
    fun grantedUsersCanMutateOwnPlaylistStateButRevokedUsersCannot() =
        withFixture { fixture ->
            val playlistId = fixture.playlist(
                Playlist(name = "M3U", url = "https://provider.example/list.m3u"),
            )
            fixture.grant(VIEWER, playlistId)
            val channelId = fixture.channel(playlistId, "Documentaries")
            fixture.database.content().insert(
                ContentIdentityRow(
                    contentId = "content-1",
                    playlistId = playlistId,
                    kind = ChannelKind.LIVE,
                    providerFingerprint = "fingerprint-1",
                    currentChannelId = channelId,
                    lastSeenAtMs = NOW,
                    retired = false,
                ),
            )
            fixture.database.activity().upsertResume(
                UserResumeRow(VIEWER, "content-1", 20_000, 60_000, NOW),
            )

            fixture.service.clearProgress(fixture.viewer, playlistId)
            assertNull(fixture.database.activity().resume(VIEWER, "content-1"))

            // A grant is not enough: reclassifying changes what every other viewer sees.
            assertFailsWith<ForbiddenApiException> {
                fixture.service.setGroupKind(
                    fixture.viewer,
                    playlistId,
                    GroupKindRequest("Documentaries", ChannelKind.MOVIE),
                )
            }
            fixture.grant(ADMIN, playlistId)
            fixture.service.setGroupKind(
                fixture.admin,
                playlistId,
                GroupKindRequest("Documentaries", ChannelKind.MOVIE),
            )
            assertEquals(
                ChannelKind.MOVIE,
                fixture.storage.channels.get(channelId)?.kind,
            )

            fixture.database.grants().revoke(VIEWER, playlistId)
            assertFailsWith<ForbiddenApiException> {
                fixture.service.clearProgress(fixture.viewer, playlistId)
            }
            assertFailsWith<ForbiddenApiException> {
                fixture.service.setGroupKind(
                    fixture.viewer,
                    playlistId,
                    GroupKindRequest("Documentaries", ChannelKind.LIVE),
                )
            }

        }

    @Test
    fun nativeXtreamCorrectionAndOrdinaryAdminOperationsStayServerForbidden() =
        withFixture { fixture ->
            val playlistId = fixture.playlist(
                Playlist(
                    name = "Xtream",
                    url = null,
                    xtreamBase = "https://provider.example",
                    xtreamUser = "user",
                    xtreamPass = "pass",
                ),
            )
            fixture.grant(VIEWER, playlistId)

            assertFailsWith<ForbiddenApiException> {
                fixture.service.setGroupKind(
                    fixture.viewer,
                    playlistId,
                    GroupKindRequest("Provider category", ChannelKind.MOVIE),
                )
            }
            // Even an administrator cannot: the provider owns these categories.
            assertFailsWith<IllegalArgumentException> {
                fixture.service.setGroupKind(
                    fixture.admin,
                    playlistId,
                    GroupKindRequest("Provider category", ChannelKind.MOVIE),
                )
            }
            assertFailsWith<ForbiddenApiException> {
                fixture.service.refresh(fixture.viewer, playlistId, force = true)
            }
            assertFailsWith<ForbiddenApiException> {
                fixture.service.update(
                    fixture.viewer,
                    playlistId,
                    PlaylistUpsertRequest(mode = "xtream", name = "Renamed"),
                )
            }
            assertFailsWith<ForbiddenApiException> {
                fixture.service.delete(fixture.viewer, playlistId)
            }
            assertFailsWith<ForbiddenApiException> {
                fixture.service.account(fixture.viewer, playlistId, force = true)
            }
        }

    @Test
    fun aStaleAdminSnapshotCannotAdvertiseBrowserOperationsOrBypassGrants() =
        withFixture { fixture ->
            val playlistId = fixture.playlist(
                Playlist(name = "M3U", url = "https://provider.example/list.m3u"),
            )
            fixture.grant(ADMIN, playlistId)
            val stored = requireNotNull(fixture.database.users().get(ADMIN))
            fixture.database.users().update(
                stored.copy(manualRole = UserRole.USER, updatedAtMs = NOW + 1),
            )

            val operations = fixture.service.capabilities(fixture.admin, playlistId).byOperation()
            assertEquals(
                setOf(PlaylistOperation.CLEAR_WATCH_PROGRESS),
                operations.keys,
                "a demoted administrator loses category correction too, not just the " +
                    "browser-delegated operations",
            )

            fixture.database.grants().revoke(ADMIN, playlistId)
            assertFailsWith<ForbiddenApiException> {
                fixture.service.capabilities(fixture.admin, playlistId)
            }
            assertFailsWith<ForbiddenApiException> {
                fixture.service.refresh(fixture.admin, playlistId, force = true)
            }
        }

    private inner class Fixture(
        val storage: Storage,
        val database: OpenTvServerDatabase,
        val service: PlaylistApplicationService,
    ) {
        val admin = actor(ADMIN, admin = true)
        val viewer = actor(VIEWER)
        val outsider = actor(OUTSIDER)

        suspend fun playlist(playlist: Playlist): Long = storage.playlists.insert(playlist)

        suspend fun grant(userId: String, playlistId: Long) {
            database.grants().grant(UserPlaylistGrantRow(userId, playlistId, NOW))
        }

        suspend fun channel(playlistId: Long, groupTitle: String): Long {
            val url = "https://provider.example/live/$playlistId"
            storage.channels.insertAll(
                listOf(
                    Channel(
                        playlistId = playlistId,
                        name = "Channel",
                        url = url,
                        logo = null,
                        groupTitle = groupTitle,
                        tvgId = null,
                        kind = ChannelKind.LIVE,
                        seriesKey = null,
                        season = null,
                        episode = null,
                        position = 0,
                    ),
                ),
            )
            return requireNotNull(storage.channels.getByUrl(playlistId, url)).id
        }
    }

    private fun withFixture(block: suspend (Fixture) -> Unit) = runTest {
        val directory = Files.createTempDirectory("playlist-capabilities")
        val persistence = createOpenTvServerStorage(directory.resolve("opentv.db").toString())
        val storage = persistence.catalog
        val database = persistence.database
        try {
            listOf(
                user(ADMIN, UserRole.ADMIN),
                user(VIEWER, UserRole.USER),
                user(OUTSIDER, UserRole.USER),
            ).forEach { database.users().insert(it) }
            val fetcher = ConditionalFetcher { _, _, _ -> ConditionalFetch.NotModified }
            val log = CoreLog { _, _ -> }
            val xtreamApi = XtreamApi { _ -> error("provider access is not used") }
            val account = AccountRepository(xtreamApi, log)
            val playlists = PlaylistRepository(storage, xtreamApi, fetcher, log, account)
            val epg = EpgRepository(storage, fetcher)
            val content = ContentIdentityService(database, storage) { NOW }
            val auth = AuthService(database, authConfig(), directory)
            val settings = ServerSettings(directory, pageSize = 50)
            val service = PlaylistApplicationService(
                storage,
                playlists,
                epg,
                XtreamRepository(storage, xtreamApi, epg, account, log),
                account,
                StreamCipher(settings.streamKey),
                auth,
                content,
                UserActivityService(database, auth, content) { NOW },
                database,
                DownloadManager(
                    database,
                    ServerHttp(),
                    settings,
                    directory,
                    ProviderConnections(),
                    connectionLimit = { Int.MAX_VALUE },
                ),
            )
            block(Fixture(storage, database, service))
        } finally {
            storage.close()
            directory.toFile().deleteRecursively()
        }
    }

    private fun user(id: String, role: String) = UserRow(
        id = id,
        username = id,
        normalizedUsername = id,
        displayName = id,
        status = UserStatus.ACTIVE,
        manualRole = role,
        oidcAdmin = false,
        createdAtMs = NOW,
        updatedAtMs = NOW,
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

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val ADMIN = "admin"
        const val VIEWER = "viewer"
        const val OUTSIDER = "outsider"

        fun actor(id: String, admin: Boolean = false) = Actor(
            userId = id,
            authSessionId = "session-$id",
            username = id,
            displayName = id,
            roles = if (admin) setOf(UserRole.USER, UserRole.ADMIN) else setOf(UserRole.USER),
            authMethod = AuthMethod.PASSWORD,
            clientKind = ClientKind.NATIVE,
        )
    }
}

private fun com.buco7854.opentv.contract.PlaylistCapabilitiesDto.byOperation() =
    operations.associateBy { it.operation }
