package com.buco7854.opentv.server

import com.buco7854.opentv.core.model.Channel
import com.buco7854.opentv.core.model.ChannelKind
import com.buco7854.opentv.core.model.Playlist
import com.buco7854.opentv.serverdata.createOpenTvServerStorage
import java.nio.file.Files
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
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
        val groupCount = 100
        val dir = Files.createTempDirectory("opentv-identity-read-scale")
        val persistence = createOpenTvServerStorage(dir.resolve("opentv.db").toString())
        val storage = persistence.catalog
        try {
            val playlistId = storage.playlists.insert(Playlist(name = "Catalog", url = null))
            storage.channels.insertAll(
                List(rowCount) { index ->
                    Channel(
                        playlistId = playlistId,
                        name = "Channel $index",
                        url = "https://fixture.invalid/$index",
                        logo = null,
                        groupTitle = "Group ${index % groupCount}",
                        tvgId = null,
                        kind = index % 3,
                        seriesKey = if (index % 3 == ChannelKind.SERIES) {
                            "Show ${index / 300}"
                        } else null,
                        season = if (index % 3 == ChannelKind.SERIES) 1 else null,
                        episode = if (index % 3 == ChannelKind.SERIES) index % 100 else null,
                        position = index,
                    )
                },
            )
            val service = ContentIdentityService(persistence.database, storage) { 1_000L }
            val elapsedMs = measureTimeMillis { service.reconcilePlaylist(playlistId) }
            val groupQueries = groupCount * 3
            println(
                "IDENTITY_RECONCILIATION_READ rows=$rowCount groupQueries=$groupQueries " +
                    "catalogReadQueries=${groupQueries + 5} elapsedMs=$elapsedMs",
            )
            val seriesCount = (rowCount + 299) / 300
            assertEquals(
                rowCount + seriesCount,
                persistence.database.content().forPlaylist(playlistId).size,
            )
        } finally {
            storage.close()
            dir.toFile().deleteRecursively()
        }
    }
}
