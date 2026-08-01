package com.buco7854.opentv.download

import android.content.SharedPreferences
import com.buco7854.opentv.contract.DownloadDto
import com.buco7854.opentv.core.model.Download
import com.buco7854.opentv.core.model.DownloadStatus
import com.buco7854.opentv.core.model.HubSource
import com.buco7854.opentv.core.net.HttpResponseSpec
import com.buco7854.opentv.core.net.HttpTransport
import com.buco7854.opentv.core.storage.DownloadStore
import com.buco7854.opentv.core.storage.HubSourceStore
import com.buco7854.opentv.hub.HubAccountRepository
import com.buco7854.opentv.hub.HubApi
import com.buco7854.opentv.hub.HubCapacityException
import com.buco7854.opentv.hub.HubGoneException
import com.buco7854.opentv.hub.HubRegistry
import com.buco7854.opentv.hub.HubSessionVault
import com.buco7854.opentv.hub.HubUnauthorizedException
import com.buco7854.opentv.hub.HubUnreachableException
import com.buco7854.opentv.hub.TokenCipher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class HubDownloadCoordinatorTest {
    @Test
    fun `one row hands off as soon as the growing server blob has bytes`() = runTest {
        val store = FakeDownloadStore()
        val scheduler = FakeDownloadScheduler()
        val remote = FakeHubDownloadRemote()
        val coordinator = coordinator(store, remote, scheduler, this)
        remote.afterEnqueue = listOf(dto(status = "QUEUED", downloaded = 0, total = 100))

        assertEquals(null, coordinator.enqueue(4, "content-1", "Movie") { "/tmp/movie-$it.mp4" })
        val preparing = store.single()
        assertEquals(DownloadStatus.PREPARING, preparing.status)
        assertEquals(0, preparing.downloadedBytes)
        assertEquals(100, preparing.totalBytes)
        assertEquals("server-1", preparing.serverDownloadId)
        assertTrue(scheduler.preparations.contains(preparing.id))

        remote.rows = listOf(
            dto(status = "RUNNING", downloaded = 25, total = 100, token = "short-lived"),
        )
        assertEquals(HubPreparationResult.HandedOff, coordinator.prepare(preparing.id))
        val handedOff = store.single()
        assertEquals(DownloadStatus.QUEUED, handedOff.status)
        assertEquals(0, handedOff.downloadedBytes)
        assertTrue(handedOff.url.endsWith("/downloads/server-1/file?token=short-lived"))
        assertTrue(scheduler.pulls.contains(handedOff.id))
    }

    @Test
    fun `file refresh carries DTO status sizes error and a reminted URL`() = runTest {
        val remote = FakeHubDownloadRemote().apply {
            rows = listOf(
                dto(
                    status = "FAILED",
                    downloaded = 40,
                    total = 100,
                    token = "replacement",
                    error = "Provider stopped",
                ),
            )
        }
        val coordinator = coordinator(
            FakeDownloadStore(),
            remote,
            FakeDownloadScheduler(),
            this,
        )

        assertEquals(
            HubDownloadFileState(
                url = "https://hub.example/api/v1/downloads/server-1/file?token=replacement",
                status = "FAILED",
                totalBytes = 100,
                downloadedBytes = 40,
                error = "Provider stopped",
            ),
            coordinator.refreshFile(4, "server-1"),
        )
    }

    @Test
    fun `remove-after-download is per hub and defaults off`() = runTest {
        val store = FakeDownloadStore()
        val remote = FakeHubDownloadRemote()
        val preferences = HubDownloadPreferences(FakeSharedPreferences())
        val coordinator = HubDownloadCoordinator(
            store,
            remote,
            FakeDownloadScheduler(),
            preferences,
            this,
        )

        coordinator.localPullCompleted(4, "kept")
        preferences.setRemoveFromServerAfterDownload(4, true)
        coordinator.localPullCompleted(4, "removed")
        coordinator.localPullCompleted(5, "other-hub")

        assertEquals(listOf(4L to "removed"), remote.deleted)
    }

    @Test
    fun `remove-after-download cleanup survives an outage and retries in foreground`() = runTest {
        val preferences = HubDownloadPreferences(FakeSharedPreferences()).apply {
            setRemoveFromServerAfterDownload(4, true)
        }
        val remote = FakeHubDownloadRemote().apply {
            error = HubUnreachableException("offline")
        }
        val coordinator = HubDownloadCoordinator(
            FakeDownloadStore(),
            remote,
            FakeDownloadScheduler(),
            preferences,
            this,
        )

        coordinator.localPullCompleted(4, "server-1")

        assertEquals(
            listOf(PendingHubDownloadDelete(4, "server-1")),
            preferences.pendingServerDeletes(),
        )

        remote.error = null
        coordinator.setForeground(true)
        runCurrent()
        coordinator.setForeground(false)

        assertEquals(listOf(4L to "server-1"), remote.deleted)
        assertTrue(preferences.pendingServerDeletes().isEmpty())
    }

    @Test
    fun `preferences discard entries for removed hubs`() {
        val preferences = HubDownloadPreferences(FakeSharedPreferences())
        preferences.setRemoveFromServerAfterDownload(4, true)
        preferences.setRemoveFromServerAfterDownload(5, true)
        preferences.enqueueServerDelete(5, "server-1")

        preferences.pruneMissingHubs(setOf(4))

        assertTrue(preferences.removeFromServerAfterDownload(4))
        assertFalse(preferences.removeFromServerAfterDownload(5))
        assertTrue(preferences.pendingServerDeletes().isEmpty())
    }

    @Test
    fun `reachable hub releases pending associations before removal prunes them`() = runTest {
        val preferences = HubDownloadPreferences(FakeSharedPreferences()).apply {
            enqueueServerDelete(4, "server-1")
            enqueueServerDelete(4, "server-2")
        }
        val remote = FakeHubDownloadRemote()
        val coordinator = HubDownloadCoordinator(
            FakeDownloadStore(),
            remote,
            FakeDownloadScheduler(),
            preferences,
            this,
        )

        val unreleased = coordinator.flushPendingServerDeletes(4)
        preferences.pruneHub(4)

        assertEquals(0, unreleased)
        assertEquals(listOf(4L to "server-1", 4L to "server-2"), remote.deleted)
        assertTrue(preferences.pendingServerDeletes().isEmpty())
    }

    @Test
    fun `unreachable hub removal prunes the intent and reports what remains on the server`() =
        runTest {
            val preferences = HubDownloadPreferences(FakeSharedPreferences()).apply {
                enqueueServerDelete(4, "server-1")
            }
            val remote = FakeHubDownloadRemote().apply {
                error = HubUnreachableException("offline")
            }
            val coordinator = HubDownloadCoordinator(
                FakeDownloadStore(),
                remote,
                FakeDownloadScheduler(),
                preferences,
                this,
            )

            val unreleased = coordinator.flushPendingServerDeletes(4)
            preferences.pruneHub(4)

            assertEquals(1, unreleased)
            assertTrue(remote.deleted.isEmpty())
            assertTrue(preferences.pendingServerDeletes().isEmpty())
        }

    @Test
    fun `account removal flushes reachable associations before credentials and preferences are pruned`() =
        runTest {
            val preferences = HubDownloadPreferences(FakeSharedPreferences()).apply {
                enqueueServerDelete(REMOVAL_HUB_ID, "server-1")
                enqueueServerDelete(REMOVAL_HUB_ID, "server-2")
            }
            val remote = FakeHubDownloadRemote()
            val coordinator = HubDownloadCoordinator(
                FakeDownloadStore(),
                remote,
                FakeDownloadScheduler(),
                preferences,
                this,
            )
            val fixture = removalRegistry()

            val result = HubAccountRepository(fixture.registry, coordinator, preferences)
                .remove(REMOVAL_HUB_ID)

            assertEquals(0, result.unreleasedDownloadAssociations)
            assertEquals(
                listOf(REMOVAL_HUB_ID to "server-1", REMOVAL_HUB_ID to "server-2"),
                remote.deleted,
            )
            assertTrue(preferences.pendingServerDeletes().isEmpty())
            assertNull(fixture.store.get(REMOVAL_HUB_ID))
            assertNull(fixture.vault.token(REMOVAL_HUB_ID))
        }

    @Test
    fun `account removal prunes unreachable associations and reports their exact count`() = runTest {
        val preferences = HubDownloadPreferences(FakeSharedPreferences()).apply {
            enqueueServerDelete(REMOVAL_HUB_ID, "server-1")
        }
        val remote = FakeHubDownloadRemote().apply {
            error = HubUnreachableException("offline")
        }
        val coordinator = HubDownloadCoordinator(
            FakeDownloadStore(),
            remote,
            FakeDownloadScheduler(),
            preferences,
            this,
        )
        val fixture = removalRegistry()

        val result = HubAccountRepository(fixture.registry, coordinator, preferences)
            .remove(REMOVAL_HUB_ID)

        assertEquals(1, result.unreleasedDownloadAssociations)
        assertTrue(preferences.pendingServerDeletes().isEmpty())
        assertNull(fixture.store.get(REMOVAL_HUB_ID))
        assertNull(fixture.vault.token(REMOVAL_HUB_ID))
    }

    @Test
    fun `concurrent enqueue of the same hub content creates one local row`() = runTest {
        val store = FakeDownloadStore().apply { blockFirstHubFind = true }
        val remote = FakeHubDownloadRemote().apply {
            afterEnqueue = listOf(dto(status = "QUEUED", downloaded = 0, total = 100))
        }
        val coordinator = coordinator(store, remote, FakeDownloadScheduler(), this)

        val first = async {
            coordinator.enqueue(4, "content-1", "Movie") { "/tmp/movie-$it.mp4" }
        }
        store.firstHubFindEntered.await()
        val second = async {
            coordinator.enqueue(4, "content-1", "Movie") { "/tmp/movie-$it.mp4" }
        }
        runCurrent()
        store.releaseFirstHubFind.complete(Unit)

        assertEquals(setOf(null, "downloading"), awaitAll(first, second).toSet())
        assertEquals(1, store.all().size)
        assertEquals(listOf(4L to "content-1"), remote.enqueued)
    }

    @Test
    fun `target allocation failure does not leave a dedupe-blocking row`() = runTest {
        val store = FakeDownloadStore()
        val coordinator = coordinator(
            store,
            FakeHubDownloadRemote(),
            FakeDownloadScheduler(),
            this,
        )

        var failed = false
        try {
            coordinator.enqueue(4, "content-1", "Movie") {
                throw IOException("Storage unavailable")
            }
        } catch (_: IOException) {
            failed = true
        }

        assertTrue(failed)
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `cancelling target allocation removes the inserted row`() = runTest {
        val store = FakeDownloadStore()
        val coordinator = coordinator(
            store,
            FakeHubDownloadRemote(),
            FakeDownloadScheduler(),
            this,
        )
        val targetStarted = CompletableDeferred<Unit>()

        val enqueue = launch {
            coordinator.enqueue(4, "content-1", "Movie") {
                targetStarted.complete(Unit)
                awaitCancellation()
            }
        }
        targetStarted.await()
        enqueue.cancelAndJoin()

        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `retrying a failed handed-off hub download retries the server transfer`() = runTest {
        val store = FakeDownloadStore()
        val scheduler = FakeDownloadScheduler()
        val remote = FakeHubDownloadRemote()
        val coordinator = coordinator(store, remote, scheduler, this)
        val id = store.insert(
            Download(
                title = "Movie",
                url = "https://hub.example/api/v1/downloads/server-1/file?token=old",
                filePath = "/tmp/movie",
                status = DownloadStatus.FAILED,
                hubSourceId = 4,
                contentId = "content-1",
                serverDownloadId = "server-1",
            ),
        )

        coordinator.retryPreparation(requireNotNull(store.get(id)))

        assertEquals(listOf(Triple(4L, "server-1", "retry")), remote.actions)
        assertEquals(DownloadStatus.QUEUED, store.single().status)
        assertEquals(listOf(id), scheduler.pulls)
    }

    @Test
    fun `new local enqueue restarts an existing failed server download`() = runTest {
        val store = FakeDownloadStore()
        val remote = FakeHubDownloadRemote().apply {
            rows = listOf(
                dto(
                    status = "FAILED",
                    downloaded = 25,
                    total = 100,
                    error = "Provider stopped",
                ),
            )
            afterAction = listOf(dto(status = "QUEUED", downloaded = 25, total = 100))
        }
        val coordinator = coordinator(store, remote, FakeDownloadScheduler(), this)

        assertEquals(
            null,
            coordinator.enqueue(4, "content-1", "Movie") { "/tmp/movie-$it.mp4" },
        )

        assertEquals(listOf(Triple(4L, "server-1", "retry")), remote.actions)
        assertEquals(DownloadStatus.PREPARING, store.single().status)
        assertEquals("server-1", store.single().serverDownloadId)
    }

    @Test
    fun `retrying a gone server download creates a replacement`() = runTest {
        val store = FakeDownloadStore()
        val remote = FakeHubDownloadRemote().apply {
            afterEnqueue = listOf(dto(status = "QUEUED", downloaded = 0, total = 100))
        }
        val coordinator = coordinator(store, remote, FakeDownloadScheduler(), this)
        val id = store.insert(
            Download(
                title = "Movie",
                url = "",
                filePath = "/tmp/movie",
                status = DownloadStatus.HUB_GONE,
                hubSourceId = 4,
                contentId = "content-1",
                serverDownloadId = "missing-server-download",
            ),
        )

        coordinator.retryPreparation(requireNotNull(store.get(id)))

        assertEquals(listOf(4L to "content-1"), remote.enqueued)
        assertEquals(DownloadStatus.PREPARING, store.single().status)
        assertEquals("server-1", store.single().serverDownloadId)
    }

    @Test
    fun `pause racing preparation handoff remains paused`() = runTest {
        val store = FakeDownloadStore()
        val scheduler = FakeDownloadScheduler()
        val remote = FakeHubDownloadRemote().apply {
            rows = listOf(
                dto(status = "RUNNING", downloaded = 25, total = 100, token = "short-lived"),
            )
            blockNextDownloads = true
        }
        val coordinator = coordinator(store, remote, scheduler, this)
        val id = store.insert(
            Download(
                title = "Movie",
                url = "",
                filePath = "/tmp/movie",
                status = DownloadStatus.PREPARING,
                hubSourceId = 4,
                contentId = "content-1",
            ),
        )

        val preparing = async { coordinator.prepare(id) }
        remote.downloadsEntered.await()
        val pausing = async {
            coordinator.pausePreparation(requireNotNull(store.get(id)))
        }
        runCurrent()
        remote.releaseDownloads.complete(Unit)
        preparing.await()
        pausing.await()

        assertEquals(DownloadStatus.PAUSED, store.single().status)
        assertTrue(scheduler.cancelled.contains(id))
    }

    @Test
    fun `cancelling a server pause is not swallowed`() = runTest {
        val store = FakeDownloadStore()
        val scheduler = FakeDownloadScheduler()
        val remote = FakeHubDownloadRemote().apply {
            blockNextAction = true
        }
        val coordinator = coordinator(store, remote, scheduler, this)
        val id = store.insert(
            Download(
                title = "Movie",
                url = "",
                filePath = "/tmp/movie",
                status = DownloadStatus.PREPARING,
                hubSourceId = 4,
                contentId = "content-1",
                serverDownloadId = "server-1",
            ),
        )
        var returnedNormally = false

        val pause = launch {
            coordinator.pausePreparation(requireNotNull(store.get(id)))
            returnedNormally = true
        }
        remote.actionEntered.await()
        pause.cancel()
        remote.releaseAction.complete(Unit)
        pause.join()

        assertFalse(returnedNormally)
        assertTrue(pause.isCancelled)
    }

    @Test
    fun `keyed mutexes are pruned after unique key churn`() = runTest {
        val locks = KeyedMutexPool<Long>()

        repeat(10_000) { key ->
            locks.withKeyLock(key.toLong()) { Unit }
        }

        assertEquals(0, locks.retainedKeyCount)
    }

    @Test
    fun `keyed mutex is retained while a waiter owns the handoff`() = runTest {
        val locks = KeyedMutexPool<String>()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        val thirdEntered = CompletableDeferred<Unit>()

        val first = launch {
            locks.withKeyLock("same") {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()
        val second = launch {
            locks.withKeyLock("same") {
                secondEntered.complete(Unit)
                releaseSecond.await()
            }
        }
        runCurrent()
        releaseFirst.complete(Unit)
        secondEntered.await()

        assertEquals(1, locks.retainedKeyCount)
        val third = launch {
            locks.withKeyLock("same") {
                thirdEntered.complete(Unit)
            }
        }
        runCurrent()
        assertFalse(thirdEntered.isCompleted)

        releaseSecond.complete(Unit)
        first.join()
        second.join()
        third.join()
        assertTrue(thirdEntered.isCompleted)
        assertEquals(0, locks.retainedKeyCount)
    }

    @Test
    fun `pausing after handoff also pauses the owned server download`() = runTest {
        val store = FakeDownloadStore()
        val scheduler = FakeDownloadScheduler()
        val remote = FakeHubDownloadRemote()
        val coordinator = coordinator(store, remote, scheduler, this)
        val id = store.insert(
            Download(
                title = "Movie",
                url = "https://hub.example/api/v1/downloads/server-1/file?token=short",
                filePath = "/tmp/movie",
                status = DownloadStatus.RUNNING,
                hubSourceId = 4,
                contentId = "content-1",
                serverDownloadId = "server-1",
            ),
        )

        coordinator.pausePreparation(requireNotNull(store.get(id)))

        assertEquals(DownloadStatus.PAUSED, store.single().status)
        assertEquals(listOf(Triple(4L, "server-1", "pause")), remote.actions)
        assertEquals(listOf(id), scheduler.cancelled)
    }

    @Test
    fun `typed hub failures become distinct local states`() = runTest {
        val cases = listOf(
            HubGoneException("gone", "gone") to DownloadStatus.HUB_GONE,
            HubUnauthorizedException("unauthorized", "signed out") to DownloadStatus.HUB_SIGNED_OUT,
            HubCapacityException("capacity", "busy", 12_000) to DownloadStatus.HUB_CAPACITY,
            HubUnreachableException("offline") to DownloadStatus.HUB_UNREACHABLE,
        )
        for ((failure, expectedStatus) in cases) {
            val store = FakeDownloadStore()
            val remote = FakeHubDownloadRemote().apply { error = failure }
            val coordinator = coordinator(store, remote, FakeDownloadScheduler(), this)
            val id = store.insert(
                Download(
                    title = "Movie",
                    url = "",
                    filePath = "/tmp/movie",
                    status = DownloadStatus.PREPARING,
                    hubSourceId = 4,
                    contentId = "content-1",
                ),
            )

            val result = coordinator.prepare(id)

            assertEquals(expectedStatus, store.single().status)
            if (failure is HubCapacityException) {
                assertEquals(HubPreparationResult.RetryAfter(12_000), result)
            }
            if (failure is HubUnreachableException) {
                assertEquals(HubPreparationResult.RetryAfter(10_000), result)
            }
        }
    }

    private fun coordinator(
        store: FakeDownloadStore,
        remote: FakeHubDownloadRemote,
        scheduler: FakeDownloadScheduler,
        scope: TestScope,
    ) = HubDownloadCoordinator(
        store,
        remote,
        scheduler,
        HubDownloadPreferences(FakeSharedPreferences()),
        scope,
    )

    private fun dto(
        status: String,
        downloaded: Long,
        total: Long,
        token: String? = null,
        error: String? = null,
    ) = DownloadDto(
        id = "server-1",
        contentId = "content-1",
        title = "Movie",
        status = status,
        active = true,
        suspended = false,
        totalBytes = total,
        downloadedBytes = downloaded,
        error = error,
        createdMs = 1,
        fileToken = token,
        fileTokenExpiresAtMs = token?.let { 2 },
    )
}

private const val REMOVAL_HUB_ID = 4L

private data class RemovalFixture(
    val registry: HubRegistry,
    val store: RemovalHubStore,
    val vault: HubSessionVault,
)

private suspend fun removalRegistry(): RemovalFixture {
    val store = RemovalHubStore()
    val vault = HubSessionVault(
        FakeSharedPreferences(),
        object : TokenCipher {
            override fun encrypt(plain: ByteArray) = plain
            override fun decrypt(blob: ByteArray) = blob
        },
    ).apply { store(REMOVAL_HUB_ID, "session-token") }
    val registry = HubRegistry(
        store,
        HubApi(
            HttpTransport {
                HttpResponseSpec(204, emptyMap(), "")
            },
        ),
        vault,
    )
    return RemovalFixture(registry, store, vault)
}

private class RemovalHubStore : HubSourceStore {
    private var source: HubSource? = HubSource(
        id = REMOVAL_HUB_ID,
        name = "Home",
        baseUrl = "https://hub.example",
        addedMs = 1,
    )

    override fun observeAll(): Flow<List<HubSource>> = flowOf(source?.let(::listOf).orEmpty())
    override suspend fun getAll(): List<HubSource> = source?.let(::listOf).orEmpty()
    override suspend fun get(id: Long): HubSource? = source?.takeIf { it.id == id }
    override suspend fun upsert(source: HubSource): Long {
        this.source = source.copy(id = REMOVAL_HUB_ID)
        return REMOVAL_HUB_ID
    }

    override suspend fun delete(id: Long) {
        if (source?.id == id) source = null
    }

    override suspend fun updateIdentity(
        id: Long,
        userId: String?,
        username: String?,
        role: String?,
        seenMs: Long,
    ) = Unit

    override suspend fun clearIdentity(id: Long) = Unit
}

private class FakeHubDownloadRemote : HubDownloadRemote {
    var rows: List<DownloadDto> = emptyList()
    var afterEnqueue: List<DownloadDto> = emptyList()
    var error: Throwable? = null
    val deleted = mutableListOf<Pair<Long, String>>()
    val enqueued = mutableListOf<Pair<Long, String>>()
    val actions = mutableListOf<Triple<Long, String, String>>()
    var blockNextDownloads = false
    var blockNextAction = false
    var afterAction: List<DownloadDto>? = null
    val downloadsEntered = CompletableDeferred<Unit>()
    val releaseDownloads = CompletableDeferred<Unit>()
    val actionEntered = CompletableDeferred<Unit>()
    val releaseAction = CompletableDeferred<Unit>()

    override suspend fun downloads(hubSourceId: Long): HubDownloadSnapshot {
        error?.let { throw it }
        if (blockNextDownloads) {
            blockNextDownloads = false
            downloadsEntered.complete(Unit)
            releaseDownloads.await()
        }
        return HubDownloadSnapshot("https://hub.example", rows)
    }

    override suspend fun enqueue(hubSourceId: Long, contentId: String) {
        error?.let { throw it }
        enqueued += hubSourceId to contentId
        rows = afterEnqueue
    }

    override suspend fun action(hubSourceId: Long, serverDownloadId: String, action: String) {
        if (blockNextAction) {
            blockNextAction = false
            actionEntered.complete(Unit)
            releaseAction.await()
        }
        error?.let { throw it }
        actions += Triple(hubSourceId, serverDownloadId, action)
        afterAction?.let { rows = it }
    }

    override suspend fun delete(hubSourceId: Long, serverDownloadId: String) {
        error?.let { throw it }
        deleted += hubSourceId to serverDownloadId
    }
}

private class FakeDownloadScheduler : DownloadScheduler {
    val pulls = mutableListOf<Long>()
    val preparations = mutableListOf<Long>()
    val cancelled = mutableListOf<Long>()

    override fun enqueue(downloadId: Long) {
        pulls += downloadId
    }

    override fun enqueuePreparation(downloadId: Long) {
        preparations += downloadId
    }

    override fun cancel(downloadId: Long) {
        cancelled += downloadId
    }
}

private class FakeDownloadStore : DownloadStore {
    private val state = MutableStateFlow<List<Download>>(emptyList())
    private var nextId = 1L
    var blockFirstHubFind = false
    val firstHubFindEntered = CompletableDeferred<Unit>()
    val releaseFirstHubFind = CompletableDeferred<Unit>()
    private var hubFindCalls = 0

    fun single() = state.value.single()
    fun all() = state.value
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
    ): Download? {
        val found = state.value.firstOrNull {
            it.hubSourceId == hubSourceId && it.contentId == contentId && it.status in statuses
        }
        if (blockFirstHubFind && hubFindCalls++ == 0) {
            firstHubFindEntered.complete(Unit)
            releaseFirstHubFind.await()
        }
        return found
    }

    override suspend fun insert(download: Download): Long {
        val id = nextId++
        state.value = state.value + download.copy(id = id)
        return id
    }

    override suspend fun update(download: Download) {
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
        val row = get(id)?.takeIf { it.status in expectedStatuses } ?: return false
        update(row.copy(url = url, error = null))
        return true
    }

    override suspend fun delete(id: Long) {
        currentCoroutineContext().ensureActive()
        state.value = state.value.filterNot { it.id == id }
    }
}

private class FakeSharedPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any>()

    override fun getBoolean(key: String, defValue: Boolean) = values[key] as? Boolean ?: defValue
    override fun contains(key: String) = values.containsKey(key)
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        override fun putBoolean(key: String, value: Boolean) = apply { values[key] = value }
        override fun remove(key: String) = apply { values.remove(key) }
        override fun clear() = apply { values.clear() }
        override fun commit() = true
        override fun apply() = Unit
        override fun putString(key: String, value: String?) = apply {
            if (value == null) values.remove(key) else values[key] = value
        }
        override fun putStringSet(key: String, values: MutableSet<String>?) = throw UnsupportedOperationException()
        override fun putInt(key: String, value: Int) = throw UnsupportedOperationException()
        override fun putLong(key: String, value: Long) = throw UnsupportedOperationException()
        override fun putFloat(key: String, value: Float) = throw UnsupportedOperationException()
    }

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun getString(key: String, defValue: String?) = values[key] as? String ?: defValue
    override fun getStringSet(key: String, defValues: MutableSet<String>?) = throw UnsupportedOperationException()
    override fun getInt(key: String, defValue: Int) = throw UnsupportedOperationException()
    override fun getLong(key: String, defValue: Long) = throw UnsupportedOperationException()
    override fun getFloat(key: String, defValue: Float) = throw UnsupportedOperationException()
    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit
}
