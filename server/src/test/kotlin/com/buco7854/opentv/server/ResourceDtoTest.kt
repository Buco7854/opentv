package com.buco7854.opentv.server

import com.buco7854.opentv.core.model.Channel
import com.buco7854.opentv.core.model.ChannelKind
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

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
        ).toDto(cipher, "content-42")

        assertTrue(source !in Json.encodeToString(ChannelDto.serializer(), dto))
        assertEquals("content-42", dto.contentId)
        assertNotEquals(logo, dto.logo)
        assertEquals(logo, dto.logo?.let(cipher::tryDecrypt))
    }
}
