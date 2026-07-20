package com.buco7854.opentv

import android.app.Application
import com.buco7854.opentv.core.log.CoreLog
import com.buco7854.opentv.core.repo.AccountRepository
import com.buco7854.opentv.core.repo.EpgRepository
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
import com.buco7854.opentv.download.DownloadWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Composition root: platform adapters wired into the shared :core repositories. */
class AppGraph(app: Application) {
    val storage: Storage = createRoomStorage(app)
    private val coreLog = CoreLog { context, error -> ErrorLog.log(context, error) }
    val xtreamApi = XtreamApi(Http.fetcher)
    val playlists = PlaylistRepository(storage, xtreamApi, Http.conditionalFetcher, coreLog)
    val epg = EpgRepository(storage, Http.conditionalFetcher)
    val account = AccountRepository(xtreamApi, coreLog)
    val xtream = XtreamRepository(storage, xtreamApi, epg, account, coreLog)
    val playerPrefs = PlayerPrefs(app)
    val downloads = DownloadRepository(app, storage, playerPrefs)
    val metadata = MetadataRepository(storage.metadata, Http.fetcher, coreLog)
    val resume = ResumeRepository(storage.resume)
}

class OpenTvApp : Application() {

    companion object {
        lateinit var graph: AppGraph
            private set
    }

    override fun onCreate() {
        super.onCreate()
        ErrorLog.install(this)
        Http.init(this)
        graph = AppGraph(this)
        graph.resume.pruneOld()
        DownloadWorker.ensureNotificationChannel(this)
        // Keep the shared HTTP User-Agent in sync with the saved preference; "" = VLC default.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            graph.playerPrefs.settings
                .map { it.userAgent }
                .distinctUntilChanged()
                .collect { ua ->
                    Http.userAgent = ua.trim().ifBlank { Http.DEFAULT_USER_AGENT }
                }
        }
    }
}
