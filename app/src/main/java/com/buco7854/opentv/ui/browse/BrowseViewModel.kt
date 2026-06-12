package com.buco7854.opentv.ui.browse

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.buco7854.opentv.OpenTvApp
import com.buco7854.opentv.data.db.ChannelEntity
import com.buco7854.opentv.data.db.ChannelKind
import com.buco7854.opentv.data.db.GroupCount
import com.buco7854.opentv.data.db.ProgrammeEntity
import com.buco7854.opentv.data.db.SeriesGroup
import com.buco7854.opentv.data.xtream.AccountInfo
import com.buco7854.opentv.diag.ErrorLog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class BrowseViewModel(app: Application, val playlistId: Long) : AndroidViewModel(app) {

    private val graph = OpenTvApp.graph
    private val channelDao = graph.db.channelDao()

    val playlist = graph.db.playlistDao().observe(playlistId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val tab = MutableStateFlow(ChannelKind.LIVE)
    val group = MutableStateFlow<String?>(null)
    val series = MutableStateFlow<String?>(null)

    val groups: StateFlow<List<GroupCount>> = tab
        .flatMapLatest { channelDao.observeGroups(playlistId, it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val channels: StateFlow<List<ChannelEntity>> = combine(tab, group) { t, g -> t to g }
        .flatMapLatest { (t, g) ->
            if (g == null || t == ChannelKind.SERIES) flowOf(emptyList())
            else channelDao.observeInGroup(playlistId, t, g)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val seriesGroups: StateFlow<List<SeriesGroup>> = combine(tab, group) { t, g -> t to g }
        .flatMapLatest { (t, g) ->
            if (g == null || t != ChannelKind.SERIES) flowOf(emptyList())
            else channelDao.observeSeriesInGroup(playlistId, g)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val episodes: StateFlow<List<ChannelEntity>> = series
        .flatMapLatest { s ->
            if (s == null) flowOf(emptyList()) else channelDao.observeEpisodes(playlistId, s)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val liveCount = channelDao.observeCount(playlistId, ChannelKind.LIVE)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val movieCount = channelDao.observeCount(playlistId, ChannelKind.MOVIE)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val seriesCount = channelDao.observeCount(playlistId, ChannelKind.SERIES)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** tvgId -> programme currently on air (local DB only, no network). */
    private val _nowAiring = MutableStateFlow<Map<String, ProgrammeEntity>>(emptyMap())
    val nowAiring: StateFlow<Map<String, ProgrammeEntity>> = _nowAiring

    private val _account = MutableStateFlow<AccountInfo?>(null)
    val account: StateFlow<AccountInfo?> = _account

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message
    fun consumeMessage() { _message.value = null }

    init {
        viewModelScope.launch {
            // Both calls are throttled inside the repositories, so opening this
            // screen repeatedly does NOT spam the provider.
            try {
                graph.playlists.refresh(playlistId)
            } catch (e: Exception) {
                ErrorLog.log("Playlist refresh", e)
                _message.value = "Playlist refresh failed: ${ErrorLog.describe(e)}"
            }
            try {
                graph.epg.refresh(playlistId)
            } catch (e: Exception) {
                ErrorLog.log("EPG refresh", e)
                _message.value = "EPG refresh failed: ${ErrorLog.describe(e)}"
            }
            reloadNowAiring()
            refreshAccount(force = false)
        }
    }

    fun selectTab(kind: Int) {
        tab.value = kind
        group.value = null
        series.value = null
    }

    fun reloadNowAiring() {
        viewModelScope.launch {
            _nowAiring.value = graph.epg.nowAiring(playlistId)
        }
    }

    fun refreshAccount(force: Boolean) {
        viewModelScope.launch {
            val p = graph.db.playlistDao().get(playlistId) ?: return@launch
            graph.account.accountInfo(p, force)?.let { _account.value = it }
        }
    }

    suspend fun upcomingProgrammes(tvgId: String): List<ProgrammeEntity> =
        graph.epg.upcoming(playlistId, tvgId)

    fun download(channel: ChannelEntity) {
        viewModelScope.launch {
            val blocked = graph.downloads.enqueue(channel)
            _message.value = blocked ?: "Download started: ${channel.name}"
        }
    }
}
