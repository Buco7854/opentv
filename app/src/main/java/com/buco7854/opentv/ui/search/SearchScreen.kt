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
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.runtime.LaunchedEffect
import com.buco7854.opentv.OpenTvApp
import com.buco7854.opentv.data.db.ChannelEntity
import com.buco7854.opentv.data.db.ChannelKind
import com.buco7854.opentv.diag.ErrorLog
import com.buco7854.opentv.ui.components.ChannelLogo
import com.buco7854.opentv.ui.components.EmptyState
import com.buco7854.opentv.ui.components.Pill
import com.buco7854.opentv.ui.components.kindIcon
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
data class SeriesHit(val seriesKey: String, val count: Int, val logo: String?, val groupTitle: String)

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
                SearchResults(
                    live = rows.filter { it.kind == ChannelKind.LIVE },
                    movies = rows.filter { it.kind == ChannelKind.MOVIE },
                    // Episodes collapse into one row per show; the series page
                    // handles seasons and episode listing.
                    series = rows.filter { it.kind == ChannelKind.SERIES }
                        .groupBy { it.seriesKey ?: it.name }
                        .map { (key, episodes) ->
                            SeriesHit(
                                seriesKey = key,
                                count = episodes.size,
                                logo = episodes.firstOrNull { it.logo != null }?.logo,
                                groupTitle = episodes.first().groupTitle,
                            )
                        },
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
) {
    val viewModel: SearchViewModel = viewModel(
        key = "search-$playlistId",
        factory = viewModelFactory {
            initializer { SearchViewModel(this[APPLICATION_KEY]!! as Application, playlistId) }
        },
    )
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
                        items(results.series, key = { "series-" + it.seriesKey }) { hit ->
                            ResultRow(
                                title = hit.seriesKey,
                                subtitle = "${hit.groupTitle} · ${hit.count} matching episodes",
                                logo = hit.logo,
                                kind = ChannelKind.SERIES,
                                badge = "Series",
                                onClick = { onOpenSeries(hit.seriesKey) },
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
