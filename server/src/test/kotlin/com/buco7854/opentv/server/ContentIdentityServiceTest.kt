package com.buco7854.opentv.server

import com.buco7854.opentv.core.model.Channel
import com.buco7854.opentv.core.model.ChannelKind
import com.buco7854.opentv.core.model.Playlist
import com.buco7854.opentv.core.storage.Storage
import com.buco7854.opentv.serverdata.createOpenTvServerStorage
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
        block: suspend (ContentIdentityService, Storage, Long, (Long) -> Unit) -> T,
    ) = runTest {
        val dir = Files.createTempDirectory("content-identity")
        val persistence = createOpenTvServerStorage(dir.resolve("opentv.db").toString())
        val storage = persistence.catalog
        val db = persistence.database
        try {
            var now = 1_000L
            val playlistId = storage.playlists.insert(Playlist(name = "P", url = null))
            val service = ContentIdentityService(db, storage) { now }
            block(service, storage, playlistId) { advanceBy -> now += advanceBy }
        } finally {
            storage.close()
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun a_link_survives_the_refresh_that_renumbers_every_channel() = runTest {
        val dir = Files.createTempDirectory("content-identity-refresh")
        val persistence = createOpenTvServerStorage(dir.resolve("opentv.db").toString())
        val storage = persistence.catalog
        val db = persistence.database
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
            storage.close()
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun repeated_browsing_returns_one_stable_identity_per_item() =
        withServices { service, storage, playlistId, _ ->
        val page = (1L..40L).map { channel(playlistId, it, it) }
        storage.channels.insertAll(page)

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
        val persistence = createOpenTvServerStorage(dir.resolve("opentv.db").toString())
        val storage = persistence.catalog
        val db = persistence.database
        try {
            val playlistId = storage.playlists.insert(Playlist(name = "P", url = null))
            val resolvedChannel = channel(playlistId, 7L, 7L).copy(
                name = "A human title",
                kind = ChannelKind.MOVIE,
            )
            storage.channels.insertAll(listOf(resolvedChannel))
            val resolvedContentId = ContentIdentityService(db, storage)
                .channel(resolvedChannel)
                .contentId
            db.content().insert(
                ContentIdentityRow(
                    contentId = "missing-content",
                    playlistId = playlistId,
                    kind = ChannelKind.MOVIE,
                    providerFingerprint = "missing-fingerprint",
                    currentChannelId = null,
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
            assertEquals(setOf(7L), requestedIds.toSet())
            assertEquals("A human title", titles[resolvedContentId])
            assertNull(titles["missing-content"])
        } finally {
            storage.close()
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun browsing_does_not_disturb_the_record_of_what_the_provider_still_lists() =
        withServices { service, storage, playlistId, advanceClock ->
            val stocked = (1L..3L).map { channel(playlistId, it, it) }
            storage.channels.insertAll(stocked)
            val before = service.channels(stocked).getValue(1L)

            advanceClock(60_000L)
            val after = service.channels(stocked).getValue(1L)

            assertEquals(before.lastSeenAtMs, after.lastSeenAtMs)
            assertTrue(!after.retired)
        }

    @Test
    fun an_item_that_appears_between_refreshes_still_resolves() =
        withServices { service, storage, playlistId, _ ->
        val first = channel(playlistId, 1L, 1L)
        storage.channels.insertAll(listOf(first))
        service.channels(listOf(first))

        val lateChannel = channel(playlistId, 2L, 2L)
        storage.channels.insertAll(listOf(lateChannel))
        val late = service.channel(lateChannel)

        assertEquals(playlistId, late.playlistId)
        assertEquals(2L, late.currentChannelId)
    }

    @Test
    fun a_stale_pointer_never_resolves_content_from_another_playlist() = runTest {
        val dir = Files.createTempDirectory("content-identity-stale-pointer")
        val persistence = createOpenTvServerStorage(dir.resolve("opentv.db").toString())
        val storage = persistence.catalog
        val db = persistence.database
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
            storage.close()
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun startup_repair_completes_a_partially_committed_chunked_reconciliation() = runTest {
        val dir = Files.createTempDirectory("content-identity-partial-repair")
        val persistence = createOpenTvServerStorage(dir.resolve("opentv.db").toString())
        val storage = persistence.catalog
        val db = persistence.database
        try {
            val playlistId = storage.playlists.insert(Playlist(name = "P", url = null))
            val service = ContentIdentityService(db, storage) { 1_000L }
            storage.channels.replaceKinds(
                playlistId,
                listOf(ChannelKind.LIVE),
                (1L..4L).map { channel(playlistId, 0, it).copy(id = 0) },
            )
            service.reconcilePlaylist(playlistId)
            val before = storage.channels
                .observeInGroup(playlistId, ChannelKind.LIVE, "Live")
                .first()
                .associateBy { it.xtreamStreamId }
            val seededIdentities = service.channels(before.values.toList())
            val original = before.values.associate { seeded ->
                requireNotNull(seeded.xtreamStreamId) to seededIdentities.getValue(seeded.id)
            }

            storage.channels.replaceKinds(
                playlistId,
                listOf(ChannelKind.LIVE),
                listOf(1L, 2L, 3L, 5L, 6L).map {
                    channel(playlistId, 0, it).copy(id = 0)
                },
            )
            val after = storage.channels
                .observeInGroup(playlistId, ChannelKind.LIVE, "Live")
                .first()
                .associateBy { it.xtreamStreamId }

            val streamOne = original.getValue(1L)
            db.content().update(streamOne.copy(currentChannelId = after.getValue(1L).id))
            val partiallyInserted = service.channel(after.getValue(5L))

            service.repairPlaylist(playlistId)

            (1L..3L).forEach { streamId ->
                val identity = service.channel(after.getValue(streamId))
                assertEquals(original.getValue(streamId).contentId, identity.contentId)
                assertEquals(after.getValue(streamId).id, identity.currentChannelId)
                assertEquals(1_000L, identity.lastSeenAtMs)
            }
            assertEquals(
                partiallyInserted.contentId,
                service.channel(after.getValue(5L)).contentId,
            )
            assertEquals(after.getValue(6L).id, service.channel(after.getValue(6L)).currentChannelId)
            val absent = db.content().get(original.getValue(4L).contentId)
            assertEquals(false, absent?.retired)
            assertNull(absent?.currentChannelId)
            assertEquals(6, db.content().forPlaylist(playlistId).size)
        } finally {
            storage.close()
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun consecutive_refreshes_in_the_same_millisecond_still_retire_missing_content() =
        runTest {
            val dir = Files.createTempDirectory("content-identity-same-clock-refresh")
            val persistence = createOpenTvServerStorage(dir.resolve("opentv.db").toString())
            val storage = persistence.catalog
            val db = persistence.database
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
                storage.close()
                dir.toFile().deleteRecursively()
            }
        }
}
