package com.buco7854.opentv.download

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import com.buco7854.opentv.R
import com.buco7854.opentv.core.download.DownloadFileName
import com.buco7854.opentv.core.model.Channel
import com.buco7854.opentv.core.model.ChannelKind
import com.buco7854.opentv.core.model.Download
import com.buco7854.opentv.core.model.DownloadStatus
import com.buco7854.opentv.core.storage.DownloadStore
import com.buco7854.opentv.data.prefs.PlayerPrefs
import com.buco7854.opentv.hub.HubUnreachableException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileNotFoundException

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

    @Test
    fun `retry after revoked capability truncates the old partial before replacement`() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val store = RepositoryDownloadStore()
            val scheduler = RepositoryScheduler()
            val scope = CoroutineScope(SupervisorJob())
            val preferences = HubDownloadPreferences(
                context.getSharedPreferences("repository-revoked-test", Context.MODE_PRIVATE),
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
            val partial = File(context.cacheDir, "revoked-partial-${System.nanoTime()}.mp4")
            partial.writeText("partial bytes from the revoked session")
            val id = store.insert(
                Download(
                    title = "Movie",
                    url = "https://hub.invalid/api/v1/downloads/server-1/file?token=revoked",
                    filePath = partial.absolutePath,
                    status = DownloadStatus.HUB_GONE,
                    totalBytes = 100,
                    downloadedBytes = partial.length(),
                    hubSourceId = 4,
                    contentId = "content-1",
                    serverDownloadId = "server-1",
                ),
            )

            try {
                var failedAfterPreparation = false
                try {
                    repository.retry(requireNotNull(store.get(id)))
                } catch (_: IllegalStateException) {
                    failedAfterPreparation = true
                }

                assertTrue(failedAfterPreparation)
                assertEquals(0L, partial.length())
                assertEquals(DownloadStatus.PREPARING, store.get(id)?.status)
                assertEquals(null, store.get(id)?.serverDownloadId)
                assertEquals(listOf(id), scheduler.preparations)
            } finally {
                scope.cancel()
                partial.delete()
            }
        }

    @Test
    fun `delete keeps row when document provider refuses physical deletion`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Robolectric.setupContentProvider(
            DeleteOutcomeProvider::class.java,
            "downloads-delete-refused",
        )
        val fixture = deleteFixture(context, "repository-delete-test")
        val id = fixture.store.insert(
            Download(
                title = "Refused",
                url = "https://provider.invalid/refused.mp4",
                filePath = "content://downloads-delete-refused/document/refused",
                status = DownloadStatus.DONE,
            ),
        )

        try {
            val result = fixture.repository.delete(requireNotNull(fixture.store.get(id)))

            assertEquals(context.getString(R.string.downloads_delete_failed), result)
            assertNotNull(fixture.store.get(id))
        } finally {
            fixture.scope.cancel()
        }
    }

    @Test
    fun `hub delete records server release before physical deletion can fail`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Robolectric.setupContentProvider(
            DeleteOutcomeProvider::class.java,
            "downloads-hub-delete-refused",
        )
        val store = RepositoryDownloadStore()
        val scheduler = RepositoryScheduler()
        val remote = RecordingHubRemote().apply {
            error = HubUnreachableException("offline")
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val preferences = HubDownloadPreferences(
            context.getSharedPreferences(
                "repository-hub-delete-refused-${System.nanoTime()}",
                Context.MODE_PRIVATE,
            ),
        )
        val coordinator = HubDownloadCoordinator(
            store,
            remote,
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
        val id = store.insert(
            Download(
                title = "Hub movie",
                url = "https://hub.invalid/api/v1/downloads/server-1/file?token=short",
                filePath = "content://downloads-hub-delete-refused/document/refused",
                status = DownloadStatus.DONE,
                hubSourceId = 4,
                contentId = "content-1",
                serverDownloadId = "server-1",
            ),
        )

        try {
            val result = repository.delete(requireNotNull(store.get(id)))

            assertEquals(context.getString(R.string.downloads_delete_failed), result)
            assertNotNull(store.get(id))
            assertEquals(
                listOf(PendingHubDownloadDelete(4, "server-1")),
                preferences.pendingServerDeletes(),
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `delete removes row when provider confirms document is already gone`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Robolectric.setupContentProvider(
            DeleteOutcomeProvider::class.java,
            "downloads-delete-missing",
        )
        val fixture = deleteFixture(context, "repository-delete-missing-test")
        val id = fixture.store.insert(
            Download(
                title = "Already gone",
                url = "https://provider.invalid/gone.mp4",
                filePath = "content://downloads-delete-missing/document/gone",
                status = DownloadStatus.DONE,
            ),
        )

        try {
            val result = fixture.repository.delete(requireNotNull(fixture.store.get(id)))

            assertNull(result)
            assertNull(fixture.store.get(id))
        } finally {
            fixture.scope.cancel()
        }
    }

    @Test
    fun `deleting a hub download also deletes its server association`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = RepositoryDownloadStore()
        val scheduler = RepositoryScheduler()
        val remote = RecordingHubRemote()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = HubDownloadCoordinator(
            store,
            remote,
            scheduler,
            HubDownloadPreferences(
                context.getSharedPreferences(
                    "repository-hub-delete-${System.nanoTime()}",
                    Context.MODE_PRIVATE,
                ),
            ),
            scope,
        )
        val repository = DownloadRepository(
            context,
            store,
            PlayerPrefs(context),
            scheduler,
            coordinator,
        )
        val local = File(context.cacheDir, "hub-delete-${System.nanoTime()}.mp4")
        local.writeText("downloaded")
        val id = store.insert(
            Download(
                title = "Hub movie",
                url = "https://hub.invalid/api/v1/downloads/server-1/file?token=short",
                filePath = local.absolutePath,
                status = DownloadStatus.DONE,
                hubSourceId = 4,
                contentId = "content-1",
                serverDownloadId = "server-1",
            ),
        )

        try {
            assertNull(repository.delete(requireNotNull(store.get(id))))

            assertNull(store.get(id))
            assertTrue(!local.exists())
            assertEquals(listOf(4L to "server-1"), remote.deleted)
        } finally {
            scope.cancel()
            local.delete()
        }
    }

    @Test
    fun `unreachable hub does not block local delete and its server cleanup is retried`() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val store = RepositoryDownloadStore()
            val scheduler = RepositoryScheduler()
            val remote = RecordingHubRemote().apply {
                error = HubUnreachableException("offline")
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val preferences = HubDownloadPreferences(
                context.getSharedPreferences(
                    "repository-hub-delete-retry-${System.nanoTime()}",
                    Context.MODE_PRIVATE,
                ),
            )
            val coordinator = HubDownloadCoordinator(
                store,
                remote,
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
            val local = File(context.cacheDir, "hub-delete-retry-${System.nanoTime()}.mp4")
            local.writeText("downloaded")
            val id = store.insert(
                Download(
                    title = "Hub movie",
                    url = "https://hub.invalid/api/v1/downloads/server-1/file?token=short",
                    filePath = local.absolutePath,
                    status = DownloadStatus.DONE,
                    hubSourceId = 4,
                    contentId = "content-1",
                    serverDownloadId = "server-1",
                ),
            )

            try {
                assertNull(repository.delete(requireNotNull(store.get(id))))

                assertNull(store.get(id))
                assertTrue(!local.exists())
                assertEquals(
                    listOf(PendingHubDownloadDelete(4, "server-1")),
                    preferences.pendingServerDeletes(),
                )

                remote.error = null
                assertTrue(coordinator.retryPendingServerDeletes())

                assertEquals(listOf(4L to "server-1"), remote.deleted)
                assertTrue(preferences.pendingServerDeletes().isEmpty())
            } finally {
                scope.cancel()
                local.delete()
            }
        }

    private fun deleteFixture(context: Context, preferencesName: String): DeleteFixture {
        val store = RepositoryDownloadStore()
        val scheduler = RepositoryScheduler()
        val scope = CoroutineScope(SupervisorJob())
        val coordinator = HubDownloadCoordinator(
            store,
            UnusedHubRemote,
            scheduler,
            HubDownloadPreferences(
                context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE),
            ),
            scope,
        )
        return DeleteFixture(
            DownloadRepository(context, store, PlayerPrefs(context), scheduler, coordinator),
            store,
            scope,
        )
    }
}

private data class DeleteFixture(
    val repository: DownloadRepository,
    val store: RepositoryDownloadStore,
    val scope: CoroutineScope,
)

private class DeleteOutcomeProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val columns = projection ?: arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        return MatrixCursor(columns).apply {
            if (uri.lastPathSegment != "gone") {
                addRow(columns.map { column ->
                    when (column) {
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID -> "refused"
                        else -> null
                    }
                })
            }
        }
    }

    override fun getType(uri: Uri) = "video/mp4"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        if (uri.lastPathSegment == "gone") throw FileNotFoundException()
        return 0
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ) = 0
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
    val preparations = mutableListOf<Long>()
    override fun enqueue(downloadId: Long) {
        enqueued += downloadId
    }
    override fun enqueuePreparation(downloadId: Long) {
        preparations += downloadId
    }
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

private class RecordingHubRemote : HubDownloadRemote {
    val deleted = mutableListOf<Pair<Long, String>>()
    var error: Throwable? = null

    override suspend fun downloads(hubSourceId: Long): HubDownloadSnapshot = error("not used")
    override suspend fun enqueue(hubSourceId: Long, contentId: String) = error("not used")
    override suspend fun action(hubSourceId: Long, serverDownloadId: String, action: String) =
        error("not used")
    override suspend fun delete(hubSourceId: Long, serverDownloadId: String) {
        error?.let { throw it }
        deleted += hubSourceId to serverDownloadId
    }
}
