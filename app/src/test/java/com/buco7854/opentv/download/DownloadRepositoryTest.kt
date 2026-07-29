package com.buco7854.opentv.download

import android.app.Application
import android.content.Context
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import com.buco7854.opentv.core.download.DownloadFileName
import com.buco7854.opentv.core.model.Channel
import com.buco7854.opentv.core.model.ChannelKind
import com.buco7854.opentv.core.model.Download
import com.buco7854.opentv.core.storage.DownloadStore
import com.buco7854.opentv.data.prefs.PlayerPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DownloadRepositoryTest {
    @Test
    fun `new row truncates a stale file left behind by destructive migration`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = RepositoryDownloadStore()
        val scheduler = RepositoryScheduler()
        val scope = CoroutineScope(SupervisorJob())
        val preferences = HubDownloadPreferences(
            context.getSharedPreferences("repository-download-test", Context.MODE_PRIVATE),
        )
        val coordinator = HubDownloadCoordinator(
            store,
            UnusedHubRemote,
            scheduler,
            preferences,
            scope,
        )
        val repository = DownloadRepository(
            context,
            store,
            PlayerPrefs(context),
            scheduler,
            coordinator,
        )
        val channel = Channel(
            playlistId = 1,
            name = "Reused title",
            url = "https://provider.invalid/new.mp4",
            logo = null,
            groupTitle = "Movies",
            tvgId = null,
            kind = ChannelKind.MOVIE,
            seriesKey = null,
            season = null,
            episode = null,
            position = 0,
        )
        val name = DownloadFileName.from(channel.name, channel.url, 1)
        val root = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
        val stale = File(File(root, "OpenTV"), name.fileName)
        stale.parentFile?.mkdirs()
        stale.writeText("bytes from the database generation that was destroyed")

        try {
            assertEquals(null, repository.enqueue(channel))

            assertEquals(stale.absolutePath, store.get(1)?.filePath)
            assertEquals(0L, stale.length())
            assertEquals(listOf(1L), scheduler.enqueued)
        } finally {
            scope.cancel()
            stale.delete()
        }
    }
}

private class RepositoryDownloadStore : DownloadStore {
    private val rows = linkedMapOf<Long, Download>()
    private var nextId = 1L

    override fun observeAll(): Flow<List<Download>> = flowOf(rows.values.toList())
    override suspend fun get(id: Long): Download? = rows[id]
    override suspend fun getByStatus(status: Int): List<Download> =
        rows.values.filter { it.status == status }
    override suspend fun getByStatuses(statuses: List<Int>): List<Download> =
        rows.values.filter { it.status in statuses }
    override suspend fun findByUrlWithStatus(url: String, statuses: List<Int>): Download? =
        rows.values.firstOrNull { it.url == url && it.status in statuses }
    override suspend fun findByHubContentWithStatus(
        hubSourceId: Long,
        contentId: String,
        statuses: List<Int>,
    ): Download? = rows.values.firstOrNull {
        it.hubSourceId == hubSourceId && it.contentId == contentId && it.status in statuses
    }
    override suspend fun insert(download: Download): Long {
        val id = nextId++
        rows[id] = download.copy(id = id)
        return id
    }
    override suspend fun update(download: Download) {
        rows[download.id] = download
    }
    override suspend fun updateProgressIfStatus(
        id: Long,
        downloaded: Long,
        total: Long,
        expectedStatuses: List<Int>,
        status: Int,
    ): Boolean = false
    override suspend fun updateStatusIfStatus(
        id: Long,
        expectedStatuses: List<Int>,
        status: Int,
        error: String?,
    ): Boolean = false
    override suspend fun updateUrlIfStatus(
        id: Long,
        url: String,
        expectedStatuses: List<Int>,
    ): Boolean = false
    override suspend fun delete(id: Long) {
        rows.remove(id)
    }
}

private class RepositoryScheduler : DownloadScheduler {
    val enqueued = mutableListOf<Long>()
    override fun enqueue(downloadId: Long) {
        enqueued += downloadId
    }
    override fun enqueuePreparation(downloadId: Long) = Unit
    override fun cancel(downloadId: Long) = Unit
}

private object UnusedHubRemote : HubDownloadRemote {
    override suspend fun downloads(hubSourceId: Long): HubDownloadSnapshot =
        error("not used")
    override suspend fun enqueue(hubSourceId: Long, contentId: String) = error("not used")
    override suspend fun action(hubSourceId: Long, serverDownloadId: String, action: String) =
        error("not used")
    override suspend fun delete(hubSourceId: Long, serverDownloadId: String) = error("not used")
}
