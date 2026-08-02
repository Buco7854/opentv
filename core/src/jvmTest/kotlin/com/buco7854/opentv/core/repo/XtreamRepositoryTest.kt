package com.buco7854.opentv.core.repo

import com.buco7854.opentv.core.log.CoreLog
import com.buco7854.opentv.core.model.Channel
import com.buco7854.opentv.core.model.ChannelKind
import com.buco7854.opentv.core.model.Playlist
import com.buco7854.opentv.core.model.Programme
import com.buco7854.opentv.core.net.ConditionalFetcher
import com.buco7854.opentv.core.storage.EpgStore
import com.buco7854.opentv.core.storage.PlaylistStore
import com.buco7854.opentv.core.storage.Storage
import com.buco7854.opentv.core.xtream.XtreamApi
import java.lang.reflect.Proxy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class XtreamRepositoryTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun slow_panel_epg_times_out_then_falls_back_without_immediate_retry() = runTest {
        val playlist = Playlist(
            id = 7,
            name = "Provider",
            url = null,
            epgUrl = "https://provider.example/xmltv.php",
            xtreamBase = "https://provider.example",
            xtreamUser = "alice",
            xtreamPass = "secret",
        )
        val fallback = Programme(
            playlistId = playlist.id,
            tvgId = "channel.epg",
            title = "Stored guide",
            description = null,
            startMs = 0,
            endMs = Long.MAX_VALUE,
        )
        val storage = storage(playlist, listOf(fallback))
        var panelRequests = 0
        val slowApi = XtreamApi {
            panelRequests++
            delay(30_000)
            """{"epg_listings":[]}"""
        }
        val log = CoreLog { _, _ -> }
        val epg = EpgRepository(storage, ConditionalFetcher { _, _, _ -> error("not fetched") })
        val repository = XtreamRepository(
            storage,
            slowApi,
            epg,
            AccountRepository(slowApi, log),
            log,
            clock = { currentTime },
        )
        val channel = Channel(
            playlistId = playlist.id,
            name = "Channel",
            url = "https://provider.example/live/alice/secret/42.ts",
            logo = null,
            groupTitle = "Live",
            tvgId = "channel.epg",
            kind = ChannelKind.LIVE,
            seriesKey = null,
            season = null,
            episode = null,
            position = 0,
            xtreamStreamId = 42,
        )

        assertEquals(listOf("Stored guide"), repository.guideFor(channel).map { it.title })
        assertEquals(4_000, currentTime)

        assertEquals(listOf("Stored guide"), repository.guideFor(channel).map { it.title })
        assertEquals(1, panelRequests)
        assertEquals(4_000, currentTime)

        advanceTimeBy(60_000)
        assertEquals(listOf("Stored guide"), repository.guideFor(channel).map { it.title })
        assertEquals(2, panelRequests)
        assertEquals(68_000, currentTime)
    }

    @Test
    fun invalid_catchup_interval_does_not_build_a_provider_url() = runTest {
        val playlist = Playlist(id = 7, name = "Provider", url = null)
        val storage = storage(playlist, emptyList())
        val api = XtreamApi { error("not fetched") }
        val log = CoreLog { _, _ -> }
        val repository = XtreamRepository(
            storage,
            api,
            EpgRepository(storage, ConditionalFetcher { _, _, _ -> error("not fetched") }),
            AccountRepository(api, log),
            log,
        )
        val channel = Channel(
            playlistId = playlist.id,
            name = "Channel",
            url = "https://provider.example/live.ts",
            logo = null,
            groupTitle = "Live",
            tvgId = "channel.epg",
            kind = ChannelKind.LIVE,
            seriesKey = null,
            season = null,
            episode = null,
            position = 0,
            catchupSource = "https://provider.example/archive/{utc}/{duration}.ts",
        )

        assertNull(repository.catchupUrlFor(channel, startMs = 200, endMs = 100))
        assertNull(repository.catchupUrlFor(channel, startMs = 100, endMs = 100))
        assertNull(repository.catchupUrlFor(channel, startMs = -1, endMs = Long.MAX_VALUE))
    }

    @Test
    fun catchup_template_uses_the_repository_clock_for_lutc() = runTest {
        val playlist = Playlist(id = 7, name = "Provider", url = null)
        val storage = storage(playlist, emptyList())
        val api = XtreamApi { error("not fetched") }
        val log = CoreLog { _, _ -> }
        val repository = XtreamRepository(
            storage,
            api,
            EpgRepository(storage, ConditionalFetcher { _, _, _ -> error("not fetched") }),
            AccountRepository(api, log),
            log,
            clock = { 10_000 },
        )
        val channel = Channel(
            playlistId = playlist.id,
            name = "Channel",
            url = "https://provider.example/live.ts",
            logo = null,
            groupTitle = "Live",
            tvgId = "channel.epg",
            kind = ChannelKind.LIVE,
            seriesKey = null,
            season = null,
            episode = null,
            position = 0,
            catchupSource = "https://provider.example/archive/{utc}/{lutc}.ts",
        )

        assertEquals(
            "https://provider.example/archive/1/10.ts",
            repository.catchupUrlFor(channel, startMs = 1_000, endMs = 2_000),
        )
    }

    @Test
    fun xtream_catchup_uses_server_info_timezone() = runTest {
        val playlist = Playlist(
            id = 7,
            name = "Provider",
            url = null,
            xtreamBase = "https://provider.example",
            xtreamUser = "alice",
            xtreamPass = "secret",
        )
        val storage = storage(playlist, emptyList())
        val api = XtreamApi {
            """{"user_info":{"status":"Active"},"server_info":{"timezone":"Europe/London"}}"""
        }
        val log = CoreLog { _, _ -> }
        val repository = XtreamRepository(
            storage,
            api,
            EpgRepository(storage, ConditionalFetcher { _, _, _ -> error("not fetched") }),
            AccountRepository(api, log, clock = { 0 }),
            log,
            clock = { 0 },
        )
        val channel = Channel(
            playlistId = playlist.id,
            name = "Channel",
            url = "https://provider.example/live/alice/secret/42.ts",
            logo = null,
            groupTitle = "Live",
            tvgId = "channel.epg",
            kind = ChannelKind.LIVE,
            seriesKey = null,
            season = null,
            episode = null,
            position = 0,
            xtreamStreamId = 42,
            catchupDays = 3,
        )

        assertEquals(
            "https://provider.example/timeshift/alice/secret/60/2024-03-31:02-30/42.ts",
            repository.catchupUrlFor(
                channel,
                startMs = kotlin.time.Instant.parse("2024-03-31T01:30:00Z").toEpochMilliseconds(),
                endMs = kotlin.time.Instant.parse("2024-03-31T02:30:00Z").toEpochMilliseconds(),
            ),
        )
    }

    private fun storage(playlist: Playlist, guide: List<Programme>): Storage {
        val playlists = proxy<PlaylistStore> { method, _ ->
            when (method) {
                "get" -> playlist
                else -> error("Unexpected PlaylistStore call: $method")
            }
        }
        val epg = proxy<EpgStore> { method, _ ->
            when (method) {
                "guideSince" -> guide
                else -> error("Unexpected EpgStore call: $method")
            }
        }
        return proxy { method, _ ->
            when (method) {
                "getPlaylists" -> playlists
                "getEpg" -> epg
                else -> error("Unexpected Storage call: $method")
            }
        }
    }

    private inline fun <reified T : Any> proxy(
        crossinline handler: (String, Array<out Any?>) -> Any?,
    ): T = Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java),
    ) { _, method, arguments ->
        handler(method.name, arguments ?: emptyArray())
    } as T
}
