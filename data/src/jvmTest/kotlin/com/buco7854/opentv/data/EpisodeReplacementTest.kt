package com.buco7854.opentv.data

import com.buco7854.opentv.core.model.Channel
import com.buco7854.opentv.core.model.ChannelKind
import com.buco7854.opentv.core.model.Playlist
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class EpisodeReplacementTest {
    @Test
    fun failedReplacementRollsBackDeletedEpisodesAndSearchIndex() = runTest {
        val directory = Files.createTempDirectory("opentv-episode-replacement")
        val storage = createRoomStorage(directory.resolve("opentv.db").toString())
        try {
            val playlist = storage.playlists.insert(Playlist(name = "Catalog", url = null))
            storage.channels.insertAll(listOf(
                episode(playlist, "Show", "Original episode"),
                episode(playlist, "Other show", "Other episode"),
            ))
            val original = storage.channels.episodes(playlist, "Show").single()
            val other = storage.channels.episodes(playlist, "Other show").single()

            assertFails {
                storage.channels.replaceEpisodes(playlist, "Show", listOf(
                    episode(playlist, "Show", "Replacement episode"),
                    episode(playlist, "Show", "Conflicting episode").copy(id = other.id),
                ))
            }
            assertEquals(listOf(original), storage.channels.episodes(playlist, "Show"))
            assertEquals(listOf(other), storage.channels.episodes(playlist, "Other show"))
            assertEquals(listOf(original.id), storage.channels.search(playlist, "Original").map { it.id })
            assertTrue(storage.channels.search(playlist, "Replacement").isEmpty())

            storage.channels.replaceEpisodes(playlist, "Show", listOf(
                episode(playlist, "Show", "Replacement episode"),
            ))
            assertEquals("Replacement episode", storage.channels.episodes(playlist, "Show").single().name)
            assertEquals(listOf(other), storage.channels.episodes(playlist, "Other show"))
            assertTrue(storage.channels.search(playlist, "Original").isEmpty())
            assertEquals(1, storage.channels.search(playlist, "Replacement").size)
        } finally {
            storage.close()
            directory.toFile().deleteRecursively()
        }
    }

    private fun episode(playlistId: Long, seriesKey: String, name: String) = Channel(
        playlistId = playlistId, name = name, url = "https://fixture.invalid/$name",
        logo = null, groupTitle = "Series", tvgId = null, kind = ChannelKind.SERIES,
        seriesKey = seriesKey, season = 1, episode = 1, position = 0,
    )
}
