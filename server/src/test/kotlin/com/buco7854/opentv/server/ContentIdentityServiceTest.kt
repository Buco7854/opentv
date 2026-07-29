package com.buco7854.opentv.server

import com.buco7854.opentv.core.model.Channel
import com.buco7854.opentv.core.model.ChannelKind
import com.buco7854.opentv.core.model.Playlist
import com.buco7854.opentv.data.createRoomStorage
import com.buco7854.opentv.serverdata.createServerUserDatabase
import com.buco7854.opentv.serverdata.db.ContentIdentityRow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
    fun a_link_survives_the_refresh_that_renumbers_every_channel() = runTest {
        val dir = Files.createTempDirectory("content-identity-refresh")
        val storage = createRoomStorage(dir.resolve("catalog.db").toString())
        val db = createServerUserDatabase(dir.resolve("users.db").toString())
        try {
            val playlistId = storage.playlists.insert(Playlist(name = "P", url = null))
            val service = ContentIdentityService(db, storage)
            // A catalog as a refresh writes it: no ids of our own, SQLite assigns them.
            storage.channels.replaceKinds(
                playlistId,
                listOf(ChannelKind.LIVE),
                (1L..3L).map { channel(playlistId, 0, it).copy(id = 0) },
            )
            service.reconcilePlaylist(playlistId)
            val before = storage.channels
                .observeInGroup(playlistId, ChannelKind.LIVE, "Live").first().first()
            val contentId = service.channel(before).contentId

            // The same three streams arrive again. Room deletes and re-inserts, so every
            // numeric id is new - which is exactly what used to break an open movie page.
            storage.channels.replaceKinds(
                playlistId,
                listOf(ChannelKind.LIVE),
                (1L..3L).map { channel(playlistId, 0, it).copy(id = 0) },
            )
            service.reconcilePlaylist(playlistId)
            val after = storage.channels
                .observeInGroup(playlistId, ChannelKind.LIVE, "Live").first().first()

            assertTrue(after.id != before.id, "the refresh must renumber, or this proves nothing")
            val (_, resolved) = service.requireChannel(contentId)
            assertEquals(after.id, resolved.id)
            assertEquals(contentId, service.channel(after).contentId)
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
    fun titles_are_resolved_with_one_channel_batch_and_missing_channels_are_absent() = runTest {
        val dir = Files.createTempDirectory("content-title-resolution")
        val storage = createRoomStorage(dir.resolve("catalog.db").toString())
        val db = createServerUserDatabase(dir.resolve("users.db").toString())
        try {
            val playlistId = storage.playlists.insert(Playlist(name = "P", url = null))
            val resolvedChannel = channel(playlistId, 7L, 7L).copy(
                name = "A human title",
                kind = ChannelKind.MOVIE,
            )
            val resolvedContentId = ContentIdentityService(db, storage)
                .channel(resolvedChannel)
                .contentId
            db.content().insert(
                ContentIdentityRow(
                    contentId = "missing-content",
                    playlistId = playlistId,
                    kind = ChannelKind.MOVIE,
                    providerFingerprint = "missing-fingerprint",
                    currentChannelId = 8L,
                    lastSeenAtMs = 1_000L,
                    retired = false,
                )
            )
            var batchCalls = 0
            var requestedIds = emptyList<Long>()
            val service = ContentIdentityService(
                db,
                storage,
                loadChannels = { ids ->
                    batchCalls++
                    requestedIds = ids
                    listOf(resolvedChannel)
                },
            )

            val titles = service.titlesByContentId(
                listOf(resolvedContentId, "missing-content"),
            )

            assertEquals(1, batchCalls)
            assertEquals(setOf(7L, 8L), requestedIds.toSet())
            assertEquals("A human title", titles[resolvedContentId])
            assertNull(titles["missing-content"])
        } finally {
            db.close()
            storage.close()
            dir.toFile().deleteRecursively()
        }
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

    @Test
    fun a_stale_pointer_never_resolves_content_from_another_playlist() = runTest {
        val dir = Files.createTempDirectory("content-identity-stale-pointer")
        val storage = createRoomStorage(dir.resolve("catalog.db").toString())
        val db = createServerUserDatabase(dir.resolve("users.db").toString())
        try {
            val firstPlaylist = storage.playlists.insert(Playlist(name = "First", url = null))
            val secondPlaylist = storage.playlists.insert(Playlist(name = "Second", url = null))
            storage.channels.replaceKinds(
                firstPlaylist,
                listOf(ChannelKind.LIVE),
                listOf(channel(firstPlaylist, 0, 1).copy(id = 0)),
            )
            storage.channels.replaceKinds(
                secondPlaylist,
                listOf(ChannelKind.LIVE),
                listOf(channel(secondPlaylist, 0, 2).copy(id = 0)),
            )
            val first = storage.channels
                .observeInGroup(firstPlaylist, ChannelKind.LIVE, "Live").first().single()
            val second = storage.channels
                .observeInGroup(secondPlaylist, ChannelKind.LIVE, "Live").first().single()
            val service = ContentIdentityService(db, storage)
            val identity = service.channel(first)
            db.content().update(
                identity.copy(
                    currentChannelId = second.id,
                    retired = true,
                ),
            )

            val (_, resolved) = service.resolve(identity.contentId)

            assertNull(resolved)
        } finally {
            db.close()
            storage.close()
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun consecutive_refreshes_in_the_same_millisecond_still_retire_missing_content() =
        runTest {
            val dir = Files.createTempDirectory("content-identity-same-clock-refresh")
            val storage = createRoomStorage(dir.resolve("catalog.db").toString())
            val db = createServerUserDatabase(dir.resolve("users.db").toString())
            try {
                val playlistId = storage.playlists.insert(Playlist(name = "P", url = null))
                val service = ContentIdentityService(db, storage) { 1_000L }
                storage.channels.replaceKinds(
                    playlistId,
                    listOf(ChannelKind.LIVE),
                    listOf(channel(playlistId, 0, 1).copy(id = 0)),
                )
                service.reconcilePlaylist(playlistId)
                val contentId = service.channel(
                    storage.channels.observeInGroup(
                        playlistId,
                        ChannelKind.LIVE,
                        "Live",
                    ).first().single(),
                ).contentId

                storage.channels.replaceKinds(
                    playlistId,
                    listOf(ChannelKind.LIVE),
                    emptyList(),
                )
                service.reconcilePlaylist(playlistId)

                val retired = service.identity(contentId)
                assertTrue(retired.retired)
                assertNull(retired.currentChannelId)
            } finally {
                db.close()
                storage.close()
                dir.toFile().deleteRecursively()
            }
        }
}
