package com.buco7854.opentv.download

import android.content.Context
import android.os.SystemClock
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.buco7854.opentv.core.model.Playlist
import com.buco7854.opentv.core.storage.DownloadStore
import com.buco7854.opentv.core.storage.PlaylistStore
import com.buco7854.opentv.core.xtream.AccountInfo
import com.buco7854.opentv.data.prefs.PlayerSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient

class DownloadWorkerDependencies(
    val downloads: DownloadStore,
    val playlists: PlaylistStore,
    val settings: Flow<PlayerSettings>,
    val accountInfo: suspend (Playlist) -> AccountInfo?,
    val httpClient: OkHttpClient,
    val userAgent: () -> String,
    val activePlaybackHost: StateFlow<String?>,
    val hubDownloads: HubDownloadWorkerAccess,
    val hubPollIntervalMs: Long = 2_000,
    val hubPollDelay: suspend (Long) -> Unit = { delay(it) },
    val hubStallTimeoutMs: Long = 10 * 60_000,
    val nowMs: () -> Long = SystemClock::elapsedRealtime,
    val executionLocks: DownloadExecutionLocks = DownloadExecutionLocks(),
    val withDownloadSlot: suspend (Int, suspend () -> Unit) -> Unit = { limit, block ->
        DownloadGate.withSlot(limit, block)
    },
) {
    suspend fun <T> withDownloadLock(downloadId: Long, block: suspend () -> T): T =
        executionLocks.withDownloadLock(downloadId, block)
}

/**
 * Shared between WorkManager and user actions that mutate the same local file.
 * Cancelling WorkManager is asynchronous, so deleting a file must also wait for the
 * worker's sink to close rather than assuming [androidx.work.WorkManager.cancelWorkById]
 * has already stopped it.
 */
class DownloadExecutionLocks {
    private val locks = KeyedMutexPool<Long>()

    suspend fun <T> withDownloadLock(downloadId: Long, block: suspend () -> T): T =
        locks.withKeyLock(downloadId, block)
}

/**
 * Creates workers with dependencies from the application composition root.
 *
 * The provider is lazy because WorkManager may ask the Application for its
 * configuration before [android.app.Application.onCreate] has built the graph.
 */
class DownloadWorkerFactory(
    private val dependencies: () -> DownloadWorkerDependencies,
) : WorkerFactory() {
    private val sharedDependencies by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        dependencies()
    }

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? =
        when (workerClassName) {
            DownloadWorker::class.java.name ->
                DownloadWorker(appContext, workerParameters, sharedDependencies)
            HubDownloadPrepareWorker::class.java.name ->
                HubDownloadPrepareWorker(appContext, workerParameters, sharedDependencies.hubDownloads)
            else -> null
        }
}
