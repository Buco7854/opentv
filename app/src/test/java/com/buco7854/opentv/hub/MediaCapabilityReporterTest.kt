package com.buco7854.opentv.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCapabilityReporterTest {

    @Test
    fun mapsCommonDeviceMimesToFfprobeNames() {
        val report = MediaCapabilityReporter.fromMimeTypes(
            setOf(
                "video/avc", "video/hevc", "video/x-vnd.on2.vp9",
                "audio/mp4a-latm", "audio/ac3", "audio/eac3", "audio/raw",
            ),
        )
        assertEquals(listOf("h264", "hevc", "vp9"), report.videoCodecs)
        assertEquals(listOf("aac", "ac3", "eac3", "pcm_s16le"), report.audioCodecs)
        assertTrue(report.selectsTracksInBand)
    }

    @Test
    fun unknownMimesAreIgnored() {
        val report = MediaCapabilityReporter.fromMimeTypes(
            setOf("video/secret-proprietary", "audio/whatever", "video/avc"),
        )
        assertEquals(listOf("h264"), report.videoCodecs)
        assertTrue(report.audioCodecs.isEmpty())
    }

    @Test
    fun dtsVariantsCollapseWithoutDuplicates() {
        val report = MediaCapabilityReporter.fromMimeTypes(
            setOf("audio/vnd.dts", "audio/vnd.dts.hd"),
        )
        assertEquals(listOf("dts"), report.audioCodecs)
    }
}
