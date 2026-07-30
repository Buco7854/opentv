package com.buco7854.opentv.ui.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.buco7854.opentv.R
import com.buco7854.opentv.core.model.DownloadStatus
import com.buco7854.opentv.download.downloadIdentityKey
import com.buco7854.opentv.core.meta.castFromNames
import com.buco7854.opentv.source.CatalogItem
import com.buco7854.opentv.source.CatalogLoadError
import com.buco7854.opentv.source.ContentRef
import com.buco7854.opentv.source.SourceId
import com.buco7854.opentv.source.encode
import com.buco7854.opentv.ui.components.CastRow
import com.buco7854.opentv.ui.components.ExpandableText
import com.buco7854.opentv.ui.components.FavoriteIcon
import com.buco7854.opentv.ui.components.OtvProgressBar
import com.buco7854.opentv.ui.components.Pill
import com.buco7854.opentv.ui.components.SourceLoadFailed
import com.buco7854.opentv.ui.components.SourceSignedOut
import com.buco7854.opentv.ui.components.SourceUnreachable
import kotlinx.coroutines.launch

/** Series page for native Xtream playlists; episodes fetched on first open (cached for a day). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun XtreamSeriesScreen(
    sourceId: SourceId,
    ref: ContentRef,
    seriesKey: String?,
    seriesId: String?,
    onBack: () -> Unit,
    onOpenEpisode: (ContentRef) -> Unit,
    onSignIn: () -> Unit,
) {
    val viewModel = detailViewModel<XtreamSeriesViewModel>(sourceId, ref) {
        XtreamSeriesViewModel(it, sourceId, ref, seriesKey, seriesId)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val episodes by viewModel.episodes.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val downloadsByUrl = remember(downloads) {
        downloads.filter { it.status != DownloadStatus.CANCELLED && it.status != DownloadStatus.FAILED }
            .associateBy { it.downloadIdentityKey() }
    }
    val progressByUrl by viewModel.progressByUrl.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    val detail = state.detail
    val series = detail?.item

    val seasons = state.seasons.ifEmpty {
        episodes.mapNotNull { it.season }.distinct().sorted()
    }
    // Saveable so the chosen season survives process death, and the config changes
    // this Activity does not declare: it handles orientation itself, but not a
    // theme or locale switch, which still recreates it.
    var selectedSeason by rememberSaveable { mutableStateOf<Int?>(null) }
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
        when (state.error) {
            CatalogLoadError.SignedOut -> {
                SourceSignedOut(onSignIn, Modifier.padding(padding))
                return@Scaffold
            }
            CatalogLoadError.Unreachable -> {
                SourceUnreachable(viewModel::retry, Modifier.padding(padding))
                return@Scaffold
            }
            is CatalogLoadError.Failed -> {
                SourceLoadFailed(null, viewModel::retry, Modifier.padding(padding))
                return@Scaffold
            }
            null -> Unit
        }
        if (state.loading && detail == null) {
            androidx.compose.foundation.layout.Box(
                Modifier.padding(padding).fillMaxSize(),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.onSurface)
            }
            return@Scaffold
        }
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
        ) {
            item {
                Poster(series?.imageUrl, Icons.Outlined.VideoLibrary)
                Spacer(Modifier.height(18.dp))
                Text(series?.title.orEmpty(), style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    series?.group?.let { Pill(it) }
                    series?.rating?.let { Pill("★ %.1f".format(it)) }
                    if (episodes.isNotEmpty()) {
                        Pill(
                            pluralStringResource(
                                R.plurals.details_episode_count,
                                state.episodeTotal,
                                state.episodeTotal,
                            ),
                        )
                    }
                }
                series?.genre?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                detail?.description?.let {
                    Spacer(Modifier.height(12.dp))
                    ExpandableText(it)
                }
                val cast = castFromNames(detail?.cast)
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
            items(shown, key = { it.ref.encode() }) { episode ->
                val localUrl = (episode.ref as? ContentRef.LocalUrl)?.url
                EpisodeRow(
                    episode = episode,
                    downloadState = downloadIdentityKey(sourceId, episode.ref)
                        ?.let { downloadsByUrl[it] },
                    onOpen = { onOpenEpisode(episode.ref) },
                    onDownload = if (
                        sourceId is SourceId.LocalPlaylist || sourceId is SourceId.Hub
                    ) {
                        {
                            scope.launch {
                                val blocked = viewModel.enqueue(episode)
                                snackbar.showSnackbar(
                                    blocked ?: resources.getString(
                                        R.string.downloads_started,
                                        episode.title,
                                    ),
                                )
                            }
                        }
                    } else null,
                    progress = episode.progress ?: localUrl?.let { progressByUrl[it] },
                )
            }
            if (sourceId is SourceId.Hub && episodes.size < state.episodeTotal) {
                item(key = "episode-page-${episodes.size}") {
                    androidx.compose.runtime.LaunchedEffect(episodes.size, state.loading) {
                        if (!state.loading) viewModel.loadMore()
                    }
                    OtvProgressBar(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                }
            }
        }
    }
}
