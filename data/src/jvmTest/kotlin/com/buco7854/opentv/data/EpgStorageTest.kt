package com.buco7854.opentv.data

import com.buco7854.opentv.core.model.Playlist
import com.buco7854.opentv.core.model.Programme
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class EpgStorageTest {
    @Test
    fun now_airing_uses_half_open_boundaries_and_latest_overlapping_start() = runTest {
        val directory = Files.createTempDirectory("opentv-epg-now")
        try {
            val storage = createRoomStorage(directory.resolve("opentv.db").toString())
            try {
                val playlistId = storage.playlists.insert(Playlist(name = "EPG", url = null))
                storage.epg.insertAll(
                    listOf(
                        programme(playlistId, "overlap", "Older", 0, 200),
                        programme(playlistId, "overlap", "Newer", 100, 150),
                        programme(playlistId, "boundary", "Just ended", 0, 100),
                        programme(playlistId, "boundary", "Just started", 100, 200),
                    ),
                )

                assertEquals(
                    mapOf("overlap" to "Newer", "boundary" to "Just started"),
                    storage.epg.nowAiring(playlistId, 100).associate { it.tvgId to it.title },
                )
                assertEquals(
                    mapOf("overlap" to "Older", "boundary" to "Just started"),
                    storage.epg.nowAiring(playlistId, 150).associate { it.tvgId to it.title },
                )
            } finally {
                storage.close()
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun programme(
        playlistId: Long,
        tvgId: String,
        title: String,
        startMs: Long,
        endMs: Long,
    ) = Programme(
        playlistId = playlistId,
        tvgId = tvgId,
        title = title,
        description = null,
        startMs = startMs,
        endMs = endMs,
    )
}
