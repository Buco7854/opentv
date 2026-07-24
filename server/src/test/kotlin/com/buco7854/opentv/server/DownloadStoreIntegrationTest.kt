package com.buco7854.opentv.server

import com.buco7854.opentv.core.model.Download
import com.buco7854.opentv.core.model.DownloadStatus
import com.buco7854.opentv.data.createRoomStorage
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadStoreIntegrationTest {
    @Test
    fun pausedDownloadCannotBeResurrectedByLateProgress() = runTest {
        val dir = Files.createTempDirectory("download-store")
        val storage = createRoomStorage(dir.resolve("test.db").toString())
        try {
            val id = storage.downloads.insert(
                Download(title = "Movie", url = "https://provider/movie", filePath = "movie.mp4")
            )
            assertTrue(
                storage.downloads.updateProgressIfStatus(
                    id, 10, 100, listOf(DownloadStatus.QUEUED), DownloadStatus.RUNNING
                )
            )
            val running = storage.downloads.get(id)!!
            storage.downloads.update(running.copy(status = DownloadStatus.PAUSED))

            assertFalse(
                storage.downloads.updateProgressIfStatus(
                    id, 20, 100, listOf(DownloadStatus.RUNNING), DownloadStatus.RUNNING
                )
            )
            assertEquals(DownloadStatus.PAUSED, storage.downloads.get(id)?.status)
            assertEquals(10, storage.downloads.get(id)?.downloadedBytes)
        } finally {
            storage.close()
        }
    }

}
