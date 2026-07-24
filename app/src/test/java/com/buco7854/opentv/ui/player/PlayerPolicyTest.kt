package com.buco7854.opentv.ui.player

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
    fun `pip ratio ignores empty video and clamps system limits`() {
        assertNull(pipAspectRatio(0, 1080))
        assertEquals(2.39f, pipAspectRatio(4_000, 1_000)!!, 0.001f)
        assertEquals(0.42f, pipAspectRatio(1_000, 4_000)!!, 0.001f)
        assertEquals(16f / 9f, pipAspectRatio(1920, 1080)!!, 0.001f)
    }
}
