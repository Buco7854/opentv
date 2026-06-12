package com.buco7854.opentv

import android.app.Application
import com.buco7854.opentv.data.db.AppDatabase
import com.buco7854.opentv.data.net.Http
import com.buco7854.opentv.data.prefs.PlayerPrefs
import com.buco7854.opentv.data.repo.AccountRepository
import com.buco7854.opentv.data.repo.EpgRepository
import com.buco7854.opentv.data.repo.MetadataRepository
import com.buco7854.opentv.data.repo.PlaylistRepository
import com.buco7854.opentv.data.repo.XtreamRepository
import com.buco7854.opentv.diag.ErrorLog
import com.buco7854.opentv.download.DownloadRepository
import com.buco7854.opentv.download.DownloadWorker

/** Tiny hand-rolled dependency graph; no DI framework needed at this size. */
class AppGraph(app: Application) {
    val db: AppDatabase = AppDatabase.build(app)
    val playlists = PlaylistRepository(db)
    val epg = EpgRepository(db)
    val account = AccountRepository()
    val playerPrefs = PlayerPrefs(app)
    val downloads = DownloadRepository(app, db, playerPrefs)
    val metadata = MetadataRepository(db)
    val xtream = XtreamRepository(db)
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
        DownloadWorker.ensureNotificationChannel(this)
    }
}
