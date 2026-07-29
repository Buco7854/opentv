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
import com.buco7854.opentv.data.net.OkHttpTransport
import com.buco7854.opentv.data.prefs.PlayerPrefs
import com.buco7854.opentv.diag.ErrorLog
import com.buco7854.opentv.download.DownloadRepository
import com.buco7854.opentv.download.DownloadWorkerDependencies
import com.buco7854.opentv.download.DownloadWorkerFactory
import com.buco7854.opentv.download.DownloadWorker
import com.buco7854.opentv.download.HubDownloadCoordinator
import com.buco7854.opentv.download.HubDownloadPreferences
import com.buco7854.opentv.download.WorkManagerDownloadScheduler
import com.buco7854.opentv.hub.HubAccountRepository
import com.buco7854.opentv.hub.HubApi
import com.buco7854.opentv.hub.HubRegistry
import com.buco7854.opentv.hub.HubSessionVault
import com.buco7854.opentv.playback.PlaybackMonitor
import com.buco7854.opentv.source.AggregatedFavorites
import com.buco7854.opentv.source.CatalogGateway
import com.buco7854.opentv.source.HubCatalogGateway
import com.buco7854.opentv.source.LocalCatalogGateway
import com.buco7854.opentv.source.SourceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Composition root: platform adapters wired into the shared :core repositories. */
class AppGraph(app: Application) : AutoCloseable {
    private val applicationJob = SupervisorJob()
    val applicationScope = CoroutineScope(applicationJob + Dispatchers.IO)
    val storage: Storage = createRoomStorage(app)
    val hubVault: HubSessionVault by lazy {
        HubSessionVault(app.getSharedPreferences(HubSessionVault.PREFS_NAME, Application.MODE_PRIVATE))
    }
    private val hubTransport by lazy { OkHttpTransport() }
    val hubApi: HubApi by lazy { HubApi(hubTransport) }
    val hubs: HubRegistry by lazy { HubRegistry(storage.hubSources, hubApi, hubVault) }
    val hubAccounts: HubAccountRepository by lazy { HubAccountRepository(hubs) }
    private val coreLog = CoreLog { context, error -> ErrorLog.log(context, error) }
    val xtreamApi = XtreamApi(Http.fetcher)
    val account = AccountRepository(xtreamApi, coreLog)
    val playlists = PlaylistRepository(storage, xtreamApi, Http.conditionalFetcher, coreLog, account)
    val epg = EpgRepository(storage, Http.conditionalFetcher)
    val xtream = XtreamRepository(storage, xtreamApi, epg, account, coreLog)
    val playerPrefs = PlayerPrefs(app)
    private val downloadScheduler = WorkManagerDownloadScheduler(app)
    val hubDownloadPreferences = HubDownloadPreferences(
        app.getSharedPreferences(HubDownloadPreferences.PREFS_NAME, Application.MODE_PRIVATE),
    )
    val hubDownloads = HubDownloadCoordinator(
        store = storage.downloads,
        hubs = hubs,
        scheduler = downloadScheduler,
        preferences = hubDownloadPreferences,
        scope = applicationScope,
    )
    val downloads = DownloadRepository(
        context = app,
        store = storage.downloads,
        prefs = playerPrefs,
        scheduler = downloadScheduler,
        hubDownloads = hubDownloads,
    )
    val favorites = FavoriteRepository(storage.favorites)
    val metadata = MetadataRepository(storage.metadata, Http.fetcher, coreLog)
    val resume = ResumeRepository(storage.resume, applicationScope)
    private val catalogGateways = LinkedHashMap<SourceId, CatalogGateway>(16, 0.75f, true)
    val aggregatedFavorites by lazy {
        AggregatedFavorites(applicationScope, storage, hubs, ::catalogFor)
    }

    fun catalogFor(source: SourceId): CatalogGateway = synchronized(catalogGateways) {
        catalogGateways.getOrPut(source) {
            when (source) {
                is SourceId.LocalPlaylist -> {
                    LocalCatalogGateway(
                        source = source,
                        storage = storage,
                        xtream = xtream,
                        favorites = favorites,
                        resume = resume,
                        epg = epg,
                    )
                }
                is SourceId.Hub -> HubCatalogGateway(source, hubs)
                is SourceId.HubConnection ->
                    error("A hub connection does not identify a catalog playlist")
            }
        }.also {
            if (catalogGateways.size > MAX_CACHED_CATALOGS) {
                val eldest = catalogGateways.entries.iterator().next()
                catalogGateways.remove(eldest.key)
            }
        }
    }

    internal val downloadWorkerDependencies by lazy {
        DownloadWorkerDependencies(
            downloads = storage.downloads,
            playlists = storage.playlists,
            settings = playerPrefs.settings,
            accountInfo = { playlist -> account.accountInfo(playlist) },
            httpClient = Http.ok,
            userAgent = { Http.userAgent },
            activePlaybackHost = PlaybackMonitor.activeHost,
            hubDownloads = hubDownloads,
        )
    }

    override fun close() {
        runBlocking {
            applicationJob.cancelAndJoin()
        }
        storage.close()
    }

    private companion object {
        const val MAX_CACHED_CATALOGS = 64
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
        graph.applicationScope.launch {
            graph.hubs.observeAll().collect {
                graph.hubs.withStoredHubIds { existingIds ->
                    graph.hubDownloadPreferences.pruneMissingHubs(existingIds)
                    graph.hubVault.pruneMissingHubs(existingIds)
                }
            }
        }
    }

    /** Called by emulators and tests; Android kills production processes without this callback. */
    override fun onTerminate() {
        graph.close()
        super.onTerminate()
    }
}
