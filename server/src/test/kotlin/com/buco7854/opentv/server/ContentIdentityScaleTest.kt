package com.buco7854.opentv.server

import com.buco7854.opentv.core.model.Channel
import com.buco7854.opentv.core.model.ChannelKind
import com.buco7854.opentv.core.model.Playlist
import com.buco7854.opentv.core.storage.ChannelIdentityProjection
import com.buco7854.opentv.core.storage.ChannelStore
import com.buco7854.opentv.core.storage.Storage
import com.buco7854.opentv.core.storage.XtreamSeriesStore
import com.buco7854.opentv.serverdata.createOpenTvServerStorage
import java.nio.file.Files
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue

class ContentIdentityScaleTest {
    @Test
    fun reconciliationCatalogReadFanoutBenchmark() = runTest {
        val requestedRows = System.getenv("OPENTV_IDENTITY_READ_BENCHMARK_ROWS")?.toIntOrNull()
        assumeTrue(
            "Set OPENTV_IDENTITY_READ_BENCHMARK_ROWS to run the benchmark",
            requestedRows != null,
        )
        val rowCount = requireNotNull(requestedRows)
        val dir = Files.createTempDirectory("opentv-identity-read-scale")
        val persistence = createOpenTvServerStorage(dir.resolve("opentv.db").toString())
        val storage = persistence.catalog
        try {
            val playlistId = storage.playlists.insert(Playlist(name = "Catalog", url = null))
            storage.channels.insertAll(
                List(rowCount) { index -> catalogChannel(playlistId, index) },
            )
            var catalogReadQueries = 0
            val measuredChannels = object : ChannelStore by storage.channels {
                override suspend fun identityPage(
                    playlistId: Long,
                    afterId: Long,
                    limit: Int,
                ): List<ChannelIdentityProjection> {
                    catalogReadQueries++
                    return storage.channels.identityPage(playlistId, afterId, limit)
                }
            }
            val measuredSeries = object : XtreamSeriesStore by storage.xtreamSeries {
                override fun observeAll(playlistId: Long) =
                    storage.xtreamSeries.observeAll(playlistId).onStart { catalogReadQueries++ }
            }
            val measuredStorage = object : Storage by storage {
                override val channels = measuredChannels
                override val xtreamSeries = measuredSeries
            }
            val service = ContentIdentityService(persistence.database, measuredStorage) { 1_000L }
            suspend fun measureScenario(scenario: String): ScenarioMeasurement {
                val before = persistence.database.content().forPlaylist(playlistId)
                    .associateBy { it.contentId }
                catalogReadQueries = 0
                val elapsedMs = measureTimeMillis { service.reconcilePlaylist(playlistId) }
                val after = persistence.database.content().forPlaylist(playlistId)
                val changedRows = after.count { before[it.contentId] != it }
                // No reader is queued in this harness, and reconcilePlaylist holds the
                // exclusive catalog gate for the entire measured call.
                val gateHoldMs = elapsedMs
                println(
                    "IDENTITY_RECONCILIATION scenario=$scenario rows=$rowCount " +
                        "catalogReadQueries=$catalogReadQueries identityRowsChanged=$changedRows " +
                        "elapsedMs=$elapsedMs gateHoldMs=$gateHoldMs",
                )
                if (rowCount == 120_000) assertEquals(4, catalogReadQueries)
                return ScenarioMeasurement(after.size, changedRows)
            }

            val seriesCount = (rowCount + 299) / 300
            val initialIdentityCount = rowCount + seriesCount
            assertEquals(
                ScenarioMeasurement(initialIdentityCount, initialIdentityCount),
                measureScenario("full-build"),
            )
            assertEquals(
                ScenarioMeasurement(initialIdentityCount, changedRows = 0),
                measureScenario("no-change"),
            )

            val changedChannels = storage.channels.getMany(
                storage.channels.identityPage(
                    playlistId,
                    afterId = 0L,
                    limit = SMALL_CHANGED_ROWS,
                ).map { it.id },
            )
            storage.channels.updateAll(
                changedChannels.map { it.copy(url = "${it.url}?revision=2") },
            )
            storage.channels.insertAll(
                List(SMALL_ADDED_ROWS) { index -> addedChannel(playlistId, rowCount, index) },
            )
            assertEquals(
                ScenarioMeasurement(
                    identities = initialIdentityCount + SMALL_CHANGED_ROWS + SMALL_ADDED_ROWS,
                    changedRows = SMALL_CHANGED_ROWS * 2 + SMALL_ADDED_ROWS,
                ),
                measureScenario("small-change"),
            )
            assertEquals(
                SMALL_CHANGED_ROWS,
                persistence.database.content().forPlaylist(playlistId).count { it.retired },
            )

            // Production refreshes replace channel rows. Even identical provider identities
            // then need their FK bindings restored after ON DELETE SET NULL.
            storage.channels.replaceKinds(
                playlistId,
                listOf(ChannelKind.LIVE, ChannelKind.MOVIE, ChannelKind.SERIES),
                List(rowCount) { index ->
                    catalogChannel(
                        playlistId,
                        index,
                        revised = index < SMALL_CHANGED_ROWS,
                    )
                } + List(SMALL_ADDED_ROWS) { index ->
                    addedChannel(playlistId, rowCount, index)
                },
            )
            assertEquals(
                ScenarioMeasurement(
                    identities = initialIdentityCount + SMALL_CHANGED_ROWS + SMALL_ADDED_ROWS,
                    changedRows = rowCount + SMALL_ADDED_ROWS,
                ),
                measureScenario("wholesale-rebind"),
            )
        } finally {
            storage.close()
            dir.toFile().deleteRecursively()
        }
    }

    private companion object {
        const val GROUP_COUNT = 100
        const val SMALL_CHANGED_ROWS = 200
        const val SMALL_ADDED_ROWS = 100
    }

    private fun catalogChannel(
        playlistId: Long,
        index: Int,
        revised: Boolean = false,
    ): Channel {
        val kind = index % 3
        return Channel(
            playlistId = playlistId,
            name = "Channel $index",
            url = "https://fixture.invalid/$index" + if (revised) "?revision=2" else "",
            logo = null,
            groupTitle = "Group ${index % GROUP_COUNT}",
            tvgId = null,
            kind = kind,
            seriesKey = if (kind == ChannelKind.SERIES) "Show ${index / 300}" else null,
            season = if (kind == ChannelKind.SERIES) 1 else null,
            episode = if (kind == ChannelKind.SERIES) index % 100 else null,
            position = index,
        )
    }

    private fun addedChannel(playlistId: Long, rowCount: Int, index: Int) = Channel(
        playlistId = playlistId,
        name = "Added channel $index",
        url = "https://fixture.invalid/added/$index",
        logo = null,
        groupTitle = "Small changes",
        tvgId = null,
        kind = ChannelKind.LIVE,
        seriesKey = null,
        season = null,
        episode = null,
        position = rowCount + index,
    )

    private data class ScenarioMeasurement(
        val identities: Int,
        val changedRows: Int,
    )
}
