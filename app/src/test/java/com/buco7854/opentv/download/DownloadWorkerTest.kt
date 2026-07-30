package com.buco7854.opentv.download

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.workDataOf
import androidx.work.testing.TestListenableWorkerBuilder
import com.buco7854.opentv.core.model.Download
import com.buco7854.opentv.core.model.DownloadStatus
import com.buco7854.opentv.core.model.Playlist
import com.buco7854.opentv.core.storage.DownloadStore
import com.buco7854.opentv.core.storage.PlaylistStore
import com.buco7854.opentv.data.prefs.PlayerSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DownloadWorkerTest {
    private lateinit var server: MockWebServer
    private lateinit var context: Context
    private lateinit var store: WorkerDownloadStore
    private lateinit var hubAccess: FakeHubWorkerAccess

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        context = ApplicationProvider.getApplicationContext()
        store = WorkerDownloadStore()
        hubAccess = FakeHubWorkerAccess()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `signed file URL pulls without bearer and bypasses provider gate`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("finished-file"))
        val url = server.url("/api/v1/downloads/server-1/file?token=short").toString()
        val id = hubRow(url, totalBytes = 13)
        hubAccess.states += state(url, status = "DONE", downloaded = 13, total = 13)

        val result = worker(id).doWork()

        assertSuccess(result)
        assertEquals("finished-file", File(requireNotNull(store.get(id)).filePath).readText())
        assertEquals(DownloadStatus.DONE, store.get(id)?.status)
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `local provider download still uses its configured gate`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("provider-file"))
        val path = File(context.cacheDir, "provider-download-${System.nanoTime()}.mp4")
        val id = store.insert(
            Download(
                title = "Provider movie",
                url = server.url("/movie").toString(),
                filePath = path.absolutePath,
            ),
        )
        var acquiredLimit: Int? = null
        val dependencies = DownloadWorkerDependencies(
            downloads = store,
            playlists = ThrowingPlaylistStore,
            settings = flowOf(PlayerSettings(downloadLimit = 2)),
            accountInfo = { error("explicit limit must not query an Xtream account") },
            httpClient = OkHttpClient(),
            userAgent = { "OpenTV-Test" },
            activePlaybackHost = MutableStateFlow(null),
            hubDownloads = hubAccess,
            withDownloadSlot = { limit, block ->
                acquiredLimit = limit
                block()
            },
        )

        assertSuccess(worker(id, dependencies).doWork())

        assertEquals(2, acquiredLimit)
        assertEquals(DownloadStatus.DONE, store.get(id)?.status)
        assertEquals("provider-file", path.readText())
        assertTrue(hubAccess.refreshes.isEmpty())
    }

    @Test
    fun `replacement worker cannot overlap an old writer for the same row`() = runBlocking {
        val path = File(context.cacheDir, "provider-overlap-${System.nanoTime()}.mp4")
        val id = store.insert(
            Download(
                title = "Provider movie",
                url = server.url("/movie").toString(),
                filePath = path.absolutePath,
            ),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("first"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("second"))
        val gateEntered = CompletableDeferred<Unit>()
        val releaseGate = CompletableDeferred<Unit>()
        val gateEntries = AtomicInteger()
        val dependencies = providerDependencies { _, block ->
            gateEntries.incrementAndGet()
            gateEntered.complete(Unit)
            releaseGate.await()
            block()
        }

        val first = async(Dispatchers.IO) { worker(id, dependencies).doWork() }
        gateEntered.await()
        val replacement = async(start = CoroutineStart.UNDISPATCHED) {
            worker(id, dependencies).doWork()
        }

        assertFalse(replacement.isCompleted)
        assertEquals(1, gateEntries.get())
        releaseGate.complete(Unit)
        assertSuccess(first.await())
        assertSuccess(replacement.await())
        assertEquals(1, server.requestCount)
        assertEquals("first", path.readText())
        assertEquals(DownloadStatus.DONE, store.get(id)?.status)
    }

    @Test
    fun `factory replacement workers share the same row lock owner`() = runBlocking {
        val path = File(context.cacheDir, "provider-factory-overlap-${System.nanoTime()}.mp4")
        val id = store.insert(
            Download(
                title = "Provider movie",
                url = server.url("/movie").toString(),
                filePath = path.absolutePath,
            ),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("first"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("second"))
        val gateEntered = CompletableDeferred<Unit>()
        val releaseGate = CompletableDeferred<Unit>()
        val gateEntries = AtomicInteger()
        var dependencyInstances = 0
        val factory = DownloadWorkerFactory {
            dependencyInstances++
            providerDependencies { _, block ->
                gateEntries.incrementAndGet()
                gateEntered.complete(Unit)
                releaseGate.await()
                block()
            }
        }

        val first = async(Dispatchers.IO) { worker(id, factory).doWork() }
        gateEntered.await()
        val replacement = async(start = CoroutineStart.UNDISPATCHED) {
            worker(id, factory).doWork()
        }

        assertFalse(replacement.isCompleted)
        assertEquals(1, gateEntries.get())
        releaseGate.complete(Unit)
        assertSuccess(first.await())
        assertSuccess(replacement.await())
        assertEquals(1, dependencyInstances)
        assertEquals(1, server.requestCount)
        assertEquals("first", path.readText())
        assertEquals(DownloadStatus.DONE, store.get(id)?.status)
    }

    @Test
    fun `local provider ranged chunks continue to the declared total`() = runBlocking {
        val path = File(context.cacheDir, "provider-range-${System.nanoTime()}.mp4")
        path.writeText("abc")
        val id = store.insert(
            Download(
                title = "Provider movie",
                url = server.url("/movie").toString(),
                filePath = path.absolutePath,
            ),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 3-4/6")
                .setBody("de"),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 5-5/6")
                .setBody("f"),
        )

        assertSuccess(worker(id, providerDependencies()).doWork())

        assertEquals("abcdef", path.readText())
        assertEquals("bytes=3-", server.takeRequest().getHeader("Range"))
        assertEquals("bytes=5-", server.takeRequest().getHeader("Range"))
        assertEquals(6L, store.get(id)?.totalBytes)
        assertEquals(DownloadStatus.DONE, store.get(id)?.status)
    }

    @Test
    fun `provider 416 with a different resource size cannot complete a partial file`() = runBlocking {
        val path = File(context.cacheDir, "provider-416-${System.nanoTime()}.mp4")
        path.writeText("abc")
        val id = store.insert(
            Download(
                title = "Provider movie",
                url = server.url("/movie").toString(),
                filePath = path.absolutePath,
            ),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(416)
                .setHeader("Content-Range", "bytes */6"),
        )

        assertFailure(worker(id, providerDependencies()).doWork())

        assertEquals(DownloadStatus.FAILED, store.get(id)?.status)
        assertNotEquals(DownloadStatus.DONE, store.get(id)?.status)
        assertEquals("abc", path.readText())
    }

    @Test
    fun `provider 416 completes only when its resource size matches the saved file`() = runBlocking {
        val path = File(context.cacheDir, "provider-complete-416-${System.nanoTime()}.mp4")
        path.writeText("abc")
        val id = store.insert(
            Download(
                title = "Provider movie",
                url = server.url("/movie").toString(),
                filePath = path.absolutePath,
            ),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(416)
                .setHeader("Content-Range", "bytes */3"),
        )

        assertSuccess(worker(id, providerDependencies()).doWork())

        assertEquals(DownloadStatus.DONE, store.get(id)?.status)
        assertEquals(3L, store.get(id)?.totalBytes)
        assertEquals("abc", path.readText())
    }

    @Test
    fun `provider 416 cannot override a previously recorded larger total`() = runBlocking {
        val path = File(context.cacheDir, "provider-recorded-416-${System.nanoTime()}.mp4")
        path.writeText("abc")
        val id = store.insert(
            Download(
                title = "Provider movie",
                url = server.url("/movie").toString(),
                filePath = path.absolutePath,
                totalBytes = 6,
                downloadedBytes = 3,
            ),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(416)
                .setHeader("Content-Range", "bytes */3"),
        )

        assertFailure(worker(id, providerDependencies()).doWork())

        assertEquals(DownloadStatus.FAILED, store.get(id)?.status)
        assertEquals(6L, store.get(id)?.totalBytes)
        assertEquals("abc", path.readText())
    }

    @Test
    fun `hub pull resumes with Range`() = runBlocking {
        val url = server.url("/api/v1/downloads/server-1/file?token=short").toString()
        val id = hubRow(url, totalBytes = 12)
        File(requireNotNull(store.get(id)).filePath).writeText("first-")
        hubAccess.states += state(url, status = "DONE", downloaded = 12, total = 12)
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 6-11/12")
                .setBody("second"),
        )

        assertSuccess(worker(id).doWork())

        assertEquals("bytes=6-", server.takeRequest().getHeader("Range"))
        assertEquals("first-second", File(requireNotNull(store.get(id)).filePath).readText())
    }

    @Test
    fun `worker follows a growing hub file and completes only after DONE sizes match`() = runBlocking {
        val url = server.url("/api/v1/downloads/server-1/file?token=short").toString()
        val id = hubRow(url, totalBytes = 9)
        server.enqueue(MockResponse().setResponseCode(200).setBody("abc"))
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 3-5/6")
                .setBody("def"),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 6-8/9")
                .setBody("ghi"),
        )
        hubAccess.states += state(url, status = "RUNNING", downloaded = 3, total = 9)
        hubAccess.states += state(url, status = "RUNNING", downloaded = 6, total = 9)
        hubAccess.states += state(url, status = "DONE", downloaded = 9, total = 9)

        assertSuccess(worker(id).doWork())

        assertNull(server.takeRequest().getHeader("Range"))
        assertEquals("bytes=3-", server.takeRequest().getHeader("Range"))
        assertEquals("bytes=6-", server.takeRequest().getHeader("Range"))
        assertEquals("abcdefghi", File(requireNotNull(store.get(id)).filePath).readText())
        assertEquals(DownloadStatus.DONE, store.get(id)?.status)
        assertEquals(listOf(4L to "server-1"), hubAccess.completed)
    }

    @Test
    fun `hub pull waits for server DONE after the local snapshot reaches its declared size`() =
        runBlocking {
            val url = server.url("/api/v1/downloads/server-1/file?token=short").toString()
            val id = hubRow(url, totalBytes = 3)
            server.enqueue(MockResponse().setResponseCode(200).setBody("abc"))
            server.enqueue(
                MockResponse()
                    .setResponseCode(416)
                    .setHeader("Content-Range", "bytes */3"),
            )
            hubAccess.states += state(url, status = "RUNNING", downloaded = 3, total = 3)
            hubAccess.states += state(url, status = "DONE", downloaded = 3, total = 3)

            assertSuccess(worker(id).doWork())

            assertEquals(2, server.requestCount)
            assertEquals(2, hubAccess.refreshes.size)
            assertEquals("abc", File(requireNotNull(store.get(id)).filePath).readText())
            assertEquals(DownloadStatus.DONE, store.get(id)?.status)
        }

    @Test
    fun `hub pull waits for all local bytes after the server reports DONE`() = runBlocking {
        val url = server.url("/api/v1/downloads/server-1/file?token=short").toString()
        val id = hubRow(url, totalBytes = 6)
        server.enqueue(MockResponse().setResponseCode(200).setBody("abc"))
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 3-5/6")
                .setBody("def"),
        )
        hubAccess.states += state(url, status = "DONE", downloaded = 6, total = 6)
        hubAccess.states += state(url, status = "DONE", downloaded = 6, total = 6)

        assertSuccess(worker(id).doWork())

        assertEquals(2, server.requestCount)
        assertEquals(2, hubAccess.refreshes.size)
        assertEquals("abcdef", File(requireNotNull(store.get(id)).filePath).readText())
        assertEquals(DownloadStatus.DONE, store.get(id)?.status)
    }

    @Test
    fun `slow growing hub file is not failed after three empty snapshots`() = runBlocking {
        val url = server.url("/api/v1/downloads/server-1/file?token=short").toString()
        val id = hubRow(url, totalBytes = 6)
        server.enqueue(MockResponse().setResponseCode(200).setBody("abc"))
        repeat(4) {
            server.enqueue(
                MockResponse()
                    .setResponseCode(416)
                    .setHeader("Content-Range", "bytes */3"),
            )
        }
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 3-5/6")
                .setBody("def"),
        )
        repeat(5) {
            hubAccess.states += state(url, status = "RUNNING", downloaded = 3, total = 6)
        }
        hubAccess.states += state(url, status = "DONE", downloaded = 6, total = 6)

        val result = worker(id).doWork()

        assertSuccess(result)
        assertEquals(DownloadStatus.DONE, store.get(id)?.status)
        assertEquals("abcdef", File(requireNotNull(store.get(id)).filePath).readText())
    }

    @Test
    fun `zero byte DONE response is rejected`() = runBlocking {
        val url = server.url("/api/v1/downloads/server-1/file?token=short").toString()
        val id = hubRow(url)
        server.enqueue(MockResponse().setResponseCode(200).setBody(""))
        hubAccess.states += state(url, status = "DONE", downloaded = 0, total = 0)

        val result = worker(id).doWork()

        assertFailure(result)
        assertEquals(DownloadStatus.FAILED, store.get(id)?.status)
        assertNotEquals(DownloadStatus.DONE, store.get(id)?.status)
    }

    @Test
    fun `server failure after partial bytes becomes a local failure`() = runBlocking {
        val url = server.url("/api/v1/downloads/server-1/file?token=short").toString()
        val id = hubRow(url, totalBytes = 9)
        server.enqueue(MockResponse().setResponseCode(200).setBody("abc"))
        hubAccess.states += state(
            url = null,
            status = "FAILED",
            downloaded = 3,
            total = 9,
            error = "Provider connection failed",
        )

        val result = worker(id).doWork()

        assertFailure(result)
        assertEquals(DownloadStatus.FAILED, store.get(id)?.status)
        assertEquals("Provider connection failed", store.get(id)?.error)
        assertEquals("abc", File(requireNotNull(store.get(id)).filePath).readText())
    }

    @Test
    fun `expired file token is reminted mid pull and the same Range resumes`() = runBlocking {
        val oldUrl = server.url("/api/v1/downloads/server-1/file?token=expired").toString()
        val freshUrl = server.url("/api/v1/downloads/server-1/file?token=fresh").toString()
        val id = hubRow(oldUrl, totalBytes = 12)
        server.enqueue(MockResponse().setResponseCode(200).setBody("first-"))
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 6-11/12")
                .setBody("second"),
        )
        hubAccess.states += state(oldUrl, status = "RUNNING", downloaded = 6, total = 12)
        hubAccess.states += state(freshUrl, status = "RUNNING", downloaded = 6, total = 12)
        hubAccess.states += state(freshUrl, status = "DONE", downloaded = 12, total = 12)

        assertSuccess(worker(id).doWork())

        val initial = server.takeRequest()
        val expired = server.takeRequest()
        val refreshed = server.takeRequest()
        assertNull(initial.getHeader("Range"))
        assertEquals("bytes=6-", expired.getHeader("Range"))
        assertEquals("bytes=6-", refreshed.getHeader("Range"))
        assertTrue(expired.path!!.contains("token=expired"))
        assertTrue(refreshed.path!!.contains("token=fresh"))
        assertEquals(3, hubAccess.refreshes.size)
        assertEquals("first-second", File(requireNotNull(store.get(id)).filePath).readText())
        assertEquals(freshUrl, store.get(id)?.url)
    }

    @Test
    fun `pause racing reminted URL persistence is not overwritten`() = runBlocking {
        val oldUrl = server.url("/api/v1/downloads/server-1/file?token=old").toString()
        val freshUrl = server.url("/api/v1/downloads/server-1/file?token=fresh").toString()
        val id = hubRow(oldUrl, totalBytes = 6)
        store.pauseBeforeUrlUpdate = true
        server.enqueue(MockResponse().setResponseCode(200).setBody("abc"))
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 3-5/6")
                .setBody("def"),
        )
        hubAccess.states += state(freshUrl, status = "RUNNING", downloaded = 3, total = 6)
        hubAccess.states += state(freshUrl, status = "DONE", downloaded = 6, total = 6)

        assertSuccess(worker(id).doWork())

        assertEquals(DownloadStatus.PAUSED, store.get(id)?.status)
        assertEquals(oldUrl, store.get(id)?.url)
        assertEquals("abc", File(requireNotNull(store.get(id)).filePath).readText())
    }

    @Test
    fun `repeated unauthorized file capabilities have a bounded refresh loop`() = runBlocking {
        val expiredUrl = server.url("/api/v1/downloads/server-1/file?token=expired").toString()
        val id = hubRow(expiredUrl, totalBytes = 6)
        repeat(4) {
            server.enqueue(MockResponse().setResponseCode(401))
            hubAccess.states += state(
                expiredUrl,
                status = "RUNNING",
                downloaded = 3,
                total = 6,
            )
        }

        assertFailure(worker(id).doWork())

        assertEquals(DownloadStatus.FAILED, store.get(id)?.status)
        assertEquals(4, hubAccess.refreshes.size)
        repeat(4) { server.takeRequest() }
        assertEquals(0, server.requestCount - 4)
    }

    @Test
    fun `revoked file capability is terminal without a refresh loop`() = runBlocking {
        val revokedUrl = server.url("/api/v1/downloads/server-1/file?token=revoked").toString()
        val id = hubRow(revokedUrl, totalBytes = 6)
        val path = File(requireNotNull(store.get(id)).filePath)
        path.writeText("abc")
        server.enqueue(
            MockResponse()
                .setResponseCode(410)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"code":"download_access_revoked","message":"The session has ended"}""",
                ),
        )

        assertSuccess(worker(id).doWork())

        assertEquals(DownloadStatus.HUB_GONE, store.get(id)?.status)
        assertEquals(1, server.requestCount)
        assertTrue(hubAccess.refreshes.isEmpty())
        assertEquals("abc", path.readText())
    }

    @Test
    fun `unrelated gone response is not treated as session revocation`() = runBlocking {
        val url = server.url("/api/v1/downloads/server-1/file?token=short").toString()
        val id = hubRow(url, totalBytes = 6)
        val path = File(requireNotNull(store.get(id)).filePath)
        path.writeText("abc")
        server.enqueue(
            MockResponse()
                .setResponseCode(410)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"code":"upstream_gone","message":"The upstream file is gone"}"""),
        )
        hubAccess.states += state(url, status = "RUNNING", downloaded = 3, total = 6)

        assertFailure(worker(id).doWork())

        assertEquals(DownloadStatus.FAILED, store.get(id)?.status)
        assertNotEquals(DownloadStatus.HUB_GONE, store.get(id)?.status)
        assertEquals("abc", path.readText())
        assertEquals(1, hubAccess.refreshes.size)
    }

    private suspend fun hubRow(url: String, totalBytes: Long = 0): Long {
        val path = File(context.cacheDir, "hub-download-${System.nanoTime()}.mp4").absolutePath
        return store.insert(
            Download(
                title = "Movie",
                url = url,
                filePath = path,
                status = DownloadStatus.QUEUED,
                hubSourceId = 4,
                contentId = "content-1",
                serverDownloadId = "server-1",
                totalBytes = totalBytes,
            ),
        )
    }

    private fun state(
        url: String?,
        status: String,
        downloaded: Long,
        total: Long,
        error: String? = null,
    ) = HubDownloadFileState(url, status, total, downloaded, error)

    private fun worker(downloadId: Long): DownloadWorker {
        val dependencies = DownloadWorkerDependencies(
            downloads = store,
            playlists = ThrowingPlaylistStore,
            settings = flow { error("hub pulls must not read the provider download limit") },
            accountInfo = { error("hub pulls must not query an Xtream account") },
            httpClient = OkHttpClient(),
            userAgent = { "OpenTV-Test" },
            activePlaybackHost = MutableStateFlow(server.hostName),
            hubDownloads = hubAccess,
            hubPollIntervalMs = 1,
            withDownloadSlot = { _, _ -> error("hub pulls must not consume the provider gate") },
        )
        return worker(downloadId, dependencies)
    }

    private fun worker(
        downloadId: Long,
        dependencies: DownloadWorkerDependencies,
    ): DownloadWorker {
        return worker(downloadId, DownloadWorkerFactory { dependencies })
    }

    private fun worker(
        downloadId: Long,
        factory: DownloadWorkerFactory,
    ): DownloadWorker {
        return TestListenableWorkerBuilder<DownloadWorker>(context)
            .setInputData(workDataOf(DownloadWorker.KEY_DOWNLOAD_ID to downloadId))
            .setWorkerFactory(factory)
            .build()
    }

    private fun providerDependencies(
        withDownloadSlot: suspend (Int, suspend () -> Unit) -> Unit = { _, block -> block() },
    ) = DownloadWorkerDependencies(
        downloads = store,
        playlists = ThrowingPlaylistStore,
        settings = flowOf(PlayerSettings(downloadLimit = 1)),
        accountInfo = { error("explicit limit must not query an Xtream account") },
        httpClient = OkHttpClient(),
        userAgent = { "OpenTV-Test" },
        activePlaybackHost = MutableStateFlow(null),
        hubDownloads = hubAccess,
        withDownloadSlot = withDownloadSlot,
    )

    private fun assertSuccess(result: ListenableWorker.Result) {
        assertEquals(ListenableWorker.Result.success()::class, result::class)
    }

    private fun assertFailure(result: ListenableWorker.Result) {
        assertEquals(ListenableWorker.Result.failure()::class, result::class)
    }
}

private class FakeHubWorkerAccess : HubDownloadWorkerAccess {
    val states = ArrayDeque<HubDownloadFileState>()
    val refreshes = mutableListOf<Pair<Long, String>>()
    val completed = mutableListOf<Pair<Long, String>>()

    override suspend fun prepare(downloadId: Long) = HubPreparationResult.Complete

    override suspend fun refreshFile(
        hubSourceId: Long,
        serverDownloadId: String,
    ): HubDownloadFileState {
        refreshes += hubSourceId to serverDownloadId
        return states.removeFirst()
    }

    override suspend fun localPullCompleted(hubSourceId: Long, serverDownloadId: String) {
        completed += hubSourceId to serverDownloadId
    }
}

private class WorkerDownloadStore : DownloadStore {
    private val state = MutableStateFlow<List<Download>>(emptyList())
    private var nextId = 1L
    var pauseBeforeUrlUpdate = false

    override fun observeAll(): Flow<List<Download>> = state
    override suspend fun get(id: Long) = state.value.firstOrNull { it.id == id }
    override suspend fun getByStatus(status: Int) = state.value.filter { it.status == status }
    override suspend fun getByStatuses(statuses: List<Int>) = state.value.filter { it.status in statuses }
    override suspend fun findByUrlWithStatus(url: String, statuses: List<Int>) =
        state.value.firstOrNull { it.url == url && it.status in statuses }
    override suspend fun findByHubContentWithStatus(
        hubSourceId: Long,
        contentId: String,
        statuses: List<Int>,
    ) = state.value.firstOrNull {
        it.hubSourceId == hubSourceId && it.contentId == contentId && it.status in statuses
    }

    override suspend fun insert(download: Download): Long {
        val id = nextId++
        state.value += download.copy(id = id)
        return id
    }

    override suspend fun update(download: Download) {
        val prior = state.value.firstOrNull { it.id == download.id }
        if (pauseBeforeUrlUpdate && prior != null && prior.url != download.url) {
            pauseBeforeUrlUpdate = false
            state.value = state.value.map {
                if (it.id == download.id) it.copy(status = DownloadStatus.PAUSED) else it
            }
        }
        state.value = state.value.map { if (it.id == download.id) download else it }
    }

    override suspend fun updateProgressIfStatus(
        id: Long,
        downloaded: Long,
        total: Long,
        expectedStatuses: List<Int>,
        status: Int,
    ): Boolean {
        val row = get(id)?.takeIf { it.status in expectedStatuses } ?: return false
        update(row.copy(downloadedBytes = downloaded, totalBytes = total, status = status))
        return true
    }

    override suspend fun updateStatusIfStatus(
        id: Long,
        expectedStatuses: List<Int>,
        status: Int,
        error: String?,
    ): Boolean {
        val row = get(id)?.takeIf { it.status in expectedStatuses } ?: return false
        update(row.copy(status = status, error = error))
        return true
    }

    override suspend fun updateUrlIfStatus(
        id: Long,
        url: String,
        expectedStatuses: List<Int>,
    ): Boolean {
        if (pauseBeforeUrlUpdate) {
            pauseBeforeUrlUpdate = false
            state.value = state.value.map {
                if (it.id == id) it.copy(status = DownloadStatus.PAUSED) else it
            }
        }
        val row = get(id)?.takeIf { it.status in expectedStatuses } ?: return false
        update(row.copy(url = url, error = null))
        return true
    }

    override suspend fun delete(id: Long) {
        state.value = state.value.filterNot { it.id == id }
    }
}

private object ThrowingPlaylistStore : PlaylistStore {
    override fun observeAll(): Flow<List<Playlist>> = flow { error("not used") }
    override suspend fun getAll(): List<Playlist> = error("hub pulls must bypass provider lookup")
    override suspend fun get(id: Long): Playlist? = error("not used")
    override fun observe(id: Long): Flow<Playlist?> = flow { error("not used") }
    override suspend fun insert(playlist: Playlist): Long = error("not used")
    override suspend fun update(playlist: Playlist) = error("not used")
    override suspend fun delete(id: Long) = error("not used")
}
