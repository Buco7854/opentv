package com.buco7854.opentv.server

import com.buco7854.opentv.core.model.Channel
import com.buco7854.opentv.core.model.ChannelKind
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
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
        ).toDto(cipher, "content-42", "user-1")

        assertTrue(source !in Json.encodeToString(ChannelDto.serializer(), dto))
        assertEquals("content-42", dto.contentId)
        assertNotEquals(logo, dto.logo)
        assertEquals(null, dto.logo?.let(cipher::tryDecrypt))
        val image = dto.logo?.let(cipher::tryDecryptImage)
        assertEquals(logo, image?.url)
        assertEquals("user-1", image?.userId)
        assertEquals(1, image?.playlistId)
        val streamToken = cipher.encrypt(source)
        assertEquals(null, cipher.tryDecryptImage(streamToken))
    }

    @Test
    fun `image capability is bound to a user and expires`() {
        var now = 10L
        val expiringCipher = StreamCipher(
            Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() }),
            clock = { now },
        )
        val token = expiringCipher.encryptImage(
            "https://provider.example/poster.jpg",
            "user-1",
            playlistId = 7,
        )

        val capability = expiringCipher.tryDecryptImage(token)
        assertEquals("user-1", capability?.userId)
        assertEquals(7, capability?.playlistId)
        now = requireNotNull(capability).expiresAtMs
        assertNull(expiringCipher.tryDecryptImage(token))
    }
}
