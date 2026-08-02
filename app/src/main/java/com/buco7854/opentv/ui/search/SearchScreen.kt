package com.buco7854.opentv.ui.search

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.LaunchedEffect
import com.buco7854.opentv.OpenTvApp
import com.buco7854.opentv.AppGraph
import com.buco7854.opentv.R
import com.buco7854.opentv.core.model.ChannelKind
import com.buco7854.opentv.core.model.Download
import com.buco7854.opentv.core.model.DownloadStatus
import com.buco7854.opentv.download.downloadIdentityKey
import com.buco7854.opentv.diag.ErrorLog
import com.buco7854.opentv.source.CatalogGateway
import com.buco7854.opentv.source.CatalogGuideEntry
import com.buco7854.opentv.source.CatalogItem
import com.buco7854.opentv.source.CatalogLoadError
import com.buco7854.opentv.source.CatalogResult
import com.buco7854.opentv.source.CatalogSearchResult
import com.buco7854.opentv.source.ContentRef
import com.buco7854.opentv.source.DEFAULT_CATALOG_PAGE_SIZE
import com.buco7854.opentv.source.SourceId
import com.buco7854.opentv.source.encode
import com.buco7854.opentv.ui.components.ChannelLogo
import com.buco7854.opentv.ui.components.DownloadStateIcon
import com.buco7854.opentv.ui.components.FavoriteIcon
import com.buco7854.opentv.ui.components.GuideSheet
import com.buco7854.opentv.ui.components.MediaListRow
import com.buco7854.opentv.ui.components.mediaTags
import com.buco7854.opentv.ui.components.EmptyState
import com.buco7854.opentv.ui.components.Pill
import com.buco7854.opentv.ui.components.kindIcon
import com.buco7854.opentv.ui.components.focusHighlight
import com.buco7854.opentv.ui.components.kindLabel
import com.buco7854.opentv.ui.components.SourceLoadFailed
import com.buco7854.opentv.ui.components.SourceSignedOut
import com.buco7854.opentv.ui.components.SourceUnreachable
import com.buco7854.opentv.ui.components.sourceViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SearchUiState(
    val results: CatalogSearchResult = CatalogSearchResult(),
    val loading: Boolean = false,
    val error: CatalogLoadError? = null,
)

internal fun searchItemKey(item: CatalogItem): String =
    "${if (item.seriesId == null) "series" else "xtream"}:${item.ref.encode()}"

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class SearchViewModel private constructor(
    private val application: Application?,
    val sourceId: SourceId,
    private val gateway: CatalogGateway,
    private val graph: AppGraph?,
) : ViewModel() {
    constructor(app: Application, sourceId: SourceId) : this(
        app,
        sourceId,
        OpenTvApp.graph.catalogFor(sourceId),
        OpenTvApp.graph,
    )

    internal constructor(sourceId: SourceId, gateway: CatalogGateway) :
        this(null, sourceId, gateway, null)

    val query = MutableStateFlow("")

    private val mutableState = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = mutableState
    private var searchGeneration = 0L

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message
    fun consumeMessage() { _message.value = null }

    private fun str(resId: Int, vararg args: Any) =
        application?.getString(resId, *args).orEmpty()

    /** Same favourite affordance as the browse rows. */
    private val mutableFavoriteKeys = MutableStateFlow<Set<String>>(emptySet())
    val favoriteKeys: StateFlow<Set<String>> = mutableFavoriteKeys

    val downloadsByUrl: StateFlow<Map<String, Download>> =
        graph?.downloads?.downloads
            ?.map { list ->
                list.filter {
                    it.status != DownloadStatus.CANCELLED && it.status != DownloadStatus.FAILED
                }.associateBy { it.downloadIdentityKey() }
            }
            ?.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
            ?: MutableStateFlow(emptyMap())

    fun retry() {
        requestSearch(query.value)
    }

    fun toggleFavorite(item: CatalogItem) {
        viewModelScope.launch {
            when (val result = safeCall { gateway.toggleFavorite(item.ref) }) {
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
            _message.value = blocked ?: str(R.string.downloads_started, item.title)
        }
    }

    private val mutableGuideIds = MutableStateFlow<Set<String>>(emptySet())
    val guideIds: StateFlow<Set<String>> = mutableGuideIds

    /** Debounced to throttle DB hits while typing. */
    init {
        // viewModelScope uses Main.immediate. Every field touched by these coroutines must be
        // initialized before launch because their bodies can enter before construction returns.
        viewModelScope.launch {
            query.debounce(250).distinctUntilChanged().collect(::requestSearch)
        }
        observeFavorites()
        reloadGuideIds()
    }

    private fun requestSearch(raw: String) {
        val generation = ++searchGeneration
        viewModelScope.launch { search(raw, generation) }
    }

    private suspend fun search(raw: String, generation: Long) {
        val term = raw.trim()
        if (term.length < 2) {
            if (sourceId is SourceId.Hub) mutableGuideIds.value = emptySet()
            mutableState.value = mutableState.value.copy(
                results = CatalogSearchResult(),
                loading = false,
                error = null,
            )
            return
        }
        if (sourceId is SourceId.Hub) mutableGuideIds.value = emptySet()
        mutableState.value = mutableState.value.copy(loading = true, error = null)
        val result = safeCall { gateway.search(term) }
        if (generation != searchGeneration) return
        when (result) {
            is CatalogResult.Success -> {
                mutableState.value = SearchUiState(results = result.value)
                reloadGuideIds(
                    result.value.live.mapNotNullTo(linkedSetOf()) { it.tvgId },
                    generation,
                )
            }
            CatalogResult.SignedOut -> fail(CatalogLoadError.SignedOut)
            CatalogResult.Unreachable -> fail(CatalogLoadError.Unreachable)
            is CatalogResult.Failed -> {
                if (sourceId is SourceId.LocalPlaylist) {
                    ErrorLog.log("Search", result.cause)
                    mutableState.value = SearchUiState()
                } else {
                    fail(CatalogLoadError.Failed(result.cause))
                }
            }
        }
    }

    private fun observeFavorites() {
        val local = sourceId as? SourceId.LocalPlaylist
        if (graph != null && local != null) {
            viewModelScope.launch {
                graph.favorites.observeKeys(local.playlistId).collect(mutableFavoriteKeys::emit)
            }
            return
        }
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

    private fun reloadGuideIds(
        tvgIds: Set<String> = emptySet(),
        generation: Long = searchGeneration,
    ) {
        val local = sourceId as? SourceId.LocalPlaylist
        if (graph != null && local != null) {
            viewModelScope.launch {
                graph.epg.observeGuideIds(local.playlistId).collect(mutableGuideIds::emit)
            }
            return
        }
        if (tvgIds.isEmpty()) return
        viewModelScope.launch {
            when (val result = safeCall { gateway.guideIds(tvgIds) }) {
                is CatalogResult.Success -> if (generation == searchGeneration) {
                    mutableGuideIds.value = result.value
                }
                CatalogResult.SignedOut,
                CatalogResult.Unreachable,
                is CatalogResult.Failed -> Unit
            }
        }
    }

    private fun fail(error: CatalogLoadError) {
        mutableState.value = mutableState.value.copy(loading = false, error = error)
    }

    private suspend fun <T> safeCall(call: suspend () -> CatalogResult<T>): CatalogResult<T> =
        try {
            call()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            CatalogResult.Failed(error)
        }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    sourceId: SourceId,
    onBack: () -> Unit,
    onPlay: (item: CatalogItem, live: Boolean) -> Unit,
    onPlayHubCatchup: (item: CatalogItem, entry: CatalogGuideEntry) -> Unit,
    onOpenMovie: (ContentRef) -> Unit,
    onOpenSeries: (CatalogItem) -> Unit,
    onOpenXtreamSeries: (CatalogItem) -> Unit,
    onSignIn: () -> Unit,
) {
    val viewModel = sourceViewModel(sourceId, ::SearchViewModel)
    val query by viewModel.query.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val results = state.results
    val favoriteKeys by viewModel.favoriteKeys.collectAsStateWithLifecycle()
    val downloadsByUrl by viewModel.downloadsByUrl.collectAsStateWithLifecycle()
    val guideIds by viewModel.guideIds.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val focusRequester = remember { FocusRequester() }
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var guideItem by remember { mutableStateOf<CatalogItem?>(null) }
    // Outlives the result list: a re-search rebuilds it, and a section the user
    // collapsed must not spring open again behind the loading spinner.
    val expandedSections = remember { mutableStateMapOf<String, Boolean>() }
    fun expanded(key: String) = expandedSections.getOrDefault(key, true)

    fun play(item: CatalogItem, live: Boolean) {
        onPlay(item, live)
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.common_search)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        // Shell already reserves dock space; zero insets avoid a double gap.
        contentWindowInsets = WindowInsets(0.dp),
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { viewModel.query.value = it },
                placeholder = { Text(stringResource(R.string.search_placeholder)) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.query.value = "" }) {
                            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.common_clear))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .focusRequester(focusRequester),
            )
            when {
                state.error is CatalogLoadError.SignedOut -> SourceSignedOut(onSignIn)
                state.error is CatalogLoadError.Unreachable ->
                    SourceUnreachable(viewModel::retry)
                state.error is CatalogLoadError.Failed ->
                    SourceLoadFailed(message = null, onRetry = viewModel::retry)
                query.trim().length < 2 -> EmptyState(
                    stringResource(R.string.search_empty_title),
                    stringResource(R.string.search_empty_subtitle),
                )
                state.loading -> androidx.compose.foundation.layout.Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.onSurface)
                }
                results.isEmpty -> EmptyState(
                    stringResource(R.string.search_no_results),
                    stringResource(R.string.search_no_results_subtitle, query),
                )
                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (results.live.isNotEmpty()) {
                            item {
                                SectionHeader(stringResource(R.string.common_live), results.live.size, expanded("live")) {
                                    expandedSections["live"] = !expanded("live")
                                }
                            }
                            if (expanded("live")) items(
                                results.live,
                                key = { it.ref.encode() },
                            ) { channel ->
                                MediaListRow(
                                    title = channel.title,
                                    subtitle = channel.group,
                                    logo = channel.imageUrl,
                                    fallbackKind = channel.kind,
                                    titleTags = mediaTags(channel.title, 1),
                                    onClick = { play(channel, true) },
                                    isFavorite = favoriteKey(channel) in favoriteKeys,
                                    onToggleFavorite = { viewModel.toggleFavorite(channel) },
                                    onGuide = if (
                                        channel.hasGuide ||
                                        channel.tvgId?.let { it in guideIds } == true
                                    ) ({ guideItem = channel }) else null,
                                    guideHighlight = channel.hasCatchup,
                                )
                            }
                        }
                        if (results.movies.isNotEmpty()) {
                            item {
                                SectionHeader(stringResource(R.string.common_movies), results.movies.size, expanded("movies")) {
                                    expandedSections["movies"] = !expanded("movies")
                                }
                            }
                            if (expanded("movies")) items(
                                results.movies,
                                key = { it.ref.encode() },
                            ) { channel ->
                                MediaListRow(
                                    title = channel.title,
                                    subtitle = channel.group,
                                    logo = channel.imageUrl,
                                    fallbackKind = channel.kind,
                                    titleTags = mediaTags(channel.title, 1),
                                    onClick = { onOpenMovie(channel.ref) },
                                    isFavorite = favoriteKey(channel) in favoriteKeys,
                                    onToggleFavorite = { viewModel.toggleFavorite(channel) },
                                    downloadState = downloadIdentityKey(sourceId, channel.ref)
                                        ?.let { downloadsByUrl[it] },
                                    onDownload = if (
                                        sourceId is SourceId.LocalPlaylist || sourceId is SourceId.Hub
                                    ) {
                                        { viewModel.download(channel) }
                                    } else null,
                                )
                            }
                        }
                        if (results.series.isNotEmpty()) {
                            item {
                                SectionHeader(stringResource(R.string.common_series), results.series.size, expanded("series")) {
                                    expandedSections["series"] = !expanded("series")
                                }
                            }
                            if (expanded("series")) items(
                                results.series,
                                key = ::searchItemKey,
                            ) { hit ->
                                MediaListRow(
                                    title = hit.title,
                                    subtitle = hit.group.orEmpty() +
                                        if ((hit.count ?: 0) > 0) {
                                            " · " + pluralStringResource(
                                                R.plurals.search_matching_episodes,
                                                hit.count ?: 0,
                                                hit.count ?: 0,
                                            )
                                        } else "",
                                    logo = hit.imageUrl,
                                    fallbackKind = ChannelKind.SERIES,
                                    onClick = {
                                        if (hit.seriesId != null) onOpenXtreamSeries(hit)
                                        else onOpenSeries(hit)
                                    },
                                    isFavorite = favoriteKey(hit) in favoriteKeys,
                                    onToggleFavorite = { viewModel.toggleFavorite(hit) },
                                    trailingChevron = true,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    guideItem?.let { item ->
        GuideSheet(
            sourceId = sourceId,
            item = item,
            hasEpgConfigured = true,
            onDismiss = { guideItem = null },
            onPlayCatchup = { url, title ->
                guideItem = null
                onPlay(
                    item.copy(ref = ContentRef.LocalUrl(url, 0), title = title),
                    false,
                )
            },
            onPlayHubCatchup = onPlayHubCatchup,
            onSignIn = onSignIn,
            onUnavailable = {
                scope.launch { snackbar.showSnackbar(resources.getString(R.string.guide_catchup_unavailable)) }
            },
        )
    }
}

@Composable
private fun SectionHeader(text: String, count: Int, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$text · $count",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Icon(
            if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = stringResource(if (expanded) R.string.common_collapse else R.string.common_expand),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
