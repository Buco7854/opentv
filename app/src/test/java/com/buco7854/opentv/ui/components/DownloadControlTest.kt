package com.buco7854.opentv.ui.components

import com.buco7854.opentv.core.model.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadControlTest {
    @Test
    fun failedAndCancelledRowsRetryInsteadOfCreatingDuplicateDownloads() {
        assertEquals(DownloadControl.RETRY, downloadControl(DownloadStatus.FAILED))
        assertEquals(DownloadControl.RETRY, downloadControl(DownloadStatus.CANCELLED))
    }
}
