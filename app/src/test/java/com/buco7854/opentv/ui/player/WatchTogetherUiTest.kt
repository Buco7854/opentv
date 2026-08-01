package com.buco7854.opentv.ui.player

import com.buco7854.opentv.contract.WatchIntentPeer
import org.junit.Assert.assertEquals
import org.junit.Test

class WatchTogetherUiTest {
    @Test
    fun `same-account peer is named as the other device`() {
        assertEquals(
            "Your other device",
            peerDisplayName(
                WatchIntentPeer("other-tv", "My own display name", sameAccount = true),
                yourOtherDevice = "Your other device",
            ),
        )
        assertEquals(
            "Ari",
            peerDisplayName(
                WatchIntentPeer("friend-tv", "Ari", sameAccount = false),
                yourOtherDevice = "Your other device",
            ),
        )
    }
}
