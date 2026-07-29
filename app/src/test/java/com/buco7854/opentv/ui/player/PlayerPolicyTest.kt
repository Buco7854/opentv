package com.buco7854.opentv.ui.player

import android.view.KeyEvent
import androidx.media3.ui.AspectRatioFrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerPolicyTest {

    @Test
    fun `clock formatting handles negative short and long positions`() {
        assertEquals("0:00", formatPlaybackClock(-1))
        assertEquals("1:05", formatPlaybackClock(65_000))
        assertEquals("1:01:01", formatPlaybackClock(3_661_000))
    }

    @Test
    fun `resize mode cycles through supported modes`() {
        assertEquals(
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
            nextResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT),
        )
        assertEquals(
            AspectRatioFrameLayout.RESIZE_MODE_FILL,
            nextResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
        )
        assertEquals(
            AspectRatioFrameLayout.RESIZE_MODE_FIT,
            nextResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FILL),
        )
    }

    @Test
    fun `resume is applied only before the end guard`() {
        assertFalse(shouldApplyResume(0, 120_000))
        assertTrue(shouldApplyResume(10_000, 120_000))
        assertFalse(shouldApplyResume(105_000, 120_000))
    }

    @Test
    fun `progress is not persisted before the duration is known`() {
        // C.TIME_UNSET, reported until the timeline arrives.
        assertFalse(shouldPersistProgress(Long.MIN_VALUE + 1, live = false))
        assertFalse(shouldPersistProgress(0, live = false))
        assertFalse(shouldPersistProgress(120_000, live = true))
        assertTrue(shouldPersistProgress(120_000, live = false))
    }

    @Test
    fun `pip ratio ignores empty video and clamps system limits`() {
        assertNull(pipAspectRatio(0, 1080))
        assertEquals(2.39f, pipAspectRatio(4_000, 1_000)!!, 0.001f)
        assertEquals(0.42f, pipAspectRatio(1_000, 4_000)!!, 0.001f)
        assertEquals(16f / 9f, pipAspectRatio(1920, 1080)!!, 0.001f)
    }

    @Test
    fun `configuration disposal retains the hub lease but real navigation ends it`() {
        assertFalse(shouldClosePlayerOnDispose(isChangingConfigurations = true))
        assertTrue(shouldClosePlayerOnDispose(isChangingConfigurations = false))
    }

    @Test
    fun `back peels notice then controls before exiting playback`() {
        assertEquals(
            PlayerBackAction.DISMISS_NOTICE,
            playerBackAction(hasNotice = true, controlsVisible = true),
        )
        assertEquals(
            PlayerBackAction.HIDE_CONTROLS,
            playerBackAction(hasNotice = false, controlsVisible = true),
        )
        assertEquals(
            PlayerBackAction.EXIT,
            playerBackAction(hasNotice = false, controlsVisible = false),
        )
    }

    @Test
    fun `hidden chrome is reachable from remote keys`() {
        assertEquals(
            PlayerRemoteAction.SHOW_CONTROLS,
            playerRemoteAction(true, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_DOWN),
        )
        assertEquals(
            PlayerRemoteAction.SHOW_CONTROLS,
            playerRemoteAction(true, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.ACTION_DOWN),
        )
        assertEquals(
            PlayerRemoteAction.SEEK_BACK,
            playerRemoteAction(true, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.ACTION_DOWN),
        )
        assertEquals(
            PlayerRemoteAction.SEEK_FORWARD,
            playerRemoteAction(true, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_DOWN),
        )
        assertEquals(
            PlayerRemoteAction.NONE,
            playerRemoteAction(false, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_DOWN),
        )
        assertEquals(
            PlayerRemoteAction.NONE,
            playerRemoteAction(true, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_UP),
        )
    }
}
