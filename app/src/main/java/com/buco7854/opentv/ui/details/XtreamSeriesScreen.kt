package com.buco7854.opentv.ui.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.buco7854.opentv.R
import com.buco7854.opentv.core.model.DownloadStatus
import com.buco7854.opentv.core.meta.castFromNames
import com.buco7854.opentv.diag.ErrorLog
import com.buco7854.opentv.ui.components.CastRow
import com.buco7854.opentv.ui.components.ExpandableText
import com.buco7854.opentv.ui.components.FavoriteIcon
import com.buco7854.opentv.ui.components.Pill
import kotlinx.coroutines.launch

/** Series page for native Xtream playlists; episodes fetched on first open (cached for a day). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun XtreamSeriesScreen(
    playlistId: Long,
    seriesId: Long,
    onBack: () -> Unit,
    onOpenEpisode: (channelId: Long) -> Unit,
) {
    val viewModel = detailViewModel("XtreamSeries-$playlistId-$seriesId") {
        XtreamSeriesViewModel(it, playlistId, seriesId)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val episodes by viewModel.episodes.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val downloadsByUrl = remember(downloads) {
        downloads.filter { it.status != DownloadStatus.CANCELLED && it.status != DownloadStatus.FAILED }
            .associateBy { it.url }
    }
    val progressByUrl by viewModel.progressByUrl.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    val series = state.series
    val error = state.error?.let {
        resources.getString(R.string.details_episodes_error, ErrorLog.describe(it))
    }

    val seasons = remember(episodes) { episodes.mapNotNull { it.season }.distinct().sorted() }
    var selectedSeason by remember { mutableStateOf<Int?>(null) }
    val shown = remember(episodes, selectedSeason) {
        selectedSeason?.let { s -> episodes.filter { it.season == s } } ?: episodes
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    FavoriteIcon(
                        isFavorite = state.isFavorite,
                        onToggle = viewModel::toggleFavorite,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        contentWindowInsets = WindowInsets(0.dp),
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
        ) {
            item {
                Poster(series?.cover, Icons.Outlined.VideoLibrary)
                Spacer(Modifier.height(18.dp))
                Text(series?.name.orEmpty(), style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    series?.categoryName?.let { Pill(it) }
                    series?.rating?.let { Pill("★ %.1f".format(it)) }
                    if (episodes.isNotEmpty()) Pill("${episodes.size} episodes")
                }
                series?.genre?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                series?.plot?.let {
                    Spacer(Modifier.height(12.dp))
                    ExpandableText(it)
                }
                val cast = castFromNames(series?.castNames)
                if (cast.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        stringResource(R.string.details_cast),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(8.dp))
                    CastRow(cast)
                }
                Spacer(Modifier.height(18.dp))
                when {
                    state.loading && episodes.isEmpty() -> Text(
                        stringResource(R.string.details_loading_episodes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    error != null && episodes.isEmpty() -> Text(
                        error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    seasons.isNotEmpty() -> {
                        SeasonPicker(
                            seasons = seasons,
                            selected = selectedSeason,
                            onSelect = { selectedSeason = it },
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }
            items(shown, key = { it.id }) { episode ->
                EpisodeRow(
                    episode = episode,
                    downloadState = downloadsByUrl[episode.url],
                    onOpen = { onOpenEpisode(episode.id) },
                    onDownload = {
                        scope.launch {
                            val blocked = viewModel.enqueue(episode)
                            snackbar.showSnackbar(
                                blocked ?: resources.getString(R.string.downloads_started, episode.name),
                            )
                        }
                    },
                    progress = progressByUrl[episode.url],
                )
            }
        }
    }
}
