package com.buco7854.opentv.ui.browse

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.buco7854.opentv.core.log.rethrowCancellation
import com.buco7854.opentv.OpenTvApp
import com.buco7854.opentv.R
import com.buco7854.opentv.core.model.Channel
import com.buco7854.opentv.core.model.ChannelKind
import com.buco7854.opentv.core.model.Download
import com.buco7854.opentv.core.model.DownloadStatus
import com.buco7854.opentv.core.model.Favorite
import com.buco7854.opentv.core.model.GroupCount
import com.buco7854.opentv.core.model.Playlist
import com.buco7854.opentv.core.model.Programme
import com.buco7854.opentv.core.model.SeriesGroup
import com.buco7854.opentv.core.model.XtreamSeries
import com.buco7854.opentv.core.xtream.AccountInfo
import com.buco7854.opentv.core.repo.xtreamFavoriteKey
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

    private fun str(resId: Int, vararg args: Any) =
        getApplication<Application>().getString(resId, *args)

    private val channelDao = graph.storage.channels

    val playlist = graph.storage.playlists.observe(playlistId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val tab = MutableStateFlow(ChannelKind.LIVE)
    val group = MutableStateFlow<String?>(null)

    // Seed from the route once: re-applying on re-entry would wipe the user's position.
    private var seeded = false
    fun seedFromRoute(initialTab: Int?, initialGroup: String?) {
        if (seeded) return
        seeded = true
        if (initialTab != null) tab.value = initialTab
        if (initialGroup != null) group.value = initialGroup
    }

    private fun Playlist?.isXtreamNative() = this != null && url == null && xtreamBase != null

    companion object {
        fun xtreamFavKey(seriesId: Long) = xtreamFavoriteKey(seriesId)
    }

    private val favorites = graph.storage.favorites.observeAll(playlistId)
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
                str(R.string.browse_category_auto_message)
            } else {
                str(R.string.browse_category_updated_message)
            }
        }
    }

    fun toggleFavorite(key: String, kind: Int) {
        viewModelScope.launch {
            val dao = graph.storage.favorites
            if (dao.get(playlistId, key) != null) dao.remove(playlistId, key)
            else dao.add(Favorite(playlistId = playlistId, key = key, kind = kind))
        }
    }

    /** True when this playlist is API-driven (added via Xtream login). */
    val isXtreamNative: StateFlow<Boolean> = playlist
        .map { it.isXtreamNative() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val groups: StateFlow<List<GroupCount>> = combine(tab, playlist) { t, p -> t to p }
        .flatMapLatest { (t, p) ->
            // Native Xtream keeps series in their own catalog table.
            if (t == ChannelKind.SERIES && p.isXtreamNative()) {
                graph.storage.xtreamSeries.observeCategories(playlistId)
            } else {
                channelDao.observeGroups(playlistId, t)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val xtreamSeries: StateFlow<List<XtreamSeries>> =
        combine(tab, group, playlist) { t, g, p -> Triple(t, g, p) }
            .flatMapLatest { (t, g, p) ->
                if (t == ChannelKind.SERIES && g != null && p.isXtreamNative()) {
                    graph.storage.xtreamSeries.observeInCategory(playlistId, g)
                } else flowOf(emptyList())
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val channels: StateFlow<List<Channel>> = combine(tab, group) { t, g -> t to g }
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
    val downloadsByUrl: StateFlow<Map<String, Download>> = graph.downloads.downloads
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
            if (p.isXtreamNative()) graph.storage.xtreamSeries.observeCount(playlistId)
            else channelDao.observeCount(playlistId, ChannelKind.SERIES)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** tvgId -> programme currently on air (local DB only, no network). */
    private val _nowAiring = MutableStateFlow<Map<String, Programme>>(emptyMap())
    val nowAiring: StateFlow<Map<String, Programme>> = _nowAiring

    val guideIds: StateFlow<Set<String>> = graph.epg.observeGuideIds(playlistId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

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
            // Both refreshes are throttled in the repositories, so reopening won't spam the provider.
            try {
                graph.playlists.refresh(playlistId)
            } catch (e: Exception) {
                e.rethrowCancellation()
                ErrorLog.log("Playlist refresh", e)
                _message.value = str(R.string.browse_playlist_refresh_failed, ErrorLog.describe(e))
            }
            try {
                graph.epg.refresh(playlistId)
            } catch (e: Exception) {
                e.rethrowCancellation()
                ErrorLog.log("EPG refresh", e)
                _message.value = str(R.string.browse_epg_refresh_failed, ErrorLog.describe(e))
            }
            reloadNowAiring()
            refreshAccount(force = false)
        }
    }

    fun reloadNowAiring() {
        viewModelScope.launch {
            _nowAiring.value = graph.epg.nowAiring(playlistId)
        }
    }

    fun refreshAccount(force: Boolean) {
        viewModelScope.launch {
            val p = graph.storage.playlists.get(playlistId) ?: return@launch
            graph.account.accountInfo(p, force)?.let { _account.value = it }
        }
    }

    fun download(channel: Channel) {
        viewModelScope.launch {
            val blocked = graph.downloads.enqueue(channel)
            _message.value = blocked ?: str(R.string.downloads_started, channel.name)
        }
    }
}
