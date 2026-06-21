package com.buco7854.opentv.ui.search

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Search
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.LaunchedEffect
import com.buco7854.opentv.OpenTvApp
import com.buco7854.opentv.data.db.ChannelEntity
import com.buco7854.opentv.data.db.ChannelKind
import com.buco7854.opentv.data.db.DownloadEntity
import com.buco7854.opentv.data.db.DownloadStatus
import com.buco7854.opentv.data.db.FavoriteEntity
import com.buco7854.opentv.data.db.GroupHit
import com.buco7854.opentv.diag.ErrorLog
import com.buco7854.opentv.data.repo.xtreamFavoriteKey
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
    val live: List<ChannelEntity> = emptyList(),
    val movies: List<ChannelEntity> = emptyList(),
    val series: List<SeriesHit> = emptyList(),
) {
    val isEmpty get() = live.isEmpty() && movies.isEmpty() && series.isEmpty()
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class SearchViewModel(app: Application, private val playlistId: Long) : AndroidViewModel(app) {

    val query = MutableStateFlow("")

    /** Debounced so we only hit the DB ~3 times a second max while typing. */
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
                val rows = OpenTvApp.graph.db.channelDao().search(playlistId, escaped)
                // Episodes collapse into one row per show; the series pages
                // handle seasons and episode listing. The Xtream catalog is a
                // separate table, searched alongside (cached episodes are
                // excluded from the channel grouping by their xs: key).
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
                val xtreamSeries = OpenTvApp.graph.db.xtreamSeriesDao()
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
                ErrorLog.log("Search", e)
                SearchResults()
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SearchResults())

    private val graph = OpenTvApp.graph

    /** Same favourite affordance as the browse rows. */
    val favoriteKeys: StateFlow<Set<String>> = graph.db.favoriteDao().observeAll(playlistId)
        .map { list -> list.map { it.key }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val downloadsByUrl: StateFlow<Map<String, DownloadEntity>> = graph.downloads.downloads
        .map { list ->
            list.filter { it.status != DownloadStatus.CANCELLED && it.status != DownloadStatus.FAILED }
                .associateBy { it.url }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    fun toggleFavorite(key: String, kind: Int) {
        viewModelScope.launch {
            val dao = graph.db.favoriteDao()
            if (dao.get(playlistId, key) != null) dao.remove(playlistId, key)
            else dao.add(FavoriteEntity(playlistId = playlistId, key = key, kind = kind))
        }
    }

    fun download(channel: ChannelEntity) {
        viewModelScope.launch { graph.downloads.enqueue(channel) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    playlistId: Long,
    onBack: () -> Unit,
    onPlay: (url: String, title: String) -> Unit,
    onOpenMovie: (channelId: Long) -> Unit,
    onOpenSeries: (seriesKey: String) -> Unit,
    onOpenXtreamSeries: (seriesId: Long) -> Unit,
) {
    val viewModel = playlistViewModel(playlistId, ::SearchViewModel)
    val query by viewModel.query.collectAsState()
    val results by viewModel.results.collectAsState()
    val favoriteKeys by viewModel.favoriteKeys.collectAsState()
    val downloadsByUrl by viewModel.downloadsByUrl.collectAsState()
    val focusRequester = remember { FocusRequester() }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var guideChannel by remember { mutableStateOf<ChannelEntity?>(null) }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Search") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { viewModel.query.value = it },
                placeholder = { Text("Channels, movies, series…") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.query.value = "" }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Clear")
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
                    "Search everything",
                    "Looks across all categories: live, movies and series.",
                )
                results.isEmpty -> EmptyState("No results", "Nothing matches \"$query\".")
                else -> {
                    val expandedSections = remember { mutableStateMapOf<String, Boolean>() }
                    fun expanded(key: String) = expandedSections.getOrDefault(key, true)
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (results.live.isNotEmpty()) {
                            item {
                                SectionHeader("Live", results.live.size, expanded("live")) {
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
                                    onClick = { onPlay(channel.url, channel.name) },
                                    isFavorite = channel.url in favoriteKeys,
                                    onToggleFavorite = { viewModel.toggleFavorite(channel.url, channel.kind) },
                                    onGuide = if (channel.tvgId != null) ({ guideChannel = channel }) else null,
                                )
                            }
                        }
                        if (results.movies.isNotEmpty()) {
                            item {
                                SectionHeader("Movies", results.movies.size, expanded("movies")) {
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
                                SectionHeader("Series", results.series.size, expanded("series")) {
                                    expandedSections["series"] = !expanded("series")
                                }
                            }
                            if (expanded("series")) items(results.series, key = { "series-${it.xtreamSeriesId ?: it.seriesKey}" }) { hit ->
                                val favKey = hit.xtreamSeriesId?.let { xtreamFavoriteKey(it) } ?: hit.seriesKey
                                MediaListRow(
                                    title = hit.seriesKey,
                                    subtitle = hit.groupTitle +
                                        (if (hit.count > 0) " · ${hit.count} matching episodes" else ""),
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
                onPlay(url, title)
            },
            onUnavailable = {
                scope.launch { snackbar.showSnackbar("Catch-up isn't available for this programme.") }
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
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Icon(
            if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
            contentDescription = if (expanded) "Collapse" else "Expand",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
