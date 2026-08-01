package com.buco7854.opentv.ui.hub

import com.buco7854.opentv.source.PlaylistEditField
import com.buco7854.opentv.source.PlaylistEditForm
import com.buco7854.opentv.source.PlaylistEditMode
import org.junit.Assert.assertEquals
import org.junit.Test

class HubPlaylistDialogsTest {
    @Test
    fun `selected replacement file is sent as playlist content`() {
        val form = PlaylistEditForm(
            id = 7,
            name = "Imported",
            mode = PlaylistEditMode.FILE,
            fields = setOf(PlaylistEditField.NAME, PlaylistEditField.CONTENT),
            storedFields = emptySet(),
        )

        val update = playlistEditUpdate(
            form = form,
            name = "Imported",
            server = "",
            username = "",
            password = "",
            url = "",
            epgUrl = "",
            content = "#EXTM3U\n#EXTINF:-1,News\nhttps://media.example/news",
        )

        assertEquals(
            "#EXTM3U\n#EXTINF:-1,News\nhttps://media.example/news",
            update.content,
        )
        assertEquals(null, update.name)
    }
}
