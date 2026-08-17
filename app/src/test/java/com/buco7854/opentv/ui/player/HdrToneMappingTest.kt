package com.buco7854.opentv.ui.player

import android.view.Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION
import android.view.Display.HdrCapabilities.HDR_TYPE_HDR10
import android.view.Display.HdrCapabilities.HDR_TYPE_HLG
import androidx.media3.common.C
import androidx.media3.common.Format
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HdrToneMappingTest {

    @Test
    fun `hdr on an ordinary screen is converted`() {
        // The reported symptom: a film so dark nothing in it can be made out, because a
        // curve built for a much brighter panel was sent to one that cannot follow it.
        assertTrue(shouldToneMapToSdr(C.COLOR_TRANSFER_ST2084, NO_HDR, surfacePresentsHdr = true))
        assertTrue(shouldToneMapToSdr(C.COLOR_TRANSFER_HLG, NO_HDR, surfacePresentsHdr = true))
    }

    @Test
    fun `a capable screen behind a surface that cannot carry hdr is still converted`() {
        // Why the first attempt at this changed nothing on the device that reported it.
        // The panel says it can show HDR, so the screen alone says leave it be, and then
        // a TextureView composites the frames in SDR and the picture is dark anyway.
        // Newer phones are the ones this strands, because they are the capable ones.
        assertTrue(
            shouldToneMapToSdr(C.COLOR_TRANSFER_ST2084, ALL_HDR, surfacePresentsHdr = false),
        )
        assertTrue(
            shouldToneMapToSdr(C.COLOR_TRANSFER_HLG, ALL_HDR, surfacePresentsHdr = false),
        )
    }

    @Test
    fun `a screen that can show it keeps it when the surface allows`() {
        // Converting here would discard the range the panel was going to display, which
        // is a regression on devices that were working.
        assertFalse(
            shouldToneMapToSdr(C.COLOR_TRANSFER_ST2084, setOf(HDR_TYPE_HDR10), true),
        )
        assertFalse(shouldToneMapToSdr(C.COLOR_TRANSFER_HLG, setOf(HDR_TYPE_HLG), true))
    }

    @Test
    fun `one kind of hdr is not a promise of the other`() {
        // A screen advertising HLG has said nothing about PQ. Reading "supports HDR" as
        // one answer leaves PQ content as dark as it started on an HLG-only panel.
        assertTrue(shouldToneMapToSdr(C.COLOR_TRANSFER_ST2084, setOf(HDR_TYPE_HLG), true))
        assertTrue(shouldToneMapToSdr(C.COLOR_TRANSFER_HLG, setOf(HDR_TYPE_HDR10), true))
    }

    @Test
    fun `a dolby vision screen decodes the pq layer underneath`() {
        assertFalse(
            shouldToneMapToSdr(C.COLOR_TRANSFER_ST2084, setOf(HDR_TYPE_DOLBY_VISION), true),
        )
    }

    @Test
    fun `ordinary video is never touched`() {
        // Including video whose transfer never reached us, and whatever the surface can
        // do: guessing would darken the very content that was fine, on every device.
        listOf(true, false).forEach { surface ->
            assertFalse(shouldToneMapToSdr(C.COLOR_TRANSFER_SDR, NO_HDR, surface))
            assertFalse(shouldToneMapToSdr(C.COLOR_TRANSFER_SRGB, NO_HDR, surface))
            assertFalse(shouldToneMapToSdr(null, NO_HDR, surface))
            assertFalse(shouldToneMapToSdr(Format.NO_VALUE, NO_HDR, surface))
        }
    }

    private companion object {
        val NO_HDR = emptySet<Int>()
        val ALL_HDR = setOf(HDR_TYPE_HDR10, HDR_TYPE_HLG, HDR_TYPE_DOLBY_VISION)
    }
}
