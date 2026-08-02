package com.buco7854.opentv.serverdata

import androidx.room.immediateTransaction
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import com.buco7854.opentv.core.model.Channel
import com.buco7854.opentv.core.model.ChannelKind
import com.buco7854.opentv.core.model.Playlist
import com.buco7854.opentv.core.model.XtreamSeries
import com.buco7854.opentv.data.db.ProgrammeRow
import com.buco7854.opentv.serverdata.db.AuthSessionRow
import com.buco7854.opentv.serverdata.db.CONTENT_IDENTITY_WRITE_CHUNK_SIZE
import com.buco7854.opentv.serverdata.db.ContentIdentityRow
import com.buco7854.opentv.serverdata.db.ContentSeriesLocatorRow
import com.buco7854.opentv.serverdata.db.GUIDE_WRITE_CHUNK_SIZE
import com.buco7854.opentv.serverdata.db.UserRow
import com.buco7854.opentv.serverdata.db.deleteCatalogPlaylist
import com.buco7854.opentv.serverdata.db.deleteGuideFromInChunks
import com.buco7854.opentv.serverdata.db.writeContentIdentityReconciliation
import com.buco7854.opentv.serverdata.db.writeContentSeriesLocators
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MergedDatabaseContentionTest {
    @Test
    fun guideIngestionBatchDoesNotStarveSessionWrites() = runTest {
        withPersistence { persistence ->
            val storage = persistence.catalog
            val db = persistence.database
            val playlistId = storage.playlists.insert(Playlist(name = "Guide", url = null))
            db.users().insert(user())
            db.sessions().insert(session())
            val batch = List(GUIDE_INGEST_BATCH_ROWS) { index ->
                ProgrammeRow(
                    playlistId = playlistId,
                    tvgId = "tvg-$index",
                    title = "Programme $index",
                    description = null,
                    startMs = index * 1_000L,
                    endMs = (index + 1L) * 1_000L,
                )
            }

            val started = CompletableDeferred<Unit>()
            val insert = async(Dispatchers.IO) {
                db.useWriterConnection { connection ->
                    connection.immediateTransaction {
                        started.complete(Unit)
                        db.epgDao().insertAll(batch)
                    }
                }
            }
            started.await()
            val touch = async(Dispatchers.IO) {
                measureTimeMillis {
                    assertEquals(1, db.sessions().touch("session", 2, 1_000_002))
                }
            }
            insert.await()
            val touchMs = touch.await()

            println("MERGED_DB_GUIDE_INSERT rows=$GUIDE_INGEST_BATCH_ROWS touchMs=$touchMs")
            assertEquals(
                GUIDE_INGEST_BATCH_ROWS.toLong(),
                db.useReaderConnection { connection ->
                    connection.usePrepared("SELECT COUNT(*) FROM programmes WHERE playlistId = ?") {
                        it.bindLong(1, playlistId)
                        check(it.step())
                        it.getLong(0)
                    }
                },
            )
            assertTrue(
                touchMs < MAX_GUIDE_CHUNK_WRITE_MS,
                "A guide ingest batch blocked a session write for ${touchMs}ms",
            )
        }
    }

    @Test
    fun realisticCatalogReplacementContentionBenchmark() = runTest {
        val requestedRows = System.getenv("OPENTV_CATALOG_CONTENTION_ROWS")?.toIntOrNull()
        assumeTrue("Set OPENTV_CATALOG_CONTENTION_ROWS to run the benchmark", requestedRows != null)
        val rowCount = requireNotNull(requestedRows)
        withPersistence { persistence ->
            val storage = persistence.catalog
            val db = persistence.database
            val playlistId = storage.playlists.insert(Playlist(name = "Catalog", url = null))
            db.users().insert(user())
            db.sessions().insert(session())
            storage.channels.insertAll(channels(playlistId, rowCount, "Old"))
            db.content().insertAll(
                List(rowCount) { index ->
                    ContentIdentityRow(
                        contentId = "content-$index",
                        playlistId = playlistId,
                        kind = index % 3,
                        providerFingerprint = "fingerprint-$index",
                        currentChannelId = index + 1L,
                        lastSeenAtMs = 1,
                        retired = false,
                    )
                },
            )
            val replacement = channels(playlistId, rowCount, "New")

            val started = CompletableDeferred<Unit>()
            val swap = async(Dispatchers.IO) {
                db.useWriterConnection { connection ->
                    connection.immediateTransaction {
                        started.complete(Unit)
                        storage.channels.replaceKinds(
                            playlistId,
                            listOf(ChannelKind.LIVE, ChannelKind.MOVIE, ChannelKind.SERIES),
                            replacement,
                        )
                    }
                }
            }
            started.await()
            val touch = async(Dispatchers.IO) {
                measureTimeMillis {
                    assertEquals(1, db.sessions().touch("session", 2, 1_000_002))
                }
            }
            swap.await()
            val touchMs = touch.await()

            println("MERGED_DB_CATALOG_CONTENTION rows=$rowCount touchMs=$touchMs")
            assertTrue(
                touchMs < MAX_CATALOG_REPLACEMENT_WRITE_MS,
                "A $rowCount-row catalog replacement blocked a session write for ${touchMs}ms",
            )
            assertEquals(rowCount, totalChannelCount(storage, playlistId))
        }
    }

    @Test
    fun realisticIdentityReconciliationContentionBenchmark() = runTest {
        val requestedRows = System.getenv("OPENTV_IDENTITY_CONTENTION_ROWS")?.toIntOrNull()
        assumeTrue("Set OPENTV_IDENTITY_CONTENTION_ROWS to run the benchmark", requestedRows != null)
        val rowCount = requireNotNull(requestedRows)
        withPersistence { persistence ->
            val storage = persistence.catalog
            val db = persistence.database
            val playlistId = storage.playlists.insert(Playlist(name = "Identities", url = null))
            db.users().insert(user())
            db.sessions().insert(session())
            storage.channels.insertAll(channels(playlistId, rowCount, "Identity"))
            val rebound = List(rowCount) { index ->
                ContentIdentityRow(
                    contentId = "identity-$index",
                    playlistId = playlistId,
                    kind = index % 3,
                    providerFingerprint = "fingerprint-$index",
                    currentChannelId = index + 1L,
                    lastSeenAtMs = 2,
                    retired = false,
                )
            }
            db.content().insertAll(rebound.map { it.copy(currentChannelId = null, lastSeenAtMs = 1) })

            val started = CompletableDeferred<Unit>()
            val reconciliation = async(Dispatchers.IO) {
                db.useWriterConnection { connection ->
                    connection.immediateTransaction {
                        started.complete(Unit)
                        db.content().updateAll(
                            rebound.take(CONTENT_IDENTITY_WRITE_CHUNK_SIZE),
                        )
                    }
                }
                db.writeContentIdentityReconciliation(
                    inserts = emptyList(),
                    updates = rebound.drop(CONTENT_IDENTITY_WRITE_CHUNK_SIZE),
                    playlistId = playlistId,
                    retireMissingBeforeMs = null,
                )
            }
            started.await()
            val touch = async(Dispatchers.IO) {
                measureTimeMillis {
                    assertEquals(1, db.sessions().touch("session", 2, 1_000_002))
                }
            }
            reconciliation.await()
            val touchMs = touch.await()

            println("MERGED_DB_IDENTITY_CONTENTION rows=$rowCount touchMs=$touchMs")
            assertTrue(
                touchMs < MAX_IDENTITY_WRITE_MS,
                "A chunked $rowCount-row identity reconciliation blocked a session write for " +
                    "${touchMs}ms",
            )
        }
    }

    @Test
    fun realisticIdentityRetirementContentionBenchmark() = runTest {
        val requestedRows = System.getenv("OPENTV_IDENTITY_RETIRE_CONTENTION_ROWS")?.toIntOrNull()
        assumeTrue(
            "Set OPENTV_IDENTITY_RETIRE_CONTENTION_ROWS to run the benchmark",
            requestedRows != null,
        )
        val rowCount = requireNotNull(requestedRows)
        withPersistence { persistence ->
            val storage = persistence.catalog
            val db = persistence.database
            val playlistId = storage.playlists.insert(Playlist(name = "Identities", url = null))
            db.users().insert(user())
            db.sessions().insert(session())
            storage.channels.insertAll(channels(playlistId, rowCount, "Identity"))
            db.content().insertAll(
                List(rowCount) { index ->
                    ContentIdentityRow(
                        contentId = "identity-$index",
                        playlistId = playlistId,
                        kind = index % 3,
                        providerFingerprint = "fingerprint-$index",
                        currentChannelId = index + 1L,
                        lastSeenAtMs = 1,
                        retired = false,
                    )
                },
            )

            val started = CompletableDeferred<Unit>()
            val retirement = async(Dispatchers.IO) {
                db.useWriterConnection { connection ->
                    connection.immediateTransaction {
                        started.complete(Unit)
                        db.content().retireNotSeen(playlistId, 2)
                    }
                }
            }
            started.await()
            val touch = async(Dispatchers.IO) {
                measureTimeMillis {
                    assertEquals(1, db.sessions().touch("session", 2, 1_000_002))
                }
            }
            retirement.await()
            val touchMs = touch.await()

            println("MERGED_DB_IDENTITY_RETIRE_CONTENTION rows=$rowCount touchMs=$touchMs")
            assertTrue(
                touchMs < MAX_IDENTITY_WRITE_MS,
                "A set-wise $rowCount-row identity retirement blocked a session write for " +
                    "${touchMs}ms",
            )
        }
    }

    @Test
    fun realisticSeriesLocatorContentionBenchmark() = runTest {
        val requestedRows = System.getenv("OPENTV_SERIES_LOCATOR_CONTENTION_ROWS")?.toIntOrNull()
        assumeTrue(
            "Set OPENTV_SERIES_LOCATOR_CONTENTION_ROWS to run the benchmark",
            requestedRows != null,
        )
        val rowCount = requireNotNull(requestedRows)
        withPersistence { persistence ->
            val storage = persistence.catalog
            val db = persistence.database
            val playlistId = storage.playlists.insert(Playlist(name = "Series", url = null))
            db.users().insert(user())
            db.sessions().insert(session())
            db.content().insertAll(
                List(rowCount) { index ->
                    ContentIdentityRow(
                        contentId = "series-$index",
                        playlistId = playlistId,
                        kind = ChannelKind.SERIES,
                        providerFingerprint = "series-fingerprint-$index",
                        currentChannelId = null,
                        lastSeenAtMs = 1,
                        retired = false,
                    )
                },
            )
            val locators = List(rowCount) { index ->
                ContentSeriesLocatorRow(
                    contentId = "series-$index",
                    playlistId = playlistId,
                    sourceKind = "xtream",
                    sourceKey = (index + 1L).toString(),
                )
            }

            val started = CompletableDeferred<Unit>()
            val write = async(Dispatchers.IO) {
                db.useWriterConnection { connection ->
                    connection.immediateTransaction {
                        started.complete(Unit)
                        db.writeContentSeriesLocators(locators.take(500))
                    }
                }
                db.writeContentSeriesLocators(locators.drop(500))
            }
            started.await()
            val touch = async(Dispatchers.IO) {
                measureTimeMillis {
                    assertEquals(1, db.sessions().touch("session", 2, 1_000_002))
                }
            }
            write.await()
            val touchMs = touch.await()

            println("MERGED_DB_SERIES_LOCATOR_CONTENTION rows=$rowCount touchMs=$touchMs")
            assertTrue(
                touchMs < MAX_IDENTITY_WRITE_MS,
                "A chunked $rowCount-row series locator write blocked a session for ${touchMs}ms",
            )
        }
    }

    @Test
    fun realisticGuideDeletionContentionBenchmark() = runTest {
        val requestedRows = System.getenv("OPENTV_GUIDE_DELETE_CONTENTION_ROWS")?.toIntOrNull()
        assumeTrue(
            "Set OPENTV_GUIDE_DELETE_CONTENTION_ROWS to run the benchmark",
            requestedRows != null,
        )
        val rowCount = requireNotNull(requestedRows)
        withPersistence { persistence ->
            val storage = persistence.catalog
            val db = persistence.database
            val playlistId = storage.playlists.insert(Playlist(name = "Guide", url = null))
            db.users().insert(user())
            db.sessions().insert(session())
            db.useWriterConnection { connection ->
                connection.usePrepared(
                    """
                    WITH RECURSIVE fixture(n) AS (
                        VALUES(0)
                        UNION ALL
                        SELECT n + 1 FROM fixture WHERE n + 1 < $rowCount
                    )
                    INSERT INTO programmes(
                        playlistId, tvgId, title, description, startMs, endMs
                    )
                    SELECT $playlistId, 'tvg-' || (n % 100000), 'Programme ' || n,
                           NULL, n, n + 3600000
                    FROM fixture
                    """.trimIndent(),
                ) { statement -> statement.step() }
            }

            val started = CompletableDeferred<Unit>()
            val deletion = async(Dispatchers.IO) {
                db.useWriterConnection { connection ->
                    connection.immediateTransaction {
                        started.complete(Unit)
                        db.guideMaintenance().deleteFromChunk(
                            playlistId,
                            0,
                            GUIDE_WRITE_CHUNK_SIZE,
                        )
                    }
                }
                db.deleteGuideFromInChunks(playlistId, 0)
            }
            started.await()
            val touch = async(Dispatchers.IO) {
                measureTimeMillis {
                    assertEquals(1, db.sessions().touch("session", 2, 1_000_002))
                }
            }
            deletion.await()
            val touchMs = touch.await()

            println("MERGED_DB_GUIDE_DELETE_CONTENTION rows=$rowCount touchMs=$touchMs")
            assertTrue(
                touchMs < MAX_GUIDE_CHUNK_WRITE_MS,
                "Chunked deletion of $rowCount guide rows blocked a session write for ${touchMs}ms",
            )
            assertEquals(
                0L,
                db.useReaderConnection { connection ->
                    connection.usePrepared(
                        "SELECT COUNT(*) FROM programmes WHERE playlistId = ?",
                    ) { statement ->
                        statement.bindLong(1, playlistId)
                        check(statement.step())
                        statement.getLong(0)
                    }
                },
            )
        }
    }

    @Test
    fun populatedCatalogReplacementDoesNotStarveSessionWrites() = runTest {
        withPersistence { persistence ->
            val storage = persistence.catalog
            val db = persistence.database
            val playlistId = storage.playlists.insert(Playlist(name = "Catalog", url = null))
            db.users().insert(user())
            db.sessions().insert(session())
            storage.channels.insertAll(channels(playlistId, CONTENTION_ROWS, "Old"))
            db.content().insertAll(
                List(CONTENTION_ROWS) { index ->
                    ContentIdentityRow(
                        contentId = "content-$index",
                        playlistId = playlistId,
                        kind = index % 3,
                        providerFingerprint = "fingerprint-$index",
                        currentChannelId = index + 1L,
                        lastSeenAtMs = 1,
                        retired = false,
                    )
                },
            )
            val replacement = channels(playlistId, CONTENTION_ROWS, "New")

            val started = CompletableDeferred<Unit>()
            val swap = async(Dispatchers.IO) {
                db.useWriterConnection { connection ->
                    connection.immediateTransaction {
                        started.complete(Unit)
                        storage.channels.replaceKinds(
                            playlistId,
                            listOf(ChannelKind.LIVE, ChannelKind.MOVIE, ChannelKind.SERIES),
                            replacement,
                        )
                    }
                }
            }
            started.await()
            val read = async(Dispatchers.IO) {
                db.users().get("u1")?.id
            }
            val touch = async(Dispatchers.IO) {
                measureTimeMillis {
                    assertEquals(1, db.sessions().touch("session", 2, 1_000_002))
                }
            }

            swap.await()
            val touchMs = touch.await()
            println("MERGED_DB_CONTENTION replacementRows=$CONTENTION_ROWS touchMs=$touchMs")

            assertEquals("u1", read.await(), "WAL readers must remain available during the swap")
            assertTrue(
                touchMs < MAX_BLOCKING_WRITE_MS,
                "A $CONTENTION_ROWS-row catalog swap blocked a session write for ${touchMs}ms",
            )
            assertEquals(CONTENTION_ROWS, totalChannelCount(storage, playlistId))
            assertTrue(storage.channels.search(playlistId, "new channel").isNotEmpty())
            assertTrue(storage.channels.search(playlistId, "old channel").isEmpty())

            val reconciliationStarted = CompletableDeferred<Unit>()
            val updates = List(CONTENTION_ROWS) { index ->
                ContentIdentityRow(
                    contentId = "content-$index",
                    playlistId = playlistId,
                    kind = index % 3,
                    providerFingerprint = "fingerprint-$index",
                    currentChannelId = CONTENTION_ROWS + index + 1L,
                    lastSeenAtMs = 2,
                    retired = false,
                )
            }
            val reconciliation = async(Dispatchers.IO) {
                db.useWriterConnection { connection ->
                    connection.immediateTransaction {
                        reconciliationStarted.complete(Unit)
                        db.content().updateAll(
                            updates.take(CONTENT_IDENTITY_WRITE_CHUNK_SIZE),
                        )
                    }
                }
                db.writeContentIdentityReconciliation(
                    inserts = emptyList(),
                    updates = updates.drop(CONTENT_IDENTITY_WRITE_CHUNK_SIZE),
                    playlistId = playlistId,
                    retireMissingBeforeMs = null,
                )
            }
            reconciliationStarted.await()
            val reconciliationTouch = async(Dispatchers.IO) {
                measureTimeMillis {
                    assertEquals(1, db.sessions().touch("session", 3, 1_000_003))
                }
            }
            reconciliation.await()
            val reconciliationTouchMs = reconciliationTouch.await()
            println(
                "MERGED_DB_CONTENTION identityRows=$CONTENTION_ROWS " +
                    "touchMs=$reconciliationTouchMs",
            )
            assertTrue(
                reconciliationTouchMs < MAX_BLOCKING_WRITE_MS,
                "A $CONTENTION_ROWS-row identity reconciliation blocked a session write for " +
                    "${reconciliationTouchMs}ms",
            )
        }
    }

    @Test
    fun catalogDeletionPreservesOtherSearchEntriesAndRestoresTriggers() = runTest {
        withPersistence { persistence ->
            val storage = persistence.catalog
            val db = persistence.database
            val deletedId = storage.playlists.insert(Playlist(name = "Deleted", url = null))
            val keptId = storage.playlists.insert(Playlist(name = "Kept", url = null))
            db.users().insert(user())
            db.sessions().insert(session())
            storage.channels.insertAll(channels(deletedId, CONTENTION_ROWS, "Deleted"))
            storage.channels.insertAll(channels(keptId, 10, "Kept"))
            storage.xtreamSeries.insertAll(series(deletedId, 100, "Deleted"))
            storage.xtreamSeries.insertAll(series(keptId, 10, "Kept"))

            val started = CompletableDeferred<Unit>()
            val deletion = async(Dispatchers.IO) {
                db.useWriterConnection { connection ->
                    connection.immediateTransaction {
                        started.complete(Unit)
                        db.deleteCatalogPlaylist(deletedId)
                    }
                }
            }
            started.await()
            val touch = async(Dispatchers.IO) {
                measureTimeMillis {
                    assertEquals(1, db.sessions().touch("session", 2, 1_000_002))
                }
            }
            deletion.await()
            val touchMs = touch.await()
            println("MERGED_DB_CONTENTION deletionRows=$CONTENTION_ROWS touchMs=$touchMs")

            assertTrue(
                touchMs < MAX_BLOCKING_WRITE_MS,
                "A $CONTENTION_ROWS-row catalog deletion blocked a session write for ${touchMs}ms",
            )
            assertTrue(storage.channels.search(keptId, "kept channel").isNotEmpty())
            assertTrue(storage.xtreamSeries.search(keptId, "kept series").isNotEmpty())

            storage.channels.insertAll(channels(keptId, 1, "Later"))
            storage.xtreamSeries.insertAll(series(keptId, 1, "Later", firstId = 100))
            assertTrue(storage.channels.search(keptId, "later channel").isNotEmpty())
            assertTrue(storage.xtreamSeries.search(keptId, "later series").isNotEmpty())
        }
    }

    @Test
    fun bundledDatabaseUsesWalAndABusyTimeout() = runTest {
        withPersistence { persistence ->
            val settings = persistence.database.useReaderConnection { connection ->
                val journal = connection.usePrepared("PRAGMA journal_mode") {
                    check(it.step())
                    it.getText(0)
                }
                val busyTimeout = connection.usePrepared("PRAGMA busy_timeout") {
                    check(it.step())
                    it.getLong(0)
                }
                journal to busyTimeout
            }

            assertEquals("wal", settings.first)
            assertTrue(settings.second >= 3_000, "Room must configure a SQLite busy timeout")
        }
    }

    private suspend fun withPersistence(block: suspend (OpenTvServerStorage) -> Unit) {
        val dir = Files.createTempDirectory("opentv-contention-test")
        val persistence = createOpenTvServerStorage(dir.resolve("opentv.db").toString())
        try {
            block(persistence)
        } finally {
            persistence.catalog.close()
            dir.toFile().deleteRecursively()
        }
    }

    private suspend fun totalChannelCount(
        storage: com.buco7854.opentv.core.storage.Storage,
        playlistId: Long,
    ): Int = listOf(ChannelKind.LIVE, ChannelKind.MOVIE, ChannelKind.SERIES)
        .sumOf { storage.channels.count(playlistId, it) }

    private fun channels(playlistId: Long, count: Int, prefix: String): List<Channel> =
        List(count) { index ->
            Channel(
                playlistId = playlistId,
                name = "$prefix Channel $index",
                url = "https://fixture.invalid/${prefix.lowercase()}/$index",
                logo = null,
                groupTitle = "Group ${index % 100}",
                tvgId = if (index % 3 == ChannelKind.LIVE) "tvg-$index" else null,
                kind = index % 3,
                seriesKey = null,
                season = null,
                episode = null,
                position = index,
            )
        }

    private fun series(
        playlistId: Long,
        count: Int,
        prefix: String,
        firstId: Long = 1,
    ): List<XtreamSeries> = List(count) { index ->
        XtreamSeries(
            playlistId = playlistId,
            seriesId = firstId + index,
            name = "$prefix Series $index",
            categoryName = "Category",
            cover = null,
            plot = null,
            castNames = null,
            genre = null,
            rating = null,
        )
    }

    private fun user() = UserRow(
        "u1",
        "Alice",
        "alice",
        "Alice",
        UserStatus.ACTIVE,
        UserRole.USER,
        false,
        1,
        1,
        null,
    )

    private fun session() = AuthSessionRow(
        id = "session",
        userId = "u1",
        tokenHash = byteArrayOf(1),
        csrfToken = "",
        authMethod = AuthMethod.PASSWORD,
        clientKind = ClientKind.BROWSER,
        tokenFamilyId = "family",
        credentialVersion = 1,
        deviceId = null,
        deviceName = null,
        mfaSatisfiedAtMs = null,
        createdAtMs = 1,
        lastSeenAtMs = 1,
        idleExpiresAtMs = 1_000_001,
        absoluteExpiresAtMs = 2_000_001,
        revokedAtMs = null,
    )

    private companion object {
        const val CONTENTION_ROWS = 20_000
        const val GUIDE_INGEST_BATCH_ROWS = 500
        const val MAX_IDENTITY_WRITE_MS = 1_000L
        const val MAX_GUIDE_CHUNK_WRITE_MS = 1_000L
        const val MAX_CATALOG_REPLACEMENT_WRITE_MS = 5_000L
        const val MAX_BLOCKING_WRITE_MS = 2_000L
    }
}
