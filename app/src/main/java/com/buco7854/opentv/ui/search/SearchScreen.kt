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
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.LaunchedEffect
import com.buco7854.opentv.core.log.rethrowCancellation
import com.buco7854.opentv.OpenTvApp
import com.buco7854.opentv.R
import com.buco7854.opentv.core.model.Channel
import com.buco7854.opentv.core.model.ChannelKind
import com.buco7854.opentv.core.model.Download
import com.buco7854.opentv.core.model.DownloadStatus
import com.buco7854.opentv.core.model.Favorite
import com.buco7854.opentv.core.model.GroupHit
import com.buco7854.opentv.core.model.hasCatchup
import com.buco7854.opentv.core.model.hasGuide
import com.buco7854.opentv.diag.ErrorLog
import com.buco7854.opentv.core.repo.xtreamFavoriteKey
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
import com.buco7854.opentv.ui.components.playlistViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One row per series, however many episodes matched. */
data class SeriesHit(
    val seriesKey: String,
    val count: Int,
    val logo: String?,
    val groupTitle: String,
    /** Set when the hit comes from an Xtream panel's series catalog. */
    val xtreamSeriesId: Long? = null,
)

data class SearchResults(
    val live: List<Channel> = emptyList(),
    val movies: List<Channel> = emptyList(),
    val series: List<SeriesHit> = emptyList(),
) {
    val isEmpty get() = live.isEmpty() && movies.isEmpty() && series.isEmpty()
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class SearchViewModel(app: Application, private val playlistId: Long) : AndroidViewModel(app) {

    val query = MutableStateFlow("")

    /** Debounced to throttle DB hits while typing. */
    val results: StateFlow<SearchResults> = query
        .debounce(250)
        .distinctUntilChanged()
        .mapLatest { q ->
            if (q.trim().length < 2) SearchResults()
            else try {
                // Escape LIKE wildcards so "100%" doesn't match everything.
                val escaped = q.trim()
                    .replace("\\", "\\\\")
                    .replace("%", "\\%")
                    .replace("_", "\\_")
                val rows = OpenTvApp.graph.storage.channels.search(playlistId, escaped)
                // Collapse episodes into one row per show; xs: keys (cached Xtream episodes) are excluded, that catalog is searched separately.
                val m3uSeries = rows.filter { it.kind == ChannelKind.SERIES }
                    .filterNot { it.seriesKey?.startsWith("xs:") == true }
                    .groupBy { it.seriesKey ?: it.name }
                    .map { (key, episodes) ->
                        SeriesHit(
                            seriesKey = key,
                            count = episodes.size,
                            logo = episodes.firstOrNull { it.logo != null }?.logo,
                            groupTitle = episodes.first().groupTitle,
                        )
                    }
                val xtreamSeries = OpenTvApp.graph.storage.xtreamSeries
                    .search(playlistId, escaped)
                    .map {
                        SeriesHit(
                            seriesKey = it.name,
                            count = 0,
                            logo = it.cover,
                            groupTitle = it.categoryName,
                            xtreamSeriesId = it.seriesId,
                        )
                    }
                SearchResults(
                    live = rows.filter { it.kind == ChannelKind.LIVE },
                    movies = rows.filter { it.kind == ChannelKind.MOVIE },
                    series = xtreamSeries + m3uSeries,
                )
            } catch (e: Exception) {
                e.rethrowCancellation()
                ErrorLog.log("Search", e)
                SearchResults()
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SearchResults())

    private val graph = OpenTvApp.graph

    /** Same favourite affordance as the browse rows. */
    val favoriteKeys: StateFlow<Set<String>> = graph.storage.favorites.observeAll(playlistId)
        .map { list -> list.map { it.key }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val downloadsByUrl: StateFlow<Map<String, Download>> = graph.downloads.downloads
        .map { list ->
            list.filter { it.status != DownloadStatus.CANCELLED && it.status != DownloadStatus.FAILED }
                .associateBy { it.url }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    fun toggleFavorite(key: String, kind: Int) {
        viewModelScope.launch {
            val dao = graph.storage.favorites
            if (dao.get(playlistId, key) != null) dao.remove(playlistId, key)
            else dao.add(Favorite(playlistId = playlistId, key = key, kind = kind))
        }
    }

    fun download(channel: Channel) {
        viewModelScope.launch { graph.downloads.enqueue(channel) }
    }

    val guideIds: StateFlow<Set<String>> = graph.epg.observeGuideIds(playlistId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    playlistId: Long,
    onBack: () -> Unit,
    onPlay: (url: String, title: String, live: Boolean) -> Unit,
    onOpenMovie: (channelId: Long) -> Unit,
    onOpenSeries: (seriesKey: String) -> Unit,
    onOpenXtreamSeries: (seriesId: Long) -> Unit,
) {
    val viewModel = playlistViewModel(playlistId, ::SearchViewModel)
    val query by viewModel.query.collectAsState()
    val results by viewModel.results.collectAsState()
    val favoriteKeys by viewModel.favoriteKeys.collectAsState()
    val downloadsByUrl by viewModel.downloadsByUrl.collectAsState()
    val guideIds by viewModel.guideIds.collectAsState()
    val focusRequester = remember { FocusRequester() }
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var guideChannel by remember { mutableStateOf<Channel?>(null) }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

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
                query.trim().length < 2 -> EmptyState(
                    stringResource(R.string.search_empty_title),
                    stringResource(R.string.search_empty_subtitle),
                )
                results.isEmpty -> EmptyState(
                    stringResource(R.string.search_no_results),
                    stringResource(R.string.search_no_results_subtitle, query),
                )
                else -> {
                    val expandedSections = remember { mutableStateMapOf<String, Boolean>() }
                    fun expanded(key: String) = expandedSections.getOrDefault(key, true)
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
                            if (expanded("live")) items(results.live, key = { it.id }) { channel ->
                                MediaListRow(
                                    title = channel.name,
                                    subtitle = channel.groupTitle,
                                    logo = channel.logo,
                                    fallbackKind = channel.kind,
                                    titleTags = mediaTags(channel.name, 1),
                                    onClick = { onPlay(channel.url, channel.name, true) },
                                    isFavorite = channel.url in favoriteKeys,
                                    onToggleFavorite = { viewModel.toggleFavorite(channel.url, channel.kind) },
                                    onGuide = if (channel.hasGuide(guideIds)) ({ guideChannel = channel }) else null,
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
                            if (expanded("movies")) items(results.movies, key = { it.id }) { channel ->
                                MediaListRow(
                                    title = channel.name,
                                    subtitle = channel.groupTitle,
                                    logo = channel.logo,
                                    fallbackKind = channel.kind,
                                    titleTags = mediaTags(channel.name, 1),
                                    onClick = { onOpenMovie(channel.id) },
                                    isFavorite = channel.url in favoriteKeys,
                                    onToggleFavorite = { viewModel.toggleFavorite(channel.url, channel.kind) },
                                    downloadState = downloadsByUrl[channel.url],
                                    onDownload = { viewModel.download(channel) },
                                )
                            }
                        }
                        if (results.series.isNotEmpty()) {
                            item {
                                SectionHeader(stringResource(R.string.common_series), results.series.size, expanded("series")) {
                                    expandedSections["series"] = !expanded("series")
                                }
                            }
                            if (expanded("series")) items(results.series, key = { "series-${it.xtreamSeriesId ?: it.seriesKey}" }) { hit ->
                                val favKey = hit.xtreamSeriesId?.let { xtreamFavoriteKey(it) } ?: hit.seriesKey
                                MediaListRow(
                                    title = hit.seriesKey,
                                    subtitle = hit.groupTitle +
                                        if (hit.count > 0) {
                                            " · " + pluralStringResource(
                                                R.plurals.search_matching_episodes, hit.count, hit.count,
                                            )
                                        } else "",
                                    logo = hit.logo,
                                    fallbackKind = ChannelKind.SERIES,
                                    onClick = {
                                        hit.xtreamSeriesId?.let { onOpenXtreamSeries(it) }
                                            ?: onOpenSeries(hit.seriesKey)
                                    },
                                    isFavorite = favKey in favoriteKeys,
                                    onToggleFavorite = { viewModel.toggleFavorite(favKey, ChannelKind.SERIES) },
                                    trailingChevron = true,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    guideChannel?.let { channel ->
        GuideSheet(
            channel = channel,
            hasEpgConfigured = true,
            onDismiss = { guideChannel = null },
            onPlayCatchup = { url, title ->
                guideChannel = null
                onPlay(url, title, false)
            },
            onUnavailable = {
                scope.launch { snackbar.showSnackbar(context.getString(R.string.guide_catchup_unavailable)) }
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
