package com.buco7854.opentv.download

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.buco7854.opentv.core.model.Playlist
import com.buco7854.opentv.core.storage.DownloadStore
import com.buco7854.opentv.core.storage.PlaylistStore
import com.buco7854.opentv.core.xtream.AccountInfo
import com.buco7854.opentv.data.prefs.PlayerSettings
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
)

/**
 * Creates workers with dependencies from the application composition root.
 *
 * The provider is lazy because WorkManager may ask the Application for its
 * configuration before [android.app.Application.onCreate] has built the graph.
 */
class DownloadWorkerFactory(
    private val dependencies: () -> DownloadWorkerDependencies,
) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? =
        if (workerClassName == DownloadWorker::class.java.name) {
            DownloadWorker(appContext, workerParameters, dependencies())
        } else {
            null
        }
}
