package com.buco7854.opentv.ui.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadsScreenTest {
    @Test
    fun staleTotalNeverRendersProgressPastOneHundredPercent() {
        assertEquals(100, downloadProgressPercent(downloadedBytes = 125, totalBytes = 100))
        assertEquals(1f, downloadProgressFraction(downloadedBytes = 125, totalBytes = 100))
    }

    @Test
    fun unknownTotalHasNoDeterminateProgress() {
        assertNull(downloadProgressFraction(downloadedBytes = 12, totalBytes = 0))
    }
}
