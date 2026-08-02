package com.buco7854.opentv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PipControllerTest {

    @Test
    fun `disposing an outgoing player cannot clear the incoming player callback`() {
        var firstCalls = 0
        var secondCalls = 0
        val unregisterFirst = PipController.registerOnUserLeave { firstCalls++ }
        val unregisterSecond = PipController.registerOnUserLeave { secondCalls++ }

        unregisterFirst()
        PipController.onUserLeave?.invoke()

        assertEquals(0, firstCalls)
        assertEquals(1, secondCalls)

        unregisterSecond()
        assertNull(PipController.onUserLeave)
    }
}
