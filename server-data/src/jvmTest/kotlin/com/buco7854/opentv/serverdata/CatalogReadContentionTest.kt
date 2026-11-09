package com.buco7854.opentv.serverdata

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import com.buco7854.opentv.core.model.Channel
import com.buco7854.opentv.core.model.ChannelKind
import com.buco7854.opentv.core.model.Playlist
import com.buco7854.opentv.core.model.XtreamSeries
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class CatalogReadContentionTest {
    @Test
    fun catalogPagesDoNotWaitForAnUnrelatedWriter() = runBlocking {
        val directory = Files.createTempDirectory("opentv-catalog-read-contention")
        val persistence = createOpenTvServerStorage(directory.resolve("opentv.db").toString())
        val storage = persistence.catalog
        val releaseWriter = CompletableDeferred<Unit>()
        try {
            val playlistId = storage.playlists.insert(Playlist(name = "Catalog", url = null))
            storage.channels.insertAll(listOf(
                channel(playlistId, "Movie", ChannelKind.MOVIE),
                channel(playlistId, "Episode", ChannelKind.SERIES, "Show"),
            ))
            storage.xtreamSeries.insertAll(listOf(XtreamSeries(
                playlistId = playlistId, seriesId = 1, name = "Panel show",
                categoryName = "Category", cover = null, plot = null, castNames = null,
                genre = null, rating = null,
            )))
            val writerHeld = CompletableDeferred<Unit>()
            val writer = async(Dispatchers.IO) {
                persistence.database.useWriterConnection { connection ->
                    connection.immediateTransaction {
                        writerHeld.complete(Unit)
                        releaseWriter.await()
                    }
                }
            }
            writerHeld.await()
            val watchdog = launch(Dispatchers.IO) {
                delay(10_000)
                releaseWriter.complete(Unit)
            }
            try {
                val pages = async(Dispatchers.IO) {
                    val movie = storage.channels.pageInGroup(playlistId, ChannelKind.MOVIE, "Category", "", 20, 0)
                    assertEquals(1, movie.total)
                    assertEquals("Movie", movie.items.single().name)
                    val series = storage.channels.pageSeriesInGroup(playlistId, "Category", "", 20, 0)
                    assertEquals(1, series.total)
                    assertEquals("Show", series.items.single().seriesKey)
                    val episodes = storage.channels.pageEpisodes(playlistId, "Show", null, 20, 0)
                    assertEquals(1, episodes.total)
                    assertEquals("Episode", episodes.items.single().name)
                    val panel = storage.xtreamSeries.pageInCategory(playlistId, "Category", "", 20, 0)
                    assertEquals(1, panel.total)
                    assertEquals("Panel show", panel.items.single().name)
                    // These are first-use reads: no persistent Flow observer has installed
                    // invalidation triggers. Cold one-shot reads must still use only readers.
                    assertEquals(1, storage.channels.groups(playlistId, ChannelKind.MOVIE).single().count)
                    assertEquals("Episode", storage.channels.episodes(playlistId, "Show").single().name)
                    assertEquals(1, storage.channels.count(playlistId, ChannelKind.MOVIE))
                    assertEquals(1, storage.xtreamSeries.categories(playlistId).single().count)
                    assertEquals("Panel show", storage.xtreamSeries.getAll(playlistId).single().name)
                    assertEquals(1, storage.xtreamSeries.count(playlistId))
                }
                withTimeout(3_000) { pages.await() }
                assertFalse(writer.isCompleted, "Catalog pages must complete before the writer is released")
            } finally {
                releaseWriter.complete(Unit)
                watchdog.cancel()
            }
            writer.await()
        } finally {
            releaseWriter.complete(Unit)
            storage.close()
            directory.toFile().deleteRecursively()
        }
    }

    private fun channel(playlistId: Long, name: String, kind: Int, seriesKey: String? = null) = Channel(
        playlistId = playlistId, name = name, url = "https://fixture.invalid/$name",
        logo = null, groupTitle = "Category", tvgId = null, kind = kind,
        seriesKey = seriesKey, season = if (seriesKey == null) null else 1,
        episode = if (seriesKey == null) null else 1, position = kind,
    )
}
