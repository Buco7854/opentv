package com.buco7854.opentv.ui.favorites

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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.buco7854.opentv.OpenTvApp
import com.buco7854.opentv.data.db.ChannelEntity
import com.buco7854.opentv.data.db.ChannelKind
import com.buco7854.opentv.data.db.SeriesGroup
import com.buco7854.opentv.data.db.XtreamSeriesEntity
import com.buco7854.opentv.data.repo.xtreamFavoriteKey
import com.buco7854.opentv.ui.components.ChannelLogo
import com.buco7854.opentv.ui.components.EmptyState
import com.buco7854.opentv.ui.components.FavoriteIcon
import com.buco7854.opentv.ui.components.focusHighlight
import com.buco7854.opentv.ui.components.kindIcon
import com.buco7854.opentv.ui.components.playlistViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class FavoritesViewModel(app: Application, private val playlistId: Long) : AndroidViewModel(app) {

    private val graph = OpenTvApp.graph

    private val favorites = graph.db.favoriteDao().observeAll(playlistId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private fun channelsOfKind(kind: Int): StateFlow<List<ChannelEntity>> =
        favorites.flatMapLatest { favs ->
            val urls = favs.filter { it.kind == kind }.map { it.key }
            if (urls.isEmpty()) flowOf(emptyList())
            else graph.db.channelDao().observeByUrls(playlistId, kind, urls.take(900))
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val live = channelsOfKind(ChannelKind.LIVE)
    val movies = channelsOfKind(ChannelKind.MOVIE)

    val m3uSeries: StateFlow<List<SeriesGroup>> = favorites.flatMapLatest { favs ->
        val keys = favs.filter { it.kind == ChannelKind.SERIES && !it.key.startsWith("x:") }
            .map { it.key }.toSet()
        if (keys.isEmpty()) flowOf(emptyList())
        else graph.db.channelDao().observeAllSeries(playlistId)
            .map { list -> list.filter { it.seriesKey in keys } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val xtreamSeries: StateFlow<List<XtreamSeriesEntity>> = favorites.flatMapLatest { favs ->
        val ids = favs.filter { it.key.startsWith("x:") }
            .mapNotNull { it.key.removePrefix("x:").toLongOrNull() }.toSet()
        if (ids.isEmpty()) flowOf(emptyList())
        else graph.db.xtreamSeriesDao().observeAll(playlistId)
            .map { list -> list.filter { it.seriesId in ids } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun remove(key: String) {
        viewModelScope.launch { graph.db.favoriteDao().remove(playlistId, key) }
    }
}

/** Dedicated favorites page: explicit destination instead of a hidden filter. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    playlistId: Long,
    onBack: () -> Unit,
    onPlay: (url: String, title: String, tvgId: String?) -> Unit,
    onOpenMovie: (channelId: Long) -> Unit,
    onOpenSeries: (seriesKey: String) -> Unit,
    onOpenXtreamSeries: (seriesId: Long) -> Unit,
) {
    val viewModel = playlistViewModel(playlistId, ::FavoritesViewModel)
    val live by viewModel.live.collectAsState()
    val movies by viewModel.movies.collectAsState()
    val m3uSeries by viewModel.m3uSeries.collectAsState()
    val xtreamSeries by viewModel.xtreamSeries.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Favorites") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        if (live.isEmpty() && movies.isEmpty() && m3uSeries.isEmpty() && xtreamSeries.isEmpty()) {
            Column(Modifier.padding(padding).fillMaxSize()) {
                EmptyState(
                    "No favorites yet",
                    "Tap the heart on channels, movies or series to pin them here.",
                )
            }
            return@Scaffold
        }
        LazyColumn(
            Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (live.isNotEmpty()) {
                item { SectionLabel("Live") }
                items(live, key = { "l${it.id}" }) { channel ->
                    FavoriteRow(
                        logo = channel.logo,
                        fallback = kindIcon(ChannelKind.LIVE),
                        title = channel.name,
                        subtitle = channel.groupTitle,
                        onClick = { onPlay(channel.url, channel.name, channel.tvgId) },
                        onRemove = { viewModel.remove(channel.url) },
                    )
                }
            }
            if (movies.isNotEmpty()) {
                item { SectionLabel("Movies") }
                items(movies, key = { "m${it.id}" }) { channel ->
                    FavoriteRow(
                        logo = channel.logo,
                        fallback = kindIcon(ChannelKind.MOVIE),
                        title = channel.name,
                        subtitle = channel.groupTitle,
                        onClick = { onOpenMovie(channel.id) },
                        onRemove = { viewModel.remove(channel.url) },
                    )
                }
            }
            if (m3uSeries.isNotEmpty() || xtreamSeries.isNotEmpty()) {
                item { SectionLabel("Series") }
                items(xtreamSeries, key = { "x${it.seriesId}" }) { series ->
                    FavoriteRow(
                        logo = series.cover,
                        fallback = kindIcon(ChannelKind.SERIES),
                        title = series.name,
                        subtitle = series.genre ?: series.categoryName,
                        onClick = { onOpenXtreamSeries(series.seriesId) },
                        onRemove = { viewModel.remove(xtreamFavoriteKey(series.seriesId)) },
                    )
                }
                items(m3uSeries, key = { "s${it.seriesKey}" }) { series ->
                    FavoriteRow(
                        logo = series.logo,
                        fallback = kindIcon(ChannelKind.SERIES),
                        title = series.seriesKey,
                        subtitle = "${series.count} episodes",
                        onClick = { onOpenSeries(series.seriesKey) },
                        onRemove = { viewModel.remove(series.seriesKey) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun FavoriteRow(
    logo: String?,
    fallback: ImageVector,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.focusHighlight(RoundedCornerShape(16.dp)),
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            ChannelLogo(logo, fallback)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                subtitle?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            FavoriteIcon(isFavorite = true, onToggle = onRemove)
        }
    }
}
