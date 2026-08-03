package com.buco7854.opentv.server

import com.buco7854.opentv.core.log.CoreLog
import com.buco7854.opentv.core.model.Playlist
import com.buco7854.opentv.core.model.XtreamSeries
import com.buco7854.opentv.core.net.ConditionalFetcher
import com.buco7854.opentv.core.repo.AccountRepository
import com.buco7854.opentv.core.repo.EpgRepository
import com.buco7854.opentv.core.repo.XtreamRepository
import com.buco7854.opentv.core.repo.xtreamSeriesKey
import com.buco7854.opentv.core.xtream.XtreamApi
import com.buco7854.opentv.serverdata.createOpenTvServerStorage
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A panel that answers with no episodes is not a series without episodes, and the
 * difference is only visible on the second attempt.
 */
class XtreamEpisodeCacheTest {

    @Test
    fun an_empty_first_fetch_does_not_silence_the_panel_for_a_day() = runBlocking {
        val directory = Files.createTempDirectory("xtream-episode-cache")
        val persistence = createOpenTvServerStorage(directory.resolve("opentv.db").toString())
        val storage = persistence.catalog
        try {
            var panelCalls = 0
            val api = XtreamApi {
                panelCalls += 1
                """{"episodes":{}}"""
            }
            val log = CoreLog { _, _ -> }
            val account = AccountRepository(api, log)
            val epg = EpgRepository(storage, ConditionalFetcher { _, _, _ -> error("no refresh") })
            val xtream = XtreamRepository(storage, api, epg, account, log)
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
                        seriesId = 66,
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

            xtream.ensureEpisodes(playlistId, 66)

            assertEquals(1, panelCalls)
            assertEquals(
                0,
                storage.channels.countEpisodes(playlistId, xtreamSeriesKey(66)),
                "an empty answer stores nothing, which is the state being tested",
            )
            assertEquals(
                0L,
                storage.xtreamSeries.get(playlistId, 66)?.episodesFetchedAtMs,
                "recording the attempt would make the early return skip every later one",
            )

            // What the viewer does next: the series page said it had no episodes, so they
            // press retry. That has to reach the panel, or the page is blank until tomorrow.
            xtream.ensureEpisodes(playlistId, 66)

            assertEquals(2, panelCalls, "retry must be able to ask the panel again")
        } finally {
            storage.close()
            directory.toFile().deleteRecursively()
        }
    }
}
