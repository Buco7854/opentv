package com.buco7854.opentv.serverdata

import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import com.buco7854.opentv.core.model.Playlist
import com.buco7854.opentv.serverdata.db.UserRow
import com.buco7854.opentv.serverdata.db.favoriteSeriesListings
import java.nio.file.Files
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue

class ServerQueryScaleTest {
    @Test
    fun m3uSeriesFavoritesProbeOneSeriesInsteadOfScanningAllEpisodes() = runTest {
        val requestedRows = System.getenv("OPENTV_M3U_FAVORITES_BENCHMARK_ROWS")?.toIntOrNull()
        assumeTrue(
            "Set OPENTV_M3U_FAVORITES_BENCHMARK_ROWS to run the benchmark",
            requestedRows != null,
        )
        val episodeRows = requireNotNull(requestedRows)
        val episodesPerSeries = 100
        require(episodeRows % episodesPerSeries == 0)
        val seriesCount = episodeRows / episodesPerSeries
        val favoriteCount = minOf(100, seriesCount)
        withPersistence { persistence ->
            val db = persistence.database
            val playlistId = persistence.catalog.playlists.insert(
                Playlist(name = "M3U Series", url = null),
            )
            db.users().insert(user())
            db.useWriterConnection { connection ->
                connection.usePrepared(
                    """
                    WITH RECURSIVE fixture(n) AS (
                        VALUES(0) UNION ALL SELECT n + 1 FROM fixture WHERE n + 1 < $episodeRows
                    )
                    INSERT INTO channels(
                        playlistId, name, url, logo, groupTitle, tvgId, kind, seriesKey,
                        season, episode, position, xtreamStreamId, catchupDays, catchupSource,
                        description, durationSecs, airDate, searchName
                    )
                    SELECT $playlistId, 'Episode ' || n, 'https://fixture.invalid/' || n,
                           NULL, 'Drama', NULL, 2,
                           'Show ' || (n / $episodesPerSeries), 1,
                           (n % $episodesPerSeries) + 1, n, NULL, 0, NULL, NULL, NULL, NULL,
                           'episode ' || n
                    FROM fixture
                    """.trimIndent(),
                ) { it.step() }
                connection.usePrepared(
                    """
                    WITH RECURSIVE fixture(n) AS (
                        VALUES(0) UNION ALL SELECT n + 1 FROM fixture WHERE n + 1 < $favoriteCount
                    )
                    INSERT INTO content_identities(
                        contentId, playlistId, kind, providerFingerprint,
                        currentChannelId, lastSeenAtMs, retired
                    )
                    SELECT 'm3u-series-' || n, $playlistId, 2, 'fingerprint-' || n,
                           NULL, 1, 0 FROM fixture
                    """.trimIndent(),
                ) { it.step() }
                connection.usePrepared(
                    """
                    WITH RECURSIVE fixture(n) AS (
                        VALUES(0) UNION ALL SELECT n + 1 FROM fixture WHERE n + 1 < $favoriteCount
                    )
                    INSERT INTO content_series_locators(contentId, playlistId, sourceKind, sourceKey)
                    SELECT 'm3u-series-' || n, $playlistId, 'm3u', 'Show ' || n FROM fixture
                    """.trimIndent(),
                ) { it.step() }
                connection.usePrepared(
                    """
                    WITH RECURSIVE fixture(n) AS (
                        VALUES(0) UNION ALL SELECT n + 1 FROM fixture WHERE n + 1 < $favoriteCount
                    )
                    INSERT INTO user_favorites(userId, contentId, addedAtMs)
                    SELECT 'u1', 'm3u-series-' || n, n FROM fixture
                    """.trimIndent(),
                ) { it.step() }
            }

            var rows = 0
            val elapsedMs = measureTimeMillis {
                rows = db.favoriteSeriesListings("u1").size
            }
            println(
                "M3U_FAVORITE_SERIES episodeRows=$episodeRows favorites=$favoriteCount " +
                    "resultRows=$rows elapsedMs=$elapsedMs",
            )
            assertEquals(favoriteCount, rows)
        }
    }

    @Test
    fun adminUserListingAvoidsCredentialAndGrantQueriesPerUser() = runTest {
        val requestedRows = System.getenv("OPENTV_ADMIN_USERS_BENCHMARK_ROWS")?.toIntOrNull()
        assumeTrue(
            "Set OPENTV_ADMIN_USERS_BENCHMARK_ROWS to run the benchmark",
            requestedRows != null,
        )
        val userCount = requireNotNull(requestedRows)
        withPersistence { persistence ->
            val db = persistence.database
            val playlistId = persistence.catalog.playlists.insert(
                Playlist(name = "Shared", url = null),
            )
            db.useWriterConnection { connection ->
                connection.usePrepared(
                    """
                    WITH RECURSIVE fixture(n) AS (
                        VALUES(1) UNION ALL SELECT n + 1 FROM fixture WHERE n < $userCount
                    )
                    INSERT INTO users(
                        id, username, normalizedUsername, displayName, status, manualRole,
                        oidcAdmin, createdAtMs, updatedAtMs, lastLoginAtMs
                    )
                    SELECT 'user-' || n, 'User ' || n, printf('user-%06d', n),
                           'User ' || n, 'ACTIVE', 'USER', 0, n, n, NULL FROM fixture
                    """.trimIndent(),
                ) { it.step() }
                connection.usePrepared(
                    """
                    WITH RECURSIVE fixture(n) AS (
                        VALUES(1) UNION ALL SELECT n + 1 FROM fixture WHERE n < $userCount
                    )
                    INSERT INTO user_playlist_grants(userId, playlistId, grantedAtMs)
                    SELECT 'user-' || n, $playlistId, n FROM fixture
                    """.trimIndent(),
                ) { it.step() }
            }

            val users = db.users().all()
            val legacyMs = measureTimeMillis {
                users.forEach { user ->
                    db.credentials().password(user.id)
                    db.credentials().confirmedTotp(user.id)
                    db.credentials().webAuthn(user.id)
                    db.oidc().forUser(user.id)
                    db.grants().forUser(user.id)
                }
            }
            var methodRows = 0
            var grantRows = 0
            val bulkMs = measureTimeMillis {
                methodRows = db.users().credentialMethods().size
                grantRows = db.grants().allUserGrants().size
                db.users().all()
            }
            println(
                "ADMIN_USER_LIST users=$userCount beforeQueries=${userCount * 5 + 1} " +
                    "beforeMs=$legacyMs afterQueries=3 afterMs=$bulkMs",
            )
            assertEquals(userCount, methodRows)
            assertEquals(userCount, grantRows)
            assertTrue(bulkMs < legacyMs, "bulk=${bulkMs}ms legacy=${legacyMs}ms")
        }
    }

    @Test
    fun catalogWideGuideSurfacesMaterializeOneRowPerChannel() = runTest {
        val requestedRows = System.getenv("OPENTV_GUIDE_SURFACE_BENCHMARK_ROWS")?.toIntOrNull()
        assumeTrue(
            "Set OPENTV_GUIDE_SURFACE_BENCHMARK_ROWS to run the benchmark",
            requestedRows != null,
        )
        val programmeRows = requireNotNull(requestedRows)
        val channelCount = minOf(100_000, programmeRows)
        require(programmeRows % channelCount == 0)
        val slots = programmeRows / channelCount
        val now = (slots / 2L) * 1_000L + 500L
        withPersistence { persistence ->
            val db = persistence.database
            val playlistId = persistence.catalog.playlists.insert(
                Playlist(name = "Guide", url = null),
            )
            db.useWriterConnection { connection ->
                connection.usePrepared(
                    """
                    WITH RECURSIVE fixture(n) AS (
                        VALUES(0) UNION ALL SELECT n + 1 FROM fixture WHERE n + 1 < $programmeRows
                    )
                    INSERT INTO programmes(
                        playlistId, tvgId, title, description, startMs, endMs
                    )
                    SELECT $playlistId,
                           'tvg-' || (n % $channelCount),
                           'Programme ' || n,
                           NULL,
                           (n / $channelCount) * 1000,
                           ((n / $channelCount) + 1) * 1000
                    FROM fixture
                    """.trimIndent(),
                ) { it.step() }
            }

            var nowRows = 0
            val nowMs = measureTimeMillis {
                nowRows = db.epgDao().nowAiring(playlistId, now).size
            }
            var guideIds = 0
            val idsMs = measureTimeMillis {
                guideIds = db.epgDao().observeGuideIds(playlistId).first().size
            }
            println(
                "GUIDE_CATALOG_SURFACES programmeRows=$programmeRows channels=$channelCount " +
                    "nowAiringRows=$nowRows nowAiringMs=$nowMs guideIdRows=$guideIds guideIdsMs=$idsMs",
            )
            assertEquals(channelCount, nowRows)
            assertEquals(channelCount, guideIds)
        }
    }

    @Test
    fun accountWideSeriesFavoritesScaleWithFavoritesNotCatalogs() = runTest {
        val catalogRows = System.getenv("OPENTV_FAVORITES_BENCHMARK_ROWS")?.toIntOrNull()
        assumeTrue(
            "Set OPENTV_FAVORITES_BENCHMARK_ROWS to run the benchmark",
            catalogRows != null,
        )
        val totalCatalogRows = requireNotNull(catalogRows)
        val playlistCount = 20
        require(totalCatalogRows % playlistCount == 0)
        val rowsPerPlaylist = totalCatalogRows / playlistCount
        val favoritesPerPlaylist = minOf(50, rowsPerPlaylist)

        withPersistence { persistence ->
            val db = persistence.database
            db.users().insert(user())
            val playlistIds = List(playlistCount) { index ->
                persistence.catalog.playlists.insert(Playlist(name = "Provider $index", url = null))
            }
            db.useWriterConnection { connection ->
                playlistIds.forEach { playlistId ->
                    connection.usePrepared(
                        """
                        WITH RECURSIVE fixture(n) AS (
                            VALUES(1) UNION ALL SELECT n + 1 FROM fixture WHERE n < $rowsPerPlaylist
                        )
                        INSERT INTO xtream_series(
                            playlistId, seriesId, name, categoryName, cover, plot, castNames,
                            genre, rating, episodesFetchedAtMs, searchName
                        )
                        SELECT $playlistId, n, 'Series ' || n, 'Drama', NULL, NULL, NULL,
                               NULL, NULL, 0, 'series ' || n
                        FROM fixture
                        """.trimIndent(),
                    ) { it.step() }
                    connection.usePrepared(
                        """
                        WITH RECURSIVE fixture(n) AS (
                            VALUES(1) UNION ALL
                            SELECT n + 1 FROM fixture WHERE n < $favoritesPerPlaylist
                        )
                        INSERT INTO content_identities(
                            contentId, playlistId, kind, providerFingerprint,
                            currentChannelId, lastSeenAtMs, retired
                        )
                        SELECT 'series-$playlistId-' || n, $playlistId, 2,
                               'fingerprint-$playlistId-' || n, NULL, 1, 0
                        FROM fixture
                        """.trimIndent(),
                    ) { it.step() }
                    connection.usePrepared(
                        """
                        WITH RECURSIVE fixture(n) AS (
                            VALUES(1) UNION ALL
                            SELECT n + 1 FROM fixture WHERE n < $favoritesPerPlaylist
                        )
                        INSERT INTO content_series_locators(contentId, playlistId, sourceKind, sourceKey)
                        SELECT 'series-$playlistId-' || n, $playlistId, 'xtream', CAST(n AS TEXT)
                        FROM fixture
                        """.trimIndent(),
                    ) { it.step() }
                    connection.usePrepared(
                        """
                        WITH RECURSIVE fixture(n) AS (
                            VALUES(1) UNION ALL
                            SELECT n + 1 FROM fixture WHERE n < $favoritesPerPlaylist
                        )
                        INSERT INTO user_favorites(userId, contentId, addedAtMs)
                        SELECT 'u1', 'series-$playlistId-' || n, n FROM fixture
                        """.trimIndent(),
                    ) { it.step() }
                }
            }

            // This is a lower bound for the former implementation: it materialized both catalog
            // queries per playlist and also issued batched identity lookups for every series row.
            var legacyRows = 0
            val legacyMs = measureTimeMillis {
                db.useReaderConnection { connection ->
                    playlistIds.forEach { playlistId ->
                        connection.usePrepared(
                            "SELECT * FROM xtream_series WHERE playlistId = ?",
                        ) { statement ->
                            statement.bindLong(1, playlistId)
                            while (statement.step()) legacyRows++
                        }
                        connection.usePrepared(
                            "SELECT seriesKey, COUNT(*) FROM channels " +
                                "WHERE playlistId = ? AND kind = 2 GROUP BY seriesKey",
                        ) { statement ->
                            statement.bindLong(1, playlistId)
                            while (statement.step()) legacyRows++
                        }
                    }
                }
            }
            lateinit var resolved: List<com.buco7854.opentv.serverdata.db.FavoriteSeriesListingRow>
            val indexedMs = measureTimeMillis {
                resolved = db.favoriteSeriesListings("u1")
            }

            val favoriteRows = playlistCount * favoritesPerPlaylist
            println(
                "ACCOUNT_FAVORITE_SERIES catalogRows=$totalCatalogRows " +
                    "favoriteRows=$favoriteRows beforeRows=$legacyRows beforeQueriesAtLeast=" +
                    "${playlistCount * 2} beforeMs=$legacyMs afterRows=${resolved.size} " +
                    "afterQueries=1 afterMs=$indexedMs",
            )
            assertEquals(totalCatalogRows, legacyRows)
            assertEquals(favoriteRows, resolved.size)
            assertTrue(indexedMs < legacyMs, "indexed=${indexedMs}ms legacy=${legacyMs}ms")
        }
    }

    @Test
    fun downloadListingAvoidsOneBlobQueryPerAssociation() = runTest {
        val requestedRows = System.getenv("OPENTV_DOWNLOAD_LISTING_BENCHMARK_ROWS")?.toIntOrNull()
        assumeTrue(
            "Set OPENTV_DOWNLOAD_LISTING_BENCHMARK_ROWS to run the benchmark",
            requestedRows != null,
        )
        val rowCount = requireNotNull(requestedRows)
        withPersistence { persistence ->
            val db = persistence.database
            val playlistId = persistence.catalog.playlists.insert(
                Playlist(name = "Downloads", url = null),
            )
            db.users().insert(user())
            db.useWriterConnection { connection ->
                connection.usePrepared(
                    """
                    WITH RECURSIVE fixture(n) AS (
                        VALUES(1) UNION ALL SELECT n + 1 FROM fixture WHERE n < $rowCount
                    )
                    INSERT INTO content_identities(
                        contentId, playlistId, kind, providerFingerprint,
                        currentChannelId, lastSeenAtMs, retired
                    )
                    SELECT 'content-' || n, $playlistId, 1, 'fingerprint-' || n, NULL, 1, 0
                    FROM fixture
                    """.trimIndent(),
                ) { it.step() }
                connection.usePrepared(
                    """
                    WITH RECURSIVE fixture(n) AS (
                        VALUES(1) UNION ALL SELECT n + 1 FROM fixture WHERE n < $rowCount
                    )
                    INSERT INTO download_blobs(
                        id, contentId, title, sourceUrl, filePath, status, totalBytes,
                        downloadedBytes, error, createdAtMs, updatedAtMs
                    )
                    SELECT 'blob-' || n, 'content-' || n, 'Title ' || n,
                           'https://fixture.invalid/' || n, '/tmp/' || n, 'DONE',
                           100, 100, NULL, n, n FROM fixture
                    """.trimIndent(),
                ) { it.step() }
                connection.usePrepared(
                    """
                    WITH RECURSIVE fixture(n) AS (
                        VALUES(1) UNION ALL SELECT n + 1 FROM fixture WHERE n < $rowCount
                    )
                    INSERT INTO user_downloads(
                        id, userId, blobId, active, suspended, createdAtMs, updatedAtMs
                    )
                    SELECT 'download-' || n, 'u1', 'blob-' || n, 1, 0, n, n FROM fixture
                    """.trimIndent(),
                ) { it.step() }
            }

            var legacyCount = 0
            val legacyMs = measureTimeMillis {
                db.downloads().forUser("u1").forEach { association ->
                    if (db.downloads().blob(association.blobId) != null) legacyCount++
                }
            }
            var joinedCount = 0
            val joinedMs = measureTimeMillis {
                joinedCount = db.downloads().listingForUser("u1").size
            }
            println(
                "DOWNLOAD_LISTING rows=$rowCount beforeQueries=${rowCount + 1} " +
                    "beforeMs=$legacyMs afterQueries=1 afterMs=$joinedMs",
            )
            assertEquals(rowCount, legacyCount)
            assertEquals(rowCount, joinedCount)
            assertTrue(joinedMs < legacyMs, "joined=${joinedMs}ms legacy=${legacyMs}ms")
        }
    }

    private suspend fun withPersistence(block: suspend (OpenTvServerStorage) -> Unit) {
        val dir = Files.createTempDirectory("opentv-query-scale")
        val persistence = createOpenTvServerStorage(dir.resolve("opentv.db").toString())
        try {
            block(persistence)
        } finally {
            persistence.catalog.close()
            dir.toFile().deleteRecursively()
        }
    }

    private fun user() = UserRow(
        "u1", "Alice", "alice", "Alice", UserStatus.ACTIVE, UserRole.USER,
        false, 1, 1, null,
    )
}
