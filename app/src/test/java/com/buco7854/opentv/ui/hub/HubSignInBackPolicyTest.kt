package com.buco7854.opentv.ui.hub

import com.buco7854.opentv.hub.HubSignInState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HubSignInBackPolicyTest {
    @Test
    fun addFlowOnlyExitsFromUrlEntry() {
        assertTrue(isHubSignInRoot(HubSignInState.UrlEntry(), reauthenticating = false))
        assertFalse(isHubSignInRoot(HubSignInState.Password(), reauthenticating = false))
        assertFalse(
            isHubSignInRoot(
                HubSignInState.Probing("https://tv.example"),
                reauthenticating = false,
            ),
        )
    }

    @Test
    fun reauthenticationTreatsItsProbeAsTheRouteRoot() {
        assertTrue(
            isHubSignInRoot(
                HubSignInState.Probing("https://tv.example"),
                reauthenticating = true,
            ),
        )
        assertFalse(isHubSignInRoot(HubSignInState.Password(), reauthenticating = true))
    }
}
