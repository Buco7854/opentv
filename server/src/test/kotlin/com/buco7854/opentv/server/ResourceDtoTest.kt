package com.buco7854.opentv.server

import com.buco7854.opentv.core.model.Channel
import com.buco7854.opentv.core.model.ChannelKind
import com.buco7854.opentv.core.model.Favorite
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class ResourceDtoTest {
    private val cipher = StreamCipher(
        Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
    )

    @Test
    fun `channel DTO hides provider and artwork URLs`() {
        val source = "https://provider.example/live/user/password/42.ts"
        val logo = "https://provider.example/images/42.png"
        val dto = Channel(
            playlistId = 1,
            name = "News",
            url = source,
            logo = logo,
            groupTitle = "Live",
            tvgId = "news",
            kind = ChannelKind.LIVE,
            seriesKey = null,
            season = null,
            episode = null,
            position = 0,
        ).toDto(cipher)

        assertNotEquals(source, dto.url)
        assertNotEquals(logo, dto.logo)
        assertEquals(source, cipher.tryDecrypt(dto.url))
        assertEquals(logo, dto.logo?.let(cipher::tryDecrypt))
    }

    @Test
    fun `series favorite keeps domain key while live favorite is opaque`() {
        val live = Favorite(1, "https://provider.example/live/1.ts", ChannelKind.LIVE, 1).toDto(cipher)
        val series = Favorite(1, "my-series", ChannelKind.SERIES, 1).toDto(cipher)

        assertFalse(live.key.startsWith("http"))
        assertEquals("my-series", series.key)
    }
}
