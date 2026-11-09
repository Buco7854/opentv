package com.buco7854.opentv.server

import com.buco7854.opentv.core.log.CoreLog
import com.buco7854.opentv.core.model.Playlist
import com.buco7854.opentv.core.model.XtreamSeries
import com.buco7854.opentv.core.net.ConditionalFetcher
import com.buco7854.opentv.core.repo.AccountRepository
import com.buco7854.opentv.core.repo.EpgRepository
import com.buco7854.opentv.core.repo.XtreamRepository
import com.buco7854.opentv.core.repo.xtreamSeriesKey
import com.buco7854.opentv.core.storage.Storage
import com.buco7854.opentv.core.xtream.XtreamApi
import com.buco7854.opentv.serverdata.createOpenTvServerStorage
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A panel that answers with no episodes is not a series without episodes, and the
 * difference is only ever visible on the attempt after the first.
 */
class XtreamEpisodeCacheTest {

    @Test
    fun a_provider_response_cannot_overwrite_a_changed_source() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        withPanel(fetch = {
            entered.complete(Unit)
            release.await()
            EPISODES
        }) { fixture ->
            withContext(Dispatchers.IO) {
                val pending = async { fixture.xtream.ensureEpisodes(fixture.playlistId, SERIES_ID) }
                try {
                    withTimeout(5_000) { entered.await() }
                    val previous = requireNotNull(fixture.storage.playlists.get(fixture.playlistId))
                    fixture.storage.playlists.update(previous.copy(xtreamPass = "replacement"))
                } finally {
                    release.complete(Unit)
                }
                pending.await()
                assertEquals(0, fixture.storage.channels.countEpisodes(fixture.playlistId, xtreamSeriesKey(SERIES_ID)))
                assertEquals(0L, fixture.storage.xtreamSeries.get(fixture.playlistId, SERIES_ID)?.episodesFetchedAtMs)
            }
        }
    }

    @Test
    fun concurrent_requests_for_one_series_share_the_fetched_episode_cache() = withPanel(fetch = { EPISODES }) { fixture ->
        coroutineScope {
            val requests = List(4) { async(Dispatchers.IO) { fixture.xtream.ensureEpisodes(fixture.playlistId, SERIES_ID) } }
            requests.forEach { it.await() }
        }
        assertEquals(1, fixture.panelCalls())
        assertEquals(1, fixture.storage.channels.countEpisodes(fixture.playlistId, xtreamSeriesKey(SERIES_ID)))
    }

    @Test
    fun an_empty_first_fetch_does_not_silence_the_panel_for_a_day() = withPanel { fixture ->
        fixture.xtream.ensureEpisodes(fixture.playlistId, SERIES_ID)

        assertEquals(1, fixture.panelCalls())
        assertEquals(
            0,
            fixture.storage.channels.countEpisodes(fixture.playlistId, xtreamSeriesKey(SERIES_ID)),
            "an empty answer stores nothing, which is the state being tested",
        )
        assertEquals(
            0L,
            fixture.storage.xtreamSeries.get(fixture.playlistId, SERIES_ID)?.episodesFetchedAtMs,
            "recording the attempt would make the early return skip every later one",
        )

        // What the viewer does next: the series page said it had no episodes, so they
        // press retry. That has to reach the panel, or the page is blank until tomorrow.
        fixture.xtream.ensureEpisodes(fixture.playlistId, SERIES_ID)

        assertEquals(2, fixture.panelCalls(), "retry must be able to ask the panel again")
    }

    @Test
    fun a_series_already_stamped_empty_is_asked_again_rather_than_waiting_out_the_day() =
        withPanel { fixture ->
            // The state a real installation is left in by the old behaviour: the series is
            // recorded as fetched and there is nothing to show for it. Trusting that
            // timestamp keeps the page blank for the rest of the window, so the recovery
            // never arrives for precisely the series that needed it.
            fixture.storage.xtreamSeries.setEpisodesFetched(
                fixture.playlistId,
                SERIES_ID,
                NOW - 1_000,
            )

            fixture.xtream.ensureEpisodes(fixture.playlistId, SERIES_ID)

            assertEquals(
                1,
                fixture.panelCalls(),
                "a stamp standing for no stored episodes must not be trusted",
            )
        }

    private class Fixture(
        val storage: Storage,
        val xtream: XtreamRepository,
        val playlistId: Long,
        private val calls: () -> Int,
    ) {
        fun panelCalls() = calls()
    }

    /** A panel playlist holding one series that the panel answers about with no episodes. */
    private fun withPanel(
        fetch: suspend (String) -> String = { """{"episodes":{}}""" },
        block: suspend (Fixture) -> Unit,
    ) = runBlocking {
        val directory: Path = Files.createTempDirectory("xtream-episode-cache")
        val persistence = createOpenTvServerStorage(directory.resolve("opentv.db").toString())
        val storage = persistence.catalog
        try {
            var panelCalls = 0
            val api = XtreamApi { url ->
                panelCalls += 1
                fetch(url)
            }
            val log = CoreLog { _, _ -> }
            val xtream = XtreamRepository(
                storage,
                api,
                EpgRepository(storage, ConditionalFetcher { _, _, _ -> error("no refresh") }),
                AccountRepository(api, log),
                log,
            ) { NOW }
            val playlistId = storage.playlists.insert(
                Playlist(
                    name = "Panel",
                    url = null,
                    xtreamBase = "https://panel.example",
                    xtreamUser = "user",
                    xtreamPass = "pass",
                ),
            )
            storage.xtreamSeries.insertAll(
                listOf(
                    XtreamSeries(
                        playlistId = playlistId,
                        seriesId = SERIES_ID,
                        name = "66-5",
                        categoryName = "Drama",
                        cover = null,
                        plot = null,
                        castNames = null,
                        genre = null,
                        rating = null,
                    ),
                ),
            )
            block(Fixture(storage, xtream, playlistId) { panelCalls })
        } finally {
            storage.close()
            directory.toFile().deleteRecursively()
        }
    }

    private companion object {
        const val SERIES_ID = 66L
        const val NOW = 1_700_000_000_000L
        const val EPISODES = """{"episodes":{"1":[{"id":"900","title":"Episode one","container_extension":"mp4","season":1,"episode_num":1}]}}"""
    }
}
