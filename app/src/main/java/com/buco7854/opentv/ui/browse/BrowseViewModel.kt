package com.buco7854.opentv.ui.browse

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.buco7854.opentv.AppGraph
import com.buco7854.opentv.OpenTvApp
import com.buco7854.opentv.R
import com.buco7854.opentv.core.log.rethrowCancellation
import com.buco7854.opentv.core.model.ChannelKind
import com.buco7854.opentv.core.model.Download
import com.buco7854.opentv.core.model.DownloadStatus
import com.buco7854.opentv.core.model.Playlist
import com.buco7854.opentv.core.xtream.AccountInfo
import com.buco7854.opentv.diag.ErrorLog
import com.buco7854.opentv.download.downloadIdentityKey
import com.buco7854.opentv.source.CatalogGateway
import com.buco7854.opentv.source.CatalogGroup
import com.buco7854.opentv.source.CatalogItem
import com.buco7854.opentv.source.CatalogLoadError
import com.buco7854.opentv.source.CatalogProgramme
import com.buco7854.opentv.source.CatalogResult
import com.buco7854.opentv.source.ContentRef
import com.buco7854.opentv.source.DEFAULT_CATALOG_PAGE_SIZE
import com.buco7854.opentv.source.ServerPageSnapshot
import com.buco7854.opentv.source.ServerPagedState
import com.buco7854.opentv.source.SourceId
import com.buco7854.opentv.source.SourceTraits
import com.buco7854.opentv.source.toCatalogItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class BrowseCatalogState(
    val groups: List<CatalogGroup> = emptyList(),
    val items: List<CatalogItem> = emptyList(),
    val total: Int = 0,
    val loading: Boolean = true,
    val error: CatalogLoadError? = null,
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class BrowseViewModel private constructor(
    private val application: Application?,
    val sourceId: SourceId,
    private val gateway: CatalogGateway,
    private val graph: AppGraph?,
) : ViewModel() {
    constructor(app: Application, sourceId: SourceId) : this(
        application = app,
        sourceId = sourceId,
        gateway = OpenTvApp.graph.catalogFor(sourceId),
        graph = OpenTvApp.graph,
    )

    internal constructor(sourceId: SourceId, gateway: CatalogGateway) : this(
        application = null,
        sourceId = sourceId,
        gateway = gateway,
        graph = null,
    )

    private val mutableTraits = MutableStateFlow<SourceTraits?>(null)
    val traits: StateFlow<SourceTraits?> = mutableTraits
    val tab = MutableStateFlow(ChannelKind.LIVE)
    val group = MutableStateFlow<String?>(null)
    val filter = MutableStateFlow("")

    private val mutableCatalog = MutableStateFlow(BrowseCatalogState())
    val catalog: StateFlow<BrowseCatalogState> = mutableCatalog

    private val mutablePlaylist = MutableStateFlow<Playlist?>(null)
    val playlist: StateFlow<Playlist?> = mutablePlaylist

    private val mutableFavoriteKeys = MutableStateFlow<Set<String>>(emptySet())
    val favoriteKeys: StateFlow<Set<String>> = mutableFavoriteKeys

    private val mutableNowAiring = MutableStateFlow<Map<String, CatalogProgramme>>(emptyMap())
    val nowAiring: StateFlow<Map<String, CatalogProgramme>> = mutableNowAiring

    private val mutableGuideIds = MutableStateFlow<Set<String>>(emptySet())
    val guideIds: StateFlow<Set<String>> = mutableGuideIds

    private val mutableAccount = MutableStateFlow<AccountInfo?>(null)
    val account: StateFlow<AccountInfo?> = mutableAccount

    private val mutableMessage = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = mutableMessage

    private val mutableLiveCount = MutableStateFlow(0)
    val liveCount: StateFlow<Int> = mutableLiveCount
    private val mutableMovieCount = MutableStateFlow(0)
    val movieCount: StateFlow<Int> = mutableMovieCount
    private val mutableSeriesCount = MutableStateFlow(0)
    val seriesCount: StateFlow<Int> = mutableSeriesCount

    val downloadsByUrl: StateFlow<Map<String, Download>> =
        graph?.downloads?.downloads
            ?.map { list ->
                list.filter {
                    it.status != DownloadStatus.CANCELLED && it.status != DownloadStatus.FAILED
                }.associateBy { it.downloadIdentityKey() }
            }
            ?.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
            ?: MutableStateFlow(emptyMap())

    val gridView: StateFlow<Boolean> =
        graph?.playerPrefs?.settings
            ?.map { it.gridBrowse }
            ?.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
            ?: MutableStateFlow(true)

    private var seeded = false
    private var pager: ServerPagedState<CatalogItem>? = null
    private var pagerCollection: Job? = null
    private var listingGeneration = 0L
    private var groupsGeneration = 0L

    init {
        viewModelScope.launch {
            val resolvedTraits = try {
                gateway.traits()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                fail(CatalogLoadError.Failed(error))
                return@launch
            }
            mutableTraits.value = resolvedTraits
            val localSource = sourceId as? SourceId.LocalPlaylist
            if (graph != null && localSource != null) {
                observeLocal(localSource.playlistId, resolvedTraits)
                refreshLocal(localSource.playlistId)
            } else {
                observeGateway()
            }
            observeAncillary()
        }
    }

    fun seedFromRoute(initialTab: Int?, initialGroup: String?) {
        if (seeded) return
        seeded = true
        if (initialTab != null) tab.value = initialTab
        if (initialGroup != null) group.value = initialGroup
    }

    fun consumeMessage() {
        mutableMessage.value = null
    }

    fun retry() {
        mutableCatalog.value = mutableCatalog.value.copy(error = null)
        loadGroups(tab.value)
        group.value?.let { loadGatewayListing(tab.value, it) }
        reloadFavorites()
        reloadNowAiring()
        reloadGuideIds()
    }

    fun loadMore() {
        pager?.loadMore()
    }

    fun toggleGridView() {
        val currentGraph = graph ?: return
        viewModelScope.launch {
            val current = currentGraph.playerPrefs.settings.first()
            currentGraph.playerPrefs.save(current.copy(gridBrowse = !current.gridBrowse))
        }
    }

    fun setGroupKind(groupTitle: String, kind: Int?) {
        val local = sourceId as? SourceId.LocalPlaylist ?: return
        val currentGraph = graph ?: return
        viewModelScope.launch {
            currentGraph.playlists.setGroupOverride(local.playlistId, groupTitle, kind)
            mutableMessage.value = if (kind == null) {
                str(R.string.browse_category_auto_message)
            } else {
                str(R.string.browse_category_updated_message)
            }
        }
    }

    fun toggleFavorite(item: CatalogItem) {
        viewModelScope.launch {
            when (val result = gateway.toggleFavorite(item.ref)) {
                is CatalogResult.Success -> {
                    val key = favoriteKey(item)
                    mutableFavoriteKeys.value = if (result.value) {
                        mutableFavoriteKeys.value + key
                    } else {
                        mutableFavoriteKeys.value - key
                    }
                }
                CatalogResult.SignedOut -> fail(CatalogLoadError.SignedOut)
                CatalogResult.Unreachable -> fail(CatalogLoadError.Unreachable)
                is CatalogResult.Failed -> fail(CatalogLoadError.Failed(result.cause))
            }
        }
    }

    fun download(item: CatalogItem) {
        val currentGraph = graph ?: return
        viewModelScope.launch {
            val blocked = when (val source = sourceId) {
                is SourceId.LocalPlaylist -> {
                    val ref = item.ref as? ContentRef.LocalUrl ?: return@launch
                    val channel = ref.channelId.takeIf { it != 0L }
                        ?.let { currentGraph.storage.channels.get(it) }
                        ?.takeIf {
                            it.playlistId == source.playlistId && it.url == ref.url
                        }
                        ?: currentGraph.storage.channels.getByUrl(source.playlistId, ref.url)
                        ?: return@launch
                    currentGraph.downloads.enqueue(channel)
                }
                is SourceId.Hub -> {
                    val ref = item.ref as? ContentRef.HubContent ?: return@launch
                    currentGraph.downloads.enqueueHub(source.hubId, ref.contentId, item.title)
                }
                is SourceId.HubConnection -> return@launch
            }
            mutableMessage.value = blocked ?: str(R.string.downloads_started, item.title)
        }
    }

    fun reloadNowAiring() {
        viewModelScope.launch {
            when (val result = gateway.nowAiring()) {
                is CatalogResult.Success -> mutableNowAiring.value = result.value
                CatalogResult.SignedOut -> if (sourceId is SourceId.Hub) {
                    fail(CatalogLoadError.SignedOut)
                }
                CatalogResult.Unreachable -> if (sourceId is SourceId.Hub) {
                    fail(CatalogLoadError.Unreachable)
                }
                is CatalogResult.Failed -> if (sourceId is SourceId.Hub) {
                    fail(CatalogLoadError.Failed(result.cause))
                }
            }
        }
    }

    fun refreshAccount(force: Boolean) {
        val local = sourceId as? SourceId.LocalPlaylist ?: return
        val currentGraph = graph ?: return
        viewModelScope.launch {
            val source = currentGraph.storage.playlists.get(local.playlistId) ?: return@launch
            currentGraph.account.accountInfo(source, force)?.let { mutableAccount.value = it }
        }
    }

    private fun observeLocal(playlistId: Long, traits: SourceTraits) {
        val currentGraph = graph ?: return
        viewModelScope.launch {
            currentGraph.storage.playlists.observe(playlistId).collect(mutablePlaylist::emit)
        }
        viewModelScope.launch {
            currentGraph.favorites.observeAll(playlistId).collect { favorites ->
                mutableFavoriteKeys.value = favorites.mapTo(mutableSetOf()) { it.key }
            }
        }
        viewModelScope.launch {
            currentGraph.epg.observeGuideIds(playlistId).collect(mutableGuideIds::emit)
        }
        viewModelScope.launch {
            tab.flatMapLatest { kind ->
                if (kind == ChannelKind.SERIES && traits.hasXtreamSeries) {
                    currentGraph.storage.xtreamSeries.observeCategories(playlistId)
                } else {
                    currentGraph.storage.channels.observeGroups(playlistId, kind)
                }
            }.collect { groups ->
                mutableCatalog.value = mutableCatalog.value.copy(
                    groups = groups.map { CatalogGroup(it.groupTitle, it.count) },
                    loading = false,
                    error = null,
                )
            }
        }
        viewModelScope.launch {
            combine(tab, group) { kind, selected -> kind to selected }
                .flatMapLatest { (kind, selected) ->
                    when {
                        selected == null -> flowOf(emptyList())
                        kind == ChannelKind.SERIES && traits.hasXtreamSeries ->
                            currentGraph.storage.xtreamSeries.observeInCategory(playlistId, selected)
                                .map { rows -> rows.map { it.toCatalogItem().copy(group = selected) } }
                        kind == ChannelKind.SERIES ->
                            currentGraph.storage.channels.observeSeriesInGroup(playlistId, selected)
                                .map { rows -> rows.map { it.toCatalogItem() } }
                        else -> currentGraph.storage.channels.observeInGroup(playlistId, kind, selected)
                            .map { rows -> rows.map { it.toCatalogItem() } }
                    }
                }
                .collect { items ->
                    mutableCatalog.value = mutableCatalog.value.copy(
                        items = items,
                        total = items.size,
                        loading = false,
                        error = null,
                    )
                }
        }
        viewModelScope.launch {
            currentGraph.storage.channels.observeCount(playlistId, ChannelKind.LIVE)
                .collect(mutableLiveCount::emit)
        }
        viewModelScope.launch {
            currentGraph.storage.channels.observeCount(playlistId, ChannelKind.MOVIE)
                .collect(mutableMovieCount::emit)
        }
        viewModelScope.launch {
            if (traits.hasXtreamSeries) {
                currentGraph.storage.xtreamSeries.observeCount(playlistId)
            } else {
                currentGraph.storage.channels.observeCount(playlistId, ChannelKind.SERIES)
            }.collect(mutableSeriesCount::emit)
        }
    }

    private fun observeGateway() {
        viewModelScope.launch {
            tab.collectLatest { kind ->
                loadGroups(kind)
            }
        }
        viewModelScope.launch {
            combine(tab, group) { kind, selected -> kind to selected }
                .collectLatest { (kind, selected) ->
                    if (selected == null) {
                        clearListing()
                    } else {
                        loadGatewayListing(kind, selected)
                    }
                }
        }
        viewModelScope.launch {
            filter.debounce(250).drop(1).collectLatest {
                group.value?.let { selected -> loadGatewayListing(tab.value, selected) }
            }
        }
    }

    private fun observeAncillary() {
        reloadFavorites()
        reloadNowAiring()
        reloadGuideIds()
    }

    private fun loadGroups(kind: Int) {
        val requestGeneration = ++groupsGeneration
        viewModelScope.launch {
            mutableCatalog.value = mutableCatalog.value.copy(loading = true, error = null)
            when (val result = safeCall { gateway.groups(kind) }) {
                is CatalogResult.Success -> {
                    if (requestGeneration != groupsGeneration) return@launch
                    setCount(kind, result.value.sumOf(CatalogGroup::count))
                    val listingSelected = group.value != null
                    mutableCatalog.value = mutableCatalog.value.copy(
                        groups = result.value,
                        loading = if (listingSelected) mutableCatalog.value.loading else false,
                        error = if (listingSelected) mutableCatalog.value.error else null,
                    )
                }
                CatalogResult.SignedOut -> if (requestGeneration == groupsGeneration) {
                    fail(CatalogLoadError.SignedOut)
                }
                CatalogResult.Unreachable -> if (requestGeneration == groupsGeneration) {
                    fail(CatalogLoadError.Unreachable)
                }
                is CatalogResult.Failed -> if (requestGeneration == groupsGeneration) {
                    fail(CatalogLoadError.Failed(result.cause))
                }
            }
        }
    }

    private fun loadGatewayListing(kind: Int, selected: String) {
        val traits = mutableTraits.value ?: return
        listingGeneration++
        pager?.cancel()
        pagerCollection?.cancel()
        val requestGeneration = listingGeneration
        val next = ServerPagedState(viewModelScope, keyOf = { it.ref }) { offset, limit ->
            when {
                kind == ChannelKind.SERIES && traits.hasXtreamSeries ->
                    gateway.xtreamSeries(selected, offset, limit, filter.value.trim())
                kind == ChannelKind.SERIES ->
                    gateway.seriesGroups(selected, offset, limit, filter.value.trim())
                else -> gateway.channels(kind, selected, offset, limit, filter.value.trim())
            }
        }
        pager = next
        pagerCollection = viewModelScope.launch {
            next.state.collect { snapshot ->
                if (requestGeneration != listingGeneration) return@collect
                mutableCatalog.value = mutableCatalog.value.copy(
                    items = snapshot.items,
                    total = snapshot.total,
                    loading = snapshot.loading,
                    error = snapshot.error,
                )
            }
        }
    }

    private fun clearListing() {
        listingGeneration++
        pager?.cancel()
        pager = null
        pagerCollection?.cancel()
        pagerCollection = null
        mutableCatalog.value = mutableCatalog.value.copy(
            items = emptyList(),
            total = 0,
        )
    }

    private fun reloadFavorites() {
        if (graph != null && sourceId is SourceId.LocalPlaylist) return
        viewModelScope.launch {
            val items = mutableListOf<CatalogItem>()
            while (true) {
                when (val result = safeCall {
                    gateway.favorites(items.size, DEFAULT_CATALOG_PAGE_SIZE)
                }) {
                    is CatalogResult.Success -> {
                        items += result.value.items
                        if (items.size >= result.value.total || result.value.items.isEmpty()) {
                            mutableFavoriteKeys.value =
                                items.mapTo(mutableSetOf(), ::favoriteKey)
                            return@launch
                        }
                    }
                    CatalogResult.SignedOut -> {
                        fail(CatalogLoadError.SignedOut)
                        return@launch
                    }
                    CatalogResult.Unreachable -> {
                        fail(CatalogLoadError.Unreachable)
                        return@launch
                    }
                    is CatalogResult.Failed -> {
                        fail(CatalogLoadError.Failed(result.cause))
                        return@launch
                    }
                }
            }
        }
    }

    private fun reloadGuideIds() {
        if (graph != null && sourceId is SourceId.LocalPlaylist) return
        viewModelScope.launch {
            when (val result = safeCall(gateway::guideIds)) {
                is CatalogResult.Success -> mutableGuideIds.value = result.value
                CatalogResult.SignedOut -> fail(CatalogLoadError.SignedOut)
                CatalogResult.Unreachable -> fail(CatalogLoadError.Unreachable)
                is CatalogResult.Failed -> fail(CatalogLoadError.Failed(result.cause))
            }
        }
    }

    private fun refreshLocal(playlistId: Long) {
        val currentGraph = graph ?: return
        viewModelScope.launch {
            try {
                currentGraph.playlists.refresh(playlistId)
            } catch (error: Exception) {
                error.rethrowCancellation()
                ErrorLog.log("Playlist refresh", error)
                mutableMessage.value =
                    str(R.string.browse_playlist_refresh_failed, ErrorLog.describe(error))
            }
            try {
                currentGraph.epg.refresh(playlistId)
            } catch (error: Exception) {
                error.rethrowCancellation()
                ErrorLog.log("EPG refresh", error)
                mutableMessage.value =
                    str(R.string.browse_epg_refresh_failed, ErrorLog.describe(error))
            }
            reloadNowAiring()
            refreshAccount(force = false)
        }
    }

    private fun fail(error: CatalogLoadError) {
        mutableCatalog.value = mutableCatalog.value.copy(loading = false, error = error)
    }

    private fun setCount(kind: Int, count: Int) {
        when (kind) {
            ChannelKind.MOVIE -> mutableMovieCount.value = count
            ChannelKind.SERIES -> mutableSeriesCount.value = count
            else -> mutableLiveCount.value = count
        }
    }

    private fun favoriteKey(item: CatalogItem): String = when (val ref = item.ref) {
        is ContentRef.HubContent -> ref.contentId
        is ContentRef.LocalUrl -> when {
            item.kind == ChannelKind.SERIES -> item.seriesId?.let { "x:$it" }
                ?: item.seriesKey
                ?: ref.url
            else -> ref.url
        }
    }

    private fun str(resId: Int, vararg args: Any): String =
        application?.getString(resId, *args).orEmpty()

    private suspend fun <T> safeCall(
        call: suspend () -> CatalogResult<T>,
    ): CatalogResult<T> = try {
        call()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        CatalogResult.Failed(error)
    }
}
