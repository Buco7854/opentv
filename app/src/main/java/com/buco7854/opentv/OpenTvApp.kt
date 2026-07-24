package com.buco7854.opentv

import android.app.Application
import androidx.work.Configuration
import androidx.work.WorkManager
import com.buco7854.opentv.core.log.CoreLog
import com.buco7854.opentv.core.repo.AccountRepository
import com.buco7854.opentv.core.repo.EpgRepository
import com.buco7854.opentv.core.repo.FavoriteRepository
import com.buco7854.opentv.core.repo.MetadataRepository
import com.buco7854.opentv.core.repo.PlaylistRepository
import com.buco7854.opentv.core.repo.ResumeRepository
import com.buco7854.opentv.core.repo.XtreamRepository
import com.buco7854.opentv.core.storage.Storage
import com.buco7854.opentv.core.xtream.XtreamApi
import com.buco7854.opentv.data.createRoomStorage
import com.buco7854.opentv.data.net.Http
import com.buco7854.opentv.data.prefs.PlayerPrefs
import com.buco7854.opentv.diag.ErrorLog
import com.buco7854.opentv.download.DownloadRepository
import com.buco7854.opentv.download.DownloadWorkerDependencies
import com.buco7854.opentv.download.DownloadWorkerFactory
import com.buco7854.opentv.download.DownloadWorker
import com.buco7854.opentv.download.WorkManagerDownloadScheduler
import com.buco7854.opentv.playback.PlaybackMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Composition root: platform adapters wired into the shared :core repositories. */
class AppGraph(app: Application) : AutoCloseable {
    private val applicationJob = SupervisorJob()
    val applicationScope = CoroutineScope(applicationJob + Dispatchers.IO)
    val storage: Storage = createRoomStorage(app)
    private val coreLog = CoreLog { context, error -> ErrorLog.log(context, error) }
    val xtreamApi = XtreamApi(Http.fetcher)
    val playlists = PlaylistRepository(storage, xtreamApi, Http.conditionalFetcher, coreLog)
    val epg = EpgRepository(storage, Http.conditionalFetcher)
    val account = AccountRepository(xtreamApi, coreLog)
    val xtream = XtreamRepository(storage, xtreamApi, epg, account, coreLog)
    val playerPrefs = PlayerPrefs(app)
    val downloads = DownloadRepository(
        context = app,
        store = storage.downloads,
        prefs = playerPrefs,
        scheduler = WorkManagerDownloadScheduler(app),
    )
    val favorites = FavoriteRepository(storage.favorites)
    val metadata = MetadataRepository(storage.metadata, Http.fetcher, coreLog)
    val resume = ResumeRepository(storage.resume, applicationScope)

    internal val downloadWorkerDependencies
        get() = DownloadWorkerDependencies(
            downloads = storage.downloads,
            playlists = storage.playlists,
            settings = playerPrefs.settings,
            accountInfo = { playlist -> account.accountInfo(playlist) },
            httpClient = Http.ok,
            userAgent = { Http.userAgent },
            activePlaybackHost = PlaybackMonitor.activeHost,
        )

    override fun close() {
        applicationScope.cancel()
        storage.close()
    }
}

class OpenTvApp : Application(), Configuration.Provider {

    companion object {
        lateinit var graph: AppGraph
            private set
    }

    private val workerFactory = DownloadWorkerFactory { graph.downloadWorkerDependencies }

    override val workManagerConfiguration: Configuration by lazy {
        Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        ErrorLog.install(this)
        Http.init(this)
        graph = AppGraph(this)
        // The default Startup initializer is disabled in the manifest so restored
        // workers cannot run before the dependency graph exists.
        WorkManager.initialize(this, workManagerConfiguration)
        graph.resume.pruneOld()
        DownloadWorker.ensureNotificationChannel(this)
        // Keep the shared HTTP User-Agent in sync with the saved preference; "" = VLC default.
        graph.applicationScope.launch {
            graph.playerPrefs.settings
                .map { it.userAgent }
                .distinctUntilChanged()
                .collect { ua ->
                    Http.userAgent = ua.trim().ifBlank { Http.DEFAULT_USER_AGENT }
                }
        }
    }

    /** Called by emulators and tests; Android kills production processes without this callback. */
    override fun onTerminate() {
        graph.close()
        super.onTerminate()
    }
}
