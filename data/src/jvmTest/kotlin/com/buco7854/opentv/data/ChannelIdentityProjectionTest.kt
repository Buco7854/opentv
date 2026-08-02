package com.buco7854.opentv.data

import com.buco7854.opentv.core.model.Channel
import com.buco7854.opentv.core.model.ChannelKind
import com.buco7854.opentv.core.model.Playlist
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class ChannelIdentityProjectionTest {
    @Test
    fun identity_input_is_keyset_paged_with_only_matching_playlist_rows() = runTest {
        val directory = Files.createTempDirectory("opentv-channel-identity-page")
        try {
            val storage = createRoomStorage(directory.resolve("opentv.db").toString())
            try {
                val playlistId = storage.playlists.insert(Playlist(name = "Catalog", url = null))
                val otherPlaylistId = storage.playlists.insert(Playlist(name = "Other", url = null))
                storage.channels.insertAll(
                    listOf(
                        channel(playlistId, "https://fixture.invalid/one", null, null),
                        channel(playlistId, "https://fixture.invalid/two", 22L, "Show"),
                        channel(playlistId, "https://fixture.invalid/three", null, "Show"),
                        channel(otherPlaylistId, "https://fixture.invalid/other", null, null),
                    ),
                )

                val first = storage.channels.identityPage(playlistId, afterId = 0L, limit = 2)
                val second = storage.channels.identityPage(
                    playlistId,
                    afterId = first.last().id,
                    limit = 2,
                )

                assertEquals(2, first.size)
                assertEquals(1, second.size)
                assertEquals(
                    listOf(
                        "https://fixture.invalid/one" to null,
                        "https://fixture.invalid/two" to 22L,
                        "https://fixture.invalid/three" to null,
                    ),
                    (first + second).map { it.url to it.xtreamStreamId },
                )
                assertEquals(listOf(null, "Show", "Show"), (first + second).map { it.seriesKey })
            } finally {
                storage.close()
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun channel(
        playlistId: Long,
        url: String,
        xtreamStreamId: Long?,
        seriesKey: String?,
    ) = Channel(
        playlistId = playlistId,
        name = url.substringAfterLast('/'),
        url = url,
        logo = null,
        groupTitle = "Group",
        tvgId = null,
        kind = if (seriesKey == null) ChannelKind.LIVE else ChannelKind.SERIES,
        seriesKey = seriesKey,
        season = null,
        episode = null,
        position = 0,
        xtreamStreamId = xtreamStreamId,
    )
}
