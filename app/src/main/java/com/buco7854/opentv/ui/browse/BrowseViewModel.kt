package com.buco7854.opentv.ui.browse

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.buco7854.opentv.OpenTvApp
import com.buco7854.opentv.data.db.ChannelEntity
import com.buco7854.opentv.data.db.ChannelKind
import com.buco7854.opentv.data.db.DownloadEntity
import com.buco7854.opentv.data.db.DownloadStatus
import com.buco7854.opentv.data.db.FavoriteEntity
import com.buco7854.opentv.data.db.GroupCount
import com.buco7854.opentv.data.db.PlaylistEntity
import com.buco7854.opentv.data.db.ProgrammeEntity
import com.buco7854.opentv.data.db.SeriesGroup
import com.buco7854.opentv.data.db.XtreamSeriesEntity
import com.buco7854.opentv.data.xtream.AccountInfo
import com.buco7854.opentv.data.repo.xtreamFavoriteKey
import com.buco7854.opentv.diag.ErrorLog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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

    private fun PlaylistEntity?.isXtreamNative() = this != null && url == null && xtreamBase != null

    companion object {
        fun xtreamFavKey(seriesId: Long) = xtreamFavoriteKey(seriesId)
    }

    private val favorites = graph.db.favoriteDao().observeAll(playlistId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Stable keys of everything favorited, for heart-icon state. */
    val favoriteKeys: StateFlow<Set<String>> = favorites
        .map { list -> list.map { it.key }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    /** User correction for a misclassified M3U category; null = back to automatic. */
    fun setGroupKind(groupTitle: String, kind: Int?) {
        viewModelScope.launch {
            graph.playlists.setGroupOverride(playlistId, groupTitle, kind)
            _message.value = if (kind == null) {
                "Category back to automatic, applies at next refresh"
            } else {
                "Category updated. Series grouping refines at next refresh"
            }
        }
    }

    fun toggleFavorite(key: String, kind: Int) {
        viewModelScope.launch {
            val dao = graph.db.favoriteDao()
            if (dao.get(playlistId, key) != null) dao.remove(playlistId, key)
            else dao.add(FavoriteEntity(playlistId = playlistId, key = key, kind = kind))
        }
    }

    /** True when this playlist is API-driven (added via Xtream login). */
    val isXtreamNative: StateFlow<Boolean> = playlist
        .map { it.isXtreamNative() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val groups: StateFlow<List<GroupCount>> = combine(tab, playlist) { t, p -> t to p }
        .flatMapLatest { (t, p) ->
            // Native Xtream playlists keep series in their own catalog table.
            if (t == ChannelKind.SERIES && p.isXtreamNative()) {
                graph.db.xtreamSeriesDao().observeCategories(playlistId)
            } else {
                channelDao.observeGroups(playlistId, t)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val xtreamSeries: StateFlow<List<XtreamSeriesEntity>> =
        combine(tab, group, playlist) { t, g, p -> Triple(t, g, p) }
            .flatMapLatest { (t, g, p) ->
                if (t == ChannelKind.SERIES && g != null && p.isXtreamNative()) {
                    graph.db.xtreamSeriesDao().observeInCategory(playlistId, g)
                } else flowOf(emptyList())
            }
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

    /** url -> active download, for the live progress icon on movie rows. */
    val downloadsByUrl: StateFlow<Map<String, DownloadEntity>> = graph.downloads.downloads
        .map { list ->
            list.filter { it.status != DownloadStatus.CANCELLED && it.status != DownloadStatus.FAILED }
                .associateBy { it.url }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val liveCount = channelDao.observeCount(playlistId, ChannelKind.LIVE)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val movieCount = channelDao.observeCount(playlistId, ChannelKind.MOVIE)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val seriesCount = playlist
        .flatMapLatest { p ->
            if (p.isXtreamNative()) graph.db.xtreamSeriesDao().observeCount(playlistId)
            else channelDao.observeCount(playlistId, ChannelKind.SERIES)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** tvgId -> programme currently on air (local DB only, no network). */
    private val _nowAiring = MutableStateFlow<Map<String, ProgrammeEntity>>(emptyMap())
    val nowAiring: StateFlow<Map<String, ProgrammeEntity>> = _nowAiring

    private val _account = MutableStateFlow<AccountInfo?>(null)
    val account: StateFlow<AccountInfo?> = _account

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message
    fun consumeMessage() { _message.value = null }

    /** Poster-grid vs row-list browsing, persisted across sessions. */
    val gridView: StateFlow<Boolean> = graph.playerPrefs.settings
        .map { it.gridBrowse }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun toggleGridView() {
        viewModelScope.launch {
            val current = graph.playerPrefs.settings.first()
            graph.playerPrefs.save(current.copy(gridBrowse = !current.gridBrowse))
        }
    }

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

    /** Guide for one channel; reaches back into the catch-up archive when available. */
    suspend fun guideFor(channel: ChannelEntity): List<ProgrammeEntity> {
        val tvgId = channel.tvgId ?: return emptyList()
        val now = System.currentTimeMillis()
        val since = if (channel.catchupDays > 0) now - channel.catchupDays * 86_400_000L else now
        return graph.epg.guide(playlistId, tvgId, since)
    }

    suspend fun catchupUrl(channel: ChannelEntity, programme: ProgrammeEntity): String? =
        graph.xtream.catchupUrlFor(channel, programme.startMs, programme.endMs)

    fun download(channel: ChannelEntity) {
        viewModelScope.launch {
            val blocked = graph.downloads.enqueue(channel)
            _message.value = blocked ?: "Download started: ${channel.name}"
        }
    }
}
