package com.buco7854.opentv.server

import com.buco7854.opentv.core.epg.TextSource
import com.buco7854.opentv.core.log.CoreLog
import com.buco7854.opentv.core.model.Playlist
import com.buco7854.opentv.core.net.ConditionalFetch
import com.buco7854.opentv.core.net.ConditionalFetcher
import com.buco7854.opentv.core.net.TextBody
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
import com.buco7854.opentv.serverdata.db.DefaultPlaylistRow
import com.buco7854.opentv.serverdata.db.OpenTvServerDatabase
import com.buco7854.opentv.serverdata.db.UserPlaylistGrantRow
import com.buco7854.opentv.serverdata.db.UserRow
import kotlinx.coroutines.test.runTest
import java.net.URI
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaylistRefreshTest {
    private val admin = Actor(
        userId = "admin",
        authSessionId = "session",
        username = "admin",
        displayName = "Admin",
        roles = setOf(UserRole.USER, UserRole.ADMIN),
        authMethod = AuthMethod.PASSWORD,
        clientKind = ClientKind.BROWSER,
    )

    private val playlistLines = listOf(
        "#EXTM3U",
        "#EXTINF:-1 tvg-id=\"one\" group-title=\"Live\",Channel One",
        "https://provider.example/live/u/p/1.ts",
        "#EXTINF:-1 tvg-id=\"two\" group-title=\"Live\",Channel Two",
        "https://provider.example/live/u/p/2.ts",
    )

    @Test
    fun `a refresh that did no work does not reconcile the catalog`() = withService { fixture ->
        val id = fixture.storage.playlists.insert(
            Playlist(name = "Provider", url = "https://provider.example/playlist.m3u"),
        )

        fixture.service.refresh(admin, id, force = false)

        val reconciled = fixture.userDatabase.content().forPlaylist(id)
        assertEquals(2, reconciled.size)
        assertTrue(reconciled.all { it.lastSeenAtMs == fixture.now })
        assertEquals(1, fixture.fetches)

        fixture.now += 60_000
        fixture.service.refresh(admin, id, force = false)

        assertEquals(1, fixture.fetches)
        assertEquals(
            reconciled.map { it.contentId to it.lastSeenAtMs }.toSet(),
            fixture.userDatabase.content().forPlaylist(id)
                .map { it.contentId to it.lastSeenAtMs }.toSet(),
        )
    }

    @Test
    fun `the repository reports whether it rewrote the catalog`() = withService { fixture ->
        val ingested = fixture.storage.playlists.insert(
            Playlist(name = "Provider", url = "https://provider.example/playlist.m3u"),
        )

        assertTrue(fixture.playlists.refresh(ingested))
        assertFalse(fixture.playlists.refresh(ingested))

        val unchanged = fixture.storage.playlists.insert(
            Playlist(name = "Unchanged", url = "https://provider.example/unchanged.m3u"),
        )
        fixture.body = null
        assertFalse(fixture.playlists.refresh(unchanged))
        assertEquals(2, fixture.fetches)

        val imported = fixture.storage.playlists.insert(Playlist(name = "File", url = null))
        assertFalse(fixture.playlists.refresh(imported, force = true))
        assertFalse(fixture.playlists.refresh(playlistId = -1, force = true))
        assertEquals(2, fixture.fetches)
    }

    @Test
    fun `deleting a playlist cascades its authorization and identity state`() =
        withService { fixture ->
            val playlistId = fixture.storage.playlists.insert(
                Playlist(name = "Provider", url = null),
            )
            fixture.userDatabase.users().insert(
                UserRow(
                    "viewer",
                    "viewer",
                    "viewer",
                    "Viewer",
                    UserStatus.ACTIVE,
                    UserRole.USER,
                    false,
                    fixture.now,
                    fixture.now,
                    null,
                ),
            )
            fixture.userDatabase.grants().addDefault(DefaultPlaylistRow(playlistId))
            fixture.userDatabase.grants().grant(
                UserPlaylistGrantRow("viewer", playlistId, fixture.now),
            )
            fixture.userDatabase.content().insert(
                ContentIdentityRow(
                    "old-content",
                    playlistId,
                    0,
                    "old-fingerprint",
                    null,
                    fixture.now,
                    false,
                ),
            )
            fixture.service.delete(admin, playlistId)

            assertEquals(null, fixture.storage.playlists.get(playlistId))
            assertTrue(fixture.userDatabase.grants().defaults().isEmpty())
            assertTrue(fixture.userDatabase.grants().forUser("viewer").isEmpty())
            assertTrue(fixture.userDatabase.content().forPlaylist(playlistId).isEmpty())
            assertTrue(fixture.userDatabase.maintenance().pendingPlaylistDeletions().isEmpty())
        }

    private class Fixture(
        val storage: Storage,
        val userDatabase: OpenTvServerDatabase,
        val playlists: PlaylistRepository,
        val service: PlaylistApplicationService,
        val auth: AuthService,
    ) {
        var now = 1_000L
        var fetches = 0
        var body: List<String>? = null
    }

    private fun withService(block: suspend (Fixture) -> Unit) = runTest {
        val dir = Files.createTempDirectory("playlist-refresh")
        val persistence = createOpenTvServerStorage(dir.resolve("opentv.db").toString())
        val storage = persistence.catalog
        val userDatabase = persistence.database
        try {
            lateinit var fixture: Fixture
            val fetcher = ConditionalFetcher { _, _, _ ->
                fixture.fetches++
                val lines = fixture.body ?: return@ConditionalFetcher ConditionalFetch.NotModified
                ConditionalFetch.Success(LineBody(lines), etag = null, lastModified = null)
            }
            val log = CoreLog { _, _ -> }
            val xtreamApi = XtreamApi { _ -> error("no panel in this test") }
            val account = AccountRepository(xtreamApi, log)
            val playlists = PlaylistRepository(storage, xtreamApi, fetcher, log, account)
            val epg = EpgRepository(storage, fetcher)
            val content = ContentIdentityService(userDatabase, storage) { fixture.now }
            val config = authConfig()
            val auth = AuthService(userDatabase, config, dir)
            val settings = ServerSettings(dir, pageSize = 50)
            val service = PlaylistApplicationService(
                storage,
                playlists,
                epg,
                XtreamRepository(storage, xtreamApi, epg, account, log),
                account,
                StreamCipher(settings.streamKey),
                auth,
                content,
                UserActivityService(userDatabase, auth, content),
                userDatabase,
                DownloadManager(
                    userDatabase,
                    ServerHttp(),
                    settings,
                    dir,
                    ProviderConnections(),
                    connectionLimit = { Int.MAX_VALUE },
                ),
            )
            fixture = Fixture(storage, userDatabase, playlists, service, auth)
            fixture.body = playlistLines
            block(fixture)
        } finally {
            storage.close()
            dir.toFile().deleteRecursively()
        }
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

    private class LineBody(private val lines: List<String>) : TextBody {
        override suspend fun <T> readLines(block: suspend (Sequence<String>) -> T): T =
            block(lines.asSequence())

        override suspend fun <T> readChars(block: suspend (TextSource) -> T): T =
            error("not used")

        override fun close() = Unit
    }
}
