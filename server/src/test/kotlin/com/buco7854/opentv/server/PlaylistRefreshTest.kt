package com.buco7854.opentv.server

import com.buco7854.opentv.contract.PlaylistRefreshJobDto
import com.buco7854.opentv.contract.PlaylistRefreshJobStatus
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
import com.buco7854.opentv.serverdata.db.ContentIdentityRow
import com.buco7854.opentv.serverdata.db.DefaultPlaylistRow
import com.buco7854.opentv.serverdata.db.OpenTvServerDatabase
import com.buco7854.opentv.serverdata.db.UserPlaylistGrantRow
import com.buco7854.opentv.serverdata.db.UserRow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.URI
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
    fun `a long refresh exposes an observable job until its typed result is ready`() =
        withService { fixture ->
            val id = fixture.storage.playlists.insert(
                Playlist(name = "Provider", url = "https://provider.example/playlist.m3u"),
            )

            val started = fixture.service.startRefresh(admin, id, force = true)
            assertEquals(PlaylistRefreshJobStatus.QUEUED, started.status)

            val finished = withContext(Dispatchers.IO) {
                withTimeout(5_000) {
                    var current: PlaylistRefreshJobDto
                    do {
                        delay(10)
                        current = fixture.service.refreshStatus(admin, id, started.id)
                    } while (
                        current.status == PlaylistRefreshJobStatus.QUEUED ||
                        current.status == PlaylistRefreshJobStatus.RUNNING
                    )
                    current
                }
            }

            assertEquals(PlaylistRefreshJobStatus.SUCCEEDED, finished.status)
            assertTrue(requireNotNull(finished.result).catalogChanged)
            assertEquals(2, finished.result?.playlist?.channelCount)
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
    fun `refreshing one playlist does not wait on another playlist's in-flight refresh`() =
        withService { fixture ->
            // Room's suspending calls run on real threads regardless of the test dispatcher,
            // so this section needs real time (like the job-polling test above) rather than
            // the virtual clock `runTest` otherwise uses.
            withContext(Dispatchers.IO) {
                val slowUrl = "https://provider.example/slow.m3u"
                val fastUrl = "https://provider.example/fast.m3u"
                val slowId = fixture.storage.playlists.insert(Playlist(name = "Slow", url = slowUrl))
                val fastId = fixture.storage.playlists.insert(Playlist(name = "Fast", url = fastUrl))

                fixture.gateUrl = slowUrl
                fixture.gate = CompletableDeferred()

                val slowRefresh = async { fixture.playlists.refresh(slowId) }
                // Wait for the slow refresh to actually enter its (now blocked) fetch.
                withTimeout(5_000) {
                    while (fixture.fetches == 0) delay(10)
                }

                // A single global lock would make this wait behind the slow playlist above.
                val fastResult = withTimeout(5_000) { fixture.playlists.refresh(fastId) }

                assertTrue(fastResult)
                assertFalse(slowRefresh.isCompleted)

                fixture.gate?.complete(Unit)
                assertTrue(withTimeout(5_000) { slowRefresh.await() })
            }
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

        /** Lets a test block one URL's fetch mid-flight while another proceeds. */
        var gateUrl: String? = null
        var gate: CompletableDeferred<Unit>? = null
    }

    private fun withService(block: suspend (Fixture) -> Unit) = runTest {
        val persistence = ServerTestPersistence("playlist-refresh")
        val dir = persistence.directory
        val storage = persistence.storage
        val userDatabase = persistence.database
        try {
            userDatabase.users().insert(
                UserRow(
                    "admin",
                    "admin",
                    "admin",
                    "Admin",
                    UserStatus.ACTIVE,
                    UserRole.ADMIN,
                    false,
                    1_000L,
                    1_000L,
                    null,
                ),
            )
            lateinit var fixture: Fixture
            val fetcher = ConditionalFetcher { url, _, _ ->
                fixture.fetches++
                if (url == fixture.gateUrl) fixture.gate?.await()
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
            val downloads = DownloadManager(
                userDatabase,
                ServerHttp(),
                settings,
                dir,
                ProviderConnections(),
                connectionLimit = { Int.MAX_VALUE },
            )
            persistence.closeBeforeDatabase(downloads::close)
            val createdService = PlaylistApplicationService(
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
                downloads,
            )
            persistence.closeBeforeDatabase(createdService::close)
            fixture = Fixture(storage, userDatabase, playlists, createdService, auth)
            fixture.body = playlistLines
            block(fixture)
        } finally {
            persistence.close()
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
