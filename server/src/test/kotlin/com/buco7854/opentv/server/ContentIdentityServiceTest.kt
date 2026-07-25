package com.buco7854.opentv.server

import com.buco7854.opentv.core.model.Channel
import com.buco7854.opentv.core.model.ChannelKind
import com.buco7854.opentv.core.model.Playlist
import com.buco7854.opentv.data.createRoomStorage
import com.buco7854.opentv.serverdata.createServerUserDatabase
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContentIdentityServiceTest {
    private fun channel(playlistId: Long, id: Long, streamId: Long) = Channel(
        id = id,
        playlistId = playlistId,
        name = "Channel $streamId",
        url = "https://provider.example/live/u/p/$streamId.ts",
        logo = null,
        groupTitle = "Live",
        tvgId = null,
        kind = ChannelKind.LIVE,
        seriesKey = null,
        season = null,
        episode = null,
        position = 0,
        xtreamStreamId = streamId,
    )

    private fun <T> withServices(
        block: suspend (ContentIdentityService, Long, (Long) -> Unit) -> T,
    ) = runTest {
        val dir = Files.createTempDirectory("content-identity")
        val storage = createRoomStorage(dir.resolve("catalog.db").toString())
        val db = createServerUserDatabase(dir.resolve("users.db").toString())
        try {
            var now = 1_000L
            val playlistId = storage.playlists.insert(Playlist(name = "P", url = null))
            val service = ContentIdentityService(db, storage) { now }
            block(service, playlistId) { advanceBy -> now += advanceBy }
        } finally {
            db.close()
            storage.close()
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun repeated_browsing_returns_one_stable_identity_per_item() = withServices { service, playlistId, _ ->
        val page = (1L..40L).map { channel(playlistId, it, it) }

        val first = service.channels(page)
        val second = service.channels(page)

        assertEquals(40, first.values.map { it.contentId }.distinct().size)
        assertEquals(
            first.mapValues { it.value.contentId },
            second.mapValues { it.value.contentId },
        )
        assertEquals(
            first.getValue(7L).contentId,
            service.channel(channel(playlistId, 7L, 7L)).contentId,
        )
    }

    @Test
    fun browsing_does_not_disturb_the_record_of_what_the_provider_still_lists() =
        withServices { service, playlistId, advanceClock ->
            val stocked = (1L..3L).map { channel(playlistId, it, it) }
            val before = service.channels(stocked).getValue(1L)

            advanceClock(60_000L)
            val after = service.channels(stocked).getValue(1L)

            assertEquals(before.lastSeenAtMs, after.lastSeenAtMs)
            assertTrue(!after.retired)
        }

    @Test
    fun an_item_that_appears_between_refreshes_still_resolves() = withServices { service, playlistId, _ ->
        service.channels(listOf(channel(playlistId, 1L, 1L)))

        val late = service.channel(channel(playlistId, 2L, 2L))

        assertEquals(playlistId, late.playlistId)
        assertEquals(2L, late.currentChannelId)
    }
}
