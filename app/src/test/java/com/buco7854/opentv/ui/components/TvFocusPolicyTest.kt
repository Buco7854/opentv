package com.buco7854.opentv.ui.components

import android.content.res.Configuration
import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvFocusPolicyTest {
    @Test
    fun televisionModeIsDetectedThroughTheUiModeMask() {
        assertTrue(
            isTelevisionUiMode(
                Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_TELEVISION,
            ),
        )
        assertFalse(isTelevisionUiMode(Configuration.UI_MODE_TYPE_NORMAL))
    }

    @Test
    fun heldRemoteCentreTriggersLongClickExactlyOnce() {
        assertFalse(
            shouldTriggerRemoteLongClick(
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.ACTION_DOWN,
                repeatCount = 0,
                flags = 0,
                alreadyHandled = false,
            ),
        )
        assertTrue(
            shouldTriggerRemoteLongClick(
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.ACTION_DOWN,
                repeatCount = 1,
                flags = KeyEvent.FLAG_LONG_PRESS,
                alreadyHandled = false,
            ),
        )
        assertFalse(
            shouldTriggerRemoteLongClick(
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.ACTION_DOWN,
                repeatCount = 2,
                flags = 0,
                alreadyHandled = true,
            ),
        )
    }

    @Test
    fun unrelatedKeysNeverTriggerRemoteLongClick() {
        assertFalse(
            shouldTriggerRemoteLongClick(
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.ACTION_DOWN,
                repeatCount = 2,
                flags = KeyEvent.FLAG_LONG_PRESS,
                alreadyHandled = false,
            ),
        )
    }
}
