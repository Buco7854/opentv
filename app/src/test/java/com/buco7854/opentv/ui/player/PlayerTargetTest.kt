package com.buco7854.opentv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerTargetTest {
    @Test
    fun `all target variants round trip reserved characters`() {
        val targets = listOf(
            PlayerTarget.LocalUrl(
                url = "https://provider.example/live?a=1|2",
                title = "News | HD",
                playlistId = 7,
                tvgId = "news:world",
                live = true,
            ),
            PlayerTarget.HubContent(
                hubId = 9,
                playlistId = 3,
                contentId = "content/one|two",
                title = "A film: part 2",
                live = false,
            ),
            PlayerTarget.HubCatchUp(
                hubId = 9,
                playlistId = 3,
                contentId = "live/channel",
                title = "Channel · Programme",
                startMs = 1_000,
                durationMs = 2_000,
            ),
        )

        targets.forEach { assertEquals(it, PlayerTarget.decode(it.encode())) }
    }

    @Test
    fun `malformed targets are rejected`() {
        assertNull(PlayerTarget.decode(""))
        assertNull(PlayerTarget.decode("1|hub|broken"))
        assertNull(PlayerTarget.decode("1|local|p%3A1|h%3Awrong|title||true"))
        assertNull(PlayerTarget.decode("1|catchup|h%3A1%3A2|h%3Aid|title|-|20"))
    }
}
