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
        assertTrue(shouldToneMapToSdr(C.COLOR_TRANSFER_ST2084, NO_HDR))
        assertTrue(shouldToneMapToSdr(C.COLOR_TRANSFER_HLG, NO_HDR))
    }

    @Test
    fun `a screen that can show it is left alone`() {
        // Converting here would discard the range the panel was going to display, which
        // is a regression on the devices that were working.
        assertFalse(shouldToneMapToSdr(C.COLOR_TRANSFER_ST2084, setOf(HDR_TYPE_HDR10)))
        assertFalse(shouldToneMapToSdr(C.COLOR_TRANSFER_HLG, setOf(HDR_TYPE_HLG)))
    }

    @Test
    fun `one kind of hdr is not a promise of the other`() {
        // A screen advertising HLG has said nothing about PQ. Reading "supports HDR" as
        // one answer leaves PQ content as dark as it started on an HLG-only panel.
        assertTrue(shouldToneMapToSdr(C.COLOR_TRANSFER_ST2084, setOf(HDR_TYPE_HLG)))
        assertTrue(shouldToneMapToSdr(C.COLOR_TRANSFER_HLG, setOf(HDR_TYPE_HDR10)))
    }

    @Test
    fun `a dolby vision screen decodes the pq layer underneath`() {
        assertFalse(shouldToneMapToSdr(C.COLOR_TRANSFER_ST2084, setOf(HDR_TYPE_DOLBY_VISION)))
    }

    @Test
    fun `ordinary video is never touched`() {
        // Including video whose transfer never reached us: guessing would darken the
        // very content that was fine, on every device.
        assertFalse(shouldToneMapToSdr(C.COLOR_TRANSFER_SDR, NO_HDR))
        assertFalse(shouldToneMapToSdr(C.COLOR_TRANSFER_SRGB, NO_HDR))
        assertFalse(shouldToneMapToSdr(null, NO_HDR))
        assertFalse(shouldToneMapToSdr(Format.NO_VALUE, NO_HDR))
    }

    private companion object {
        val NO_HDR = emptySet<Int>()
    }
}
