package com.buco7854.opentv.server

import com.buco7854.opentv.core.epg.TextSource
import com.buco7854.opentv.core.log.CoreLog
import com.buco7854.opentv.core.model.Channel
import com.buco7854.opentv.core.model.ChannelKind
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
import com.buco7854.opentv.serverdata.db.OpenTvServerDatabase
import com.buco7854.opentv.serverdata.db.UserFavoriteRow
import com.buco7854.opentv.serverdata.db.UserRow
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CatalogReferentialIntegrityTest {
    @Test
    fun content_resolution_does_not_observe_the_refresh_set_null_window() = runBlocking {
        withFixture { fixture ->
            val seeded = fixture.seedContent()
            fixture.db.users().insert(fixture.adminRow())
            fixture.db.activity().addFavorite(
                UserFavoriteRow(fixture.admin.userId, seeded.contentId, 1_000L),
            )
            val enteredCatalogGap = CountDownLatch(1)
            val finishRefresh = CountDownLatch(1)
            fixture.body = BlockingBody(
                listOf(
                    "#EXTM3U",
                    "#EXTINF:-1 group-title=\"Live\",Channel One",
                    CONTENT_URL,
                ),
                enteredCatalogGap,
                finishRefresh,
            )

            val refresh = async(Dispatchers.IO) {
                fixture.service.refresh(fixture.admin, seeded.playlistId, force = true)
            }
            assertTrue(enteredCatalogGap.await(COORDINATION_TIMEOUT_S, TimeUnit.SECONDS))
            assertEquals(null, fixture.db.content().get(seeded.contentId)?.currentChannelId)

            val resolution = async(Dispatchers.IO) {
                runCatching { fixture.content.requireChannel(seeded.contentId) }
            }
            val favoriteResolution = async(Dispatchers.IO) {
                fixture.service.resolvedFavorites(fixture.admin, seeded.playlistId)
            }
            try {
                delay(200)
                assertFalse(
                    resolution.isCompleted,
                    "content resolution must wait until refresh reconciliation closes the FK gap",
                )
                assertFalse(
                    favoriteResolution.isCompleted,
                    "favorites must not transiently resolve as empty during the FK gap",
                )
            } finally {
                finishRefresh.countDown()
            }

            refresh.await()
            val (_, rebound) = resolution.await().getOrThrow()
            assertNotEquals(seeded.channelId, rebound.id)
            assertEquals(CONTENT_URL, rebound.url)
            assertEquals(
                listOf(seeded.contentId),
                favoriteResolution.await().live.map { it.contentId },
            )
        }
    }

    @Test
    fun failed_refresh_repairs_surviving_bindings_without_deleting_favorites() = runBlocking {
        withFixture { fixture ->
            val seeded = fixture.seedContent()
            fixture.db.users().insert(fixture.adminRow())
            fixture.db.activity().addFavorite(
                UserFavoriteRow(fixture.admin.userId, seeded.contentId, 1_000L),
            )
            fixture.body = FailingAfterBatchBody()

            assertFailsWith<IOException> {
                fixture.service.refresh(fixture.admin, seeded.playlistId, force = true)
            }

            val (_, rebound) = fixture.content.requireChannel(seeded.contentId)
            assertNotEquals(seeded.channelId, rebound.id)
            assertEquals(CONTENT_URL, rebound.url)
            assertEquals(
                listOf(seeded.contentId),
                fixture.db.activity().favorites(fixture.admin.userId).map { it.contentId },
            )
        }
    }

    @Test
    fun startup_maintenance_repairs_a_refresh_interrupted_after_catalog_commit() = runBlocking {
        withFixture { fixture ->
            val seeded = fixture.seedContent()
            fixture.storage.channels.replaceKinds(
                seeded.playlistId,
                listOf(ChannelKind.LIVE),
                listOf(
                    Channel(
                        playlistId = seeded.playlistId,
                        name = "Channel One",
                        url = CONTENT_URL,
                        logo = null,
                        groupTitle = "Live",
                        tvgId = null,
                        kind = ChannelKind.LIVE,
                        seriesKey = null,
                        season = null,
                        episode = null,
                        position = 0,
                    ),
                ),
            )
            assertEquals(null, fixture.db.content().get(seeded.contentId)?.currentChannelId)

            fixture.service.reconcilePendingDeletions()

            val (_, rebound) = fixture.content.requireChannel(seeded.contentId)
            assertNotEquals(seeded.channelId, rebound.id)
            assertEquals(CONTENT_URL, rebound.url)
        }
    }

    @Test
    fun playlist_deletion_waits_for_an_in_flight_refresh_before_cascading() = runBlocking {
        withFixture { fixture ->
            val seeded = fixture.seedContent()
            fixture.db.users().insert(fixture.adminRow())
            val enteredCatalogGap = CountDownLatch(1)
            val finishRefresh = CountDownLatch(1)
            fixture.body = BlockingBody(
                listOf(
                    "#EXTM3U",
                    "#EXTINF:-1 group-title=\"Live\",Channel One",
                    CONTENT_URL,
                ),
                enteredCatalogGap,
                finishRefresh,
            )
            val refresh = async(Dispatchers.IO) {
                fixture.service.refresh(fixture.admin, seeded.playlistId, force = true)
            }
            assertTrue(enteredCatalogGap.await(COORDINATION_TIMEOUT_S, TimeUnit.SECONDS))

            val deletion = async(Dispatchers.IO) {
                runCatching { fixture.service.delete(fixture.admin, seeded.playlistId) }
            }
            try {
                delay(200)
                assertFalse(
                    deletion.isCompleted,
                    "deletion must not cascade the playlist while refresh can still insert rows",
                )
            } finally {
                finishRefresh.countDown()
            }

            refresh.await()
            deletion.await().getOrThrow()
            assertEquals(null, fixture.storage.playlists.get(seeded.playlistId))
            assertEquals(
                0,
                fixture.storage.channels.count(seeded.playlistId, ChannelKind.LIVE),
            )
            assertTrue(fixture.db.maintenance().pendingPlaylistDeletions().isEmpty())
        }
    }

    private suspend fun <T> withFixture(block: suspend (Fixture) -> T): T {
        val dir = Files.createTempDirectory("catalog-referential-integrity")
        val persistence = createOpenTvServerStorage(dir.resolve("opentv.db").toString())
        val storage = persistence.catalog
        val db = persistence.database
        try {
            lateinit var fixture: Fixture
            val fetcher = ConditionalFetcher { _, _, _ ->
                ConditionalFetch.Success(fixture.body, etag = null, lastModified = null)
            }
            val log = CoreLog { _, _ -> }
            val xtreamApi = XtreamApi { _ -> error("no panel in this test") }
            val account = AccountRepository(xtreamApi, log)
            val playlists = PlaylistRepository(storage, xtreamApi, fetcher, log, account)
            val epg = EpgRepository(storage, fetcher)
            val content = ContentIdentityService(db, storage)
            val settings = ServerSettings(dir, pageSize = 50)
            val auth = AuthService(db, authConfig(), dir)
            val service = PlaylistApplicationService(
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
                DownloadManager(
                    db,
                    ServerHttp(),
                    settings,
                    dir,
                    ProviderConnections(),
                    connectionLimit = { Int.MAX_VALUE },
                ),
            )
            fixture = Fixture(storage, db, content, service)
            return block(fixture)
        } finally {
            storage.close()
            dir.toFile().deleteRecursively()
        }
    }

    private data class SeededContent(
        val playlistId: Long,
        val channelId: Long,
        val contentId: String,
    )

    private class Fixture(
        val storage: Storage,
        val db: OpenTvServerDatabase,
        val content: ContentIdentityService,
        val service: PlaylistApplicationService,
    ) {
        val admin = Actor(
            userId = "admin",
            authSessionId = "session",
            username = "admin",
            displayName = "Admin",
            roles = setOf(UserRole.USER, UserRole.ADMIN),
            authMethod = AuthMethod.PASSWORD,
            clientKind = ClientKind.BROWSER,
        )
        var body: TextBody = LinesBody(emptyList())

        suspend fun seedContent(): SeededContent {
            val playlistId = storage.playlists.insert(
                Playlist(name = "Provider", url = "https://provider.example/playlist.m3u"),
            )
            storage.channels.insertAll(
                listOf(
                    Channel(
                        playlistId = playlistId,
                        name = "Channel One",
                        url = CONTENT_URL,
                        logo = null,
                        groupTitle = "Live",
                        tvgId = null,
                        kind = ChannelKind.LIVE,
                        seriesKey = null,
                        season = null,
                        episode = null,
                        position = 0,
                    ),
                ),
            )
            val channel = storage.channels.getByUrl(playlistId, CONTENT_URL)
                ?: error("seed channel was not inserted")
            content.reconcilePlaylist(playlistId)
            return SeededContent(playlistId, channel.id, content.channel(channel).contentId)
        }

        fun adminRow() = UserRow(
            id = admin.userId,
            username = admin.username,
            normalizedUsername = admin.username,
            displayName = admin.displayName,
            status = UserStatus.ACTIVE,
            manualRole = UserRole.ADMIN,
            oidcAdmin = false,
            createdAtMs = 1_000L,
            updatedAtMs = 1_000L,
            lastLoginAtMs = null,
        )
    }

    private open class LinesBody(private val lines: List<String>) : TextBody {
        override suspend fun <T> readLines(block: suspend (Sequence<String>) -> T): T =
            block(lines.asSequence())

        override suspend fun <T> readChars(block: suspend (TextSource) -> T): T =
            error("not used")

        override fun close() = Unit
    }

    private class BlockingBody(
        private val lines: List<String>,
        private val enteredCatalogGap: CountDownLatch,
        private val finishRefresh: CountDownLatch,
    ) : LinesBody(emptyList()) {
        override suspend fun <T> readLines(block: suspend (Sequence<String>) -> T): T =
            block(
                sequence {
                    enteredCatalogGap.countDown()
                    check(finishRefresh.await(COORDINATION_TIMEOUT_S, TimeUnit.SECONDS))
                    yieldAll(lines)
                },
            )
    }

    private class FailingAfterBatchBody : LinesBody(emptyList()) {
        override suspend fun <T> readLines(block: suspend (Sequence<String>) -> T): T =
            block(
                sequence {
                    yield("#EXTM3U")
                    repeat(500) { index ->
                        yield("#EXTINF:-1 group-title=\"Live\",Channel ${index + 1}")
                        yield(
                            if (index == 0) CONTENT_URL
                            else "https://provider.example/live/${index + 1}.ts",
                        )
                    }
                    throw IOException("provider body failed after the first committed batch")
                },
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

    private companion object {
        /**
         * Generous on purpose. These latches only elapse when the coordination under
         * test is genuinely stuck, so a long budget costs nothing when it works -- and
         * the catalog and the accounts now share one SQLite writer, so a refresh can
         * legitimately take seconds to reach the gap on a loaded machine.
         */
        const val COORDINATION_TIMEOUT_S = 60L
        const val CONTENT_URL = "https://provider.example/live/1.ts"
    }
}
