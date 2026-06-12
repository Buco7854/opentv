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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.buco7854.opentv.data.db.GroupHit
import com.buco7854.opentv.diag.ErrorLog
import com.buco7854.opentv.ui.components.ChannelLogo
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
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

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
    val categories: List<GroupHit> = emptyList(),
    val live: List<ChannelEntity> = emptyList(),
    val movies: List<ChannelEntity> = emptyList(),
    val series: List<SeriesHit> = emptyList(),
) {
    val isEmpty
        get() = categories.isEmpty() && live.isEmpty() && movies.isEmpty() && series.isEmpty()
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
                // Categories match on the group title itself - the fast path
                // when a playlist has hundreds of them.
                val categories = (OpenTvApp.graph.db.channelDao().searchGroups(playlistId, escaped) +
                    OpenTvApp.graph.db.xtreamSeriesDao().searchCategories(playlistId, escaped))
                SearchResults(
                    categories = categories,
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
    onOpenCategory: (kind: Int, group: String) -> Unit,
) {
    val viewModel = playlistViewModel(playlistId, ::SearchViewModel)
    val query by viewModel.query.collectAsState()
    val results by viewModel.results.collectAsState()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Scaffold(
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
                else -> LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (results.categories.isNotEmpty()) {
                        item { SectionHeader("Categories") }
                        items(results.categories, key = { "cat-${it.kind}-${it.groupTitle}" }) { hit ->
                            ResultRow(
                                title = hit.groupTitle,
                                subtitle = "${hit.count} items",
                                logo = null,
                                kind = hit.kind,
                                badge = kindLabel(hit.kind),
                                onClick = { onOpenCategory(hit.kind, hit.groupTitle) },
                            )
                        }
                    }
                    if (results.live.isNotEmpty()) {
                        item { SectionHeader("Live") }
                        items(results.live, key = { it.id }) { channel ->
                            ResultRow(
                                title = channel.name,
                                subtitle = channel.groupTitle,
                                logo = channel.logo,
                                kind = channel.kind,
                                badge = "Live",
                                onClick = { onPlay(channel.url, channel.name) },
                            )
                        }
                    }
                    if (results.movies.isNotEmpty()) {
                        item { SectionHeader("Movies") }
                        items(results.movies, key = { it.id }) { channel ->
                            ResultRow(
                                title = channel.name,
                                subtitle = channel.groupTitle,
                                logo = channel.logo,
                                kind = channel.kind,
                                badge = "Movie",
                                onClick = { onOpenMovie(channel.id) },
                            )
                        }
                    }
                    if (results.series.isNotEmpty()) {
                        item { SectionHeader("Series") }
                        items(results.series, key = { "series-${it.xtreamSeriesId ?: it.seriesKey}" }) { hit ->
                            ResultRow(
                                title = hit.seriesKey,
                                subtitle = hit.groupTitle +
                                    (if (hit.count > 0) " · ${hit.count} matching episodes" else ""),
                                logo = hit.logo,
                                kind = ChannelKind.SERIES,
                                badge = "Series",
                                onClick = {
                                    hit.xtreamSeriesId?.let { onOpenXtreamSeries(it) }
                                        ?: onOpenSeries(hit.seriesKey)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun ResultRow(
    title: String,
    subtitle: String,
    logo: String?,
    kind: Int,
    badge: String,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.focusHighlight(RoundedCornerShape(16.dp)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChannelLogo(logo, kindIcon(kind))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Pill(badge)
        }
    }
}
