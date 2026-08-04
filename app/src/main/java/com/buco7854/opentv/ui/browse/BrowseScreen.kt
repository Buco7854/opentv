package com.buco7854.opentv.ui.browse

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.buco7854.opentv.R
import com.buco7854.opentv.core.model.ChannelKind
import com.buco7854.opentv.core.model.Download
import com.buco7854.opentv.download.downloadIdentityKey
import com.buco7854.opentv.source.CatalogGroup
import com.buco7854.opentv.source.CatalogGuideEntry
import com.buco7854.opentv.source.CatalogItem
import com.buco7854.opentv.source.CatalogLoadError
import com.buco7854.opentv.source.CatalogProgramme
import com.buco7854.opentv.source.ContentRef
import com.buco7854.opentv.source.CatalogResult
import com.buco7854.opentv.source.PlaylistCapabilities
import com.buco7854.opentv.source.PlaylistOperation
import com.buco7854.opentv.source.SourceId
import com.buco7854.opentv.source.encode
import com.buco7854.opentv.source.seriesEpisodeCount
import com.buco7854.opentv.ui.components.BadgeRow
import com.buco7854.opentv.ui.components.ChannelLogo
import com.buco7854.opentv.ui.components.DownloadStateIcon
import com.buco7854.opentv.ui.components.FavoriteIcon
import com.buco7854.opentv.ui.components.GuideSheet
import com.buco7854.opentv.ui.components.MediaListRow
import com.buco7854.opentv.ui.components.mediaTags
import com.buco7854.opentv.ui.components.PosterGrid
import com.buco7854.opentv.ui.components.PosterItem
import com.buco7854.opentv.ui.components.focusHighlight
import com.buco7854.opentv.ui.components.kindIcon
import com.buco7854.opentv.ui.components.EmptyState
import com.buco7854.opentv.ui.components.OtvProgressBar
import com.buco7854.opentv.ui.components.OtvTextButton
import com.buco7854.opentv.ui.components.SourceLoadFailed
import com.buco7854.opentv.ui.components.SourceSignedOut
import com.buco7854.opentv.ui.components.SourceUnreachable
import com.buco7854.opentv.ui.components.sourceViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    sourceId: SourceId,
    initialTab: Int? = null,
    initialGroup: String? = null,
    onPlay: (item: CatalogItem, live: Boolean) -> Unit,
    onPlayHubCatchup: (item: CatalogItem, entry: CatalogGuideEntry) -> Unit,
    onOpenMovie: (ContentRef) -> Unit,
    onOpenSeries: (CatalogItem) -> Unit,
    onOpenXtreamSeries: (CatalogItem) -> Unit,
    onOpenAccount: () -> Unit,
    onSignIn: () -> Unit,
) {
    val viewModel = sourceViewModel(sourceId, ::BrowseViewModel)

    // Seed the VM once so returning from player/detail keeps position.
    LaunchedEffect(Unit) { viewModel.seedFromRoute(initialTab, initialGroup) }

    val playlist by viewModel.playlist.collectAsStateWithLifecycle()
    val tab by viewModel.tab.collectAsStateWithLifecycle()
    val group by viewModel.group.collectAsStateWithLifecycle()
    val catalog by viewModel.catalog.collectAsStateWithLifecycle()
    val groups = catalog.groups
    val items = catalog.items
    val nowAiring by viewModel.nowAiring.collectAsStateWithLifecycle()
    val guideIds by viewModel.guideIds.collectAsStateWithLifecycle()
    val downloadsByUrl by viewModel.downloadsByUrl.collectAsStateWithLifecycle()
    val traits by viewModel.traits.collectAsStateWithLifecycle()
    val isXtreamNative = traits?.hasXtreamSeries == true
    // Correcting a category is a local convenience for a local playlist, but on a server it
    // edits a catalog other people browse, so the server decides whether this user may do it
    // at all -- and says it does not apply to native Xtream categories, which it owns. Ask
    // rather than inferring: a hub M3U playlist can be corrected, a hub Xtream one cannot.
    var correctionOffered by remember(sourceId) { mutableStateOf(sourceId !is SourceId.Hub) }
    LaunchedEffect(sourceId) {
        if (sourceId !is SourceId.Hub) return@LaunchedEffect
        val result = com.buco7854.opentv.OpenTvApp.graph
            .catalogFor(sourceId)
            .playlistCapabilities()
        correctionOffered = (result as? CatalogResult.Success<PlaylistCapabilities>)
            ?.value
            ?.get(PlaylistOperation.CORRECT_CATEGORY_TYPE) != null
    }
    val gridView by viewModel.gridView.collectAsStateWithLifecycle()
    val favoriteKeys by viewModel.favoriteKeys.collectAsStateWithLifecycle()
    val account by viewModel.account.collectAsStateWithLifecycle()
    val liveCount by viewModel.liveCount.collectAsStateWithLifecycle()
    val movieCount by viewModel.movieCount.collectAsStateWithLifecycle()
    val seriesCount by viewModel.seriesCount.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var guideItem by remember { mutableStateOf<CatalogItem?>(null) }
    var correctingGroup by remember { mutableStateOf<String?>(null) }
    // Filters the currently-shown list (categories at the root, items inside one).
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    LaunchedEffect(group, tab) { viewModel.filter.value = "" }

    val resources = LocalResources.current
    val download: (CatalogItem) -> Unit = viewModel::download

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    // Keep "now airing" rows fresh. A hub answers this over the network, so the
    // timer follows the lifecycle rather than the composition: a backgrounded
    // app must not keep polling a server nobody is looking at.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(60_000)
                viewModel.reloadNowAiring()
            }
        }
    }

    fun play(item: CatalogItem, live: Boolean) {
        onPlay(item, live)
    }

    // Single-group playlists skip the pointless category level.
    LaunchedEffect(groups, tab) {
        if (group == null && groups.size == 1) {
            viewModel.group.value = groups.first().name
        }
    }
    val singleGroup = groups.size == 1

    BackHandler(enabled = group != null && !singleGroup) {
        viewModel.group.value = null
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            (if (singleGroup) null else group) ?: playlist?.name
                                ?: stringResource(R.string.browse_fallback_title),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (account == null) {
                            Text(
                                when (tab) {
                                    ChannelKind.MOVIE -> stringResource(R.string.browse_movies_count, movieCount)
                                    ChannelKind.SERIES -> stringResource(R.string.browse_series_count, seriesCount)
                                    else -> stringResource(R.string.browse_live_count, liveCount)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        account?.let { info ->
                            val warn = info.maxConnections in 1..info.activeConnections
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable { onOpenAccount() },
                            ) {
                                Icon(
                                    Icons.Outlined.Person,
                                    contentDescription = null,
                                    tint = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    stringResource(R.string.browse_connections, info.activeConnections, info.maxConnections),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    if (group != null && !singleGroup) {
                        IconButton(onClick = { viewModel.group.value = null }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back))
                        }
                    }
                },
                actions = {
                    if (group != null && tab != ChannelKind.LIVE) {
                        IconButton(onClick = { viewModel.toggleGridView() }) {
                            Icon(
                                if (gridView) Icons.AutoMirrored.Outlined.ViewList else Icons.Outlined.GridView,
                                contentDescription = stringResource(if (gridView) R.string.common_list_view else R.string.common_grid_view),
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        // Shell already reserves dock space; zero insets avoid a double gap.
        contentWindowInsets = WindowInsets(0.dp),
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            val hasContent = groups.isNotEmpty() || items.isNotEmpty()
            if (hasContent) {
                FilterField(
                    value = filter,
                    onValueChange = { viewModel.filter.value = it },
                    placeholder = stringResource(if (group == null) R.string.browse_filter_categories else R.string.browse_filter_category),
                )
            }
            val f = filter.trim()
            fun matches(s: String) = f.isBlank() || s.contains(f, ignoreCase = true)
            val hasMore = sourceId is SourceId.Hub &&
                catalog.error == null && items.size < catalog.total

            when {
                catalog.error is CatalogLoadError.SignedOut -> SourceSignedOut(onSignIn)
                catalog.error is CatalogLoadError.Unreachable ->
                    SourceUnreachable(viewModel::retry)
                catalog.error is CatalogLoadError.Failed ->
                    SourceLoadFailed(message = null, onRetry = viewModel::retry)
                browseListLoading(
                    loading = catalog.loading,
                    groupSelected = group != null,
                    groupCount = groups.size,
                    itemCount = items.size,
                ) -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.onSurface)
                }
                group == null -> GroupList(
                    groups = groups.filter { matches(it.name) },
                    // Xtream categories come from the panel; only M3U guessing needs correcting.
                    onCorrect = if (isXtreamNative || !correctionOffered) {
                        null
                    } else {
                        ({ correctingGroup = it })
                    },
                    onSelect = { viewModel.group.value = it },
                )

                // Native Xtream playlists list the panel's series catalog.
                tab == ChannelKind.SERIES && isXtreamNative -> {
                    val shown = items.filter { matches(it.title) }
                    if (gridView) {
                        PosterGrid(
                            items = shown.map {
                                PosterItem(it.ref.encode(), it.imageUrl, it.title, it.genre)
                            },
                            fallback = kindIcon(ChannelKind.SERIES),
                            onClick = { id ->
                                shown.firstOrNull { it.ref.encode() == id }
                                    ?.let(onOpenXtreamSeries)
                            },
                            hasMore = hasMore,
                            loadingMore = catalog.loading,
                            onLoadMore = viewModel::loadMore,
                        )
                    } else {
                        XtreamSeriesList(
                            series = shown,
                            favoriteKeys = favoriteKeys,
                            onToggleFavorite = {
                                viewModel.toggleFavorite(
                                    it,
                                )
                            },
                            onSelect = onOpenXtreamSeries,
                            hasMore = hasMore,
                            loadingMore = catalog.loading,
                            onLoadMore = viewModel::loadMore,
                        )
                    }
                }

                // Series open their own page (poster, season picker, episodes).
                tab == ChannelKind.SERIES -> {
                    val shown = items.filter { matches(it.title) }
                    if (gridView) {
                        PosterGrid(
                            items = shown.map {
                                PosterItem(
                                    it.ref.encode(), it.imageUrl, it.title,
                                    seriesEpisodeCount(it.count)?.let { episodes ->
                                        pluralStringResource(
                                            R.plurals.details_episode_count,
                                            episodes,
                                            episodes,
                                        )
                                    },
                                )
                            },
                            fallback = kindIcon(ChannelKind.SERIES),
                            onClick = { id ->
                                shown.firstOrNull { it.ref.encode() == id }
                                    ?.let(onOpenSeries)
                            },
                            hasMore = hasMore,
                            loadingMore = catalog.loading,
                            onLoadMore = viewModel::loadMore,
                        )
                    } else {
                        SeriesList(
                            series = shown,
                            favoriteKeys = favoriteKeys,
                            onToggleFavorite = viewModel::toggleFavorite,
                            onSelect = onOpenSeries,
                            hasMore = hasMore,
                            loadingMore = catalog.loading,
                            onLoadMore = viewModel::loadMore,
                        )
                    }
                }

                tab == ChannelKind.MOVIE && gridView -> PosterGrid(
                    items = items.filter { matches(it.title) }.map {
                        PosterItem(
                            it.ref.encode(),
                            it.imageUrl,
                            it.title,
                            null,
                            tags = mediaTags(it.title, 1),
                        )
                    },
                    fallback = kindIcon(ChannelKind.MOVIE),
                    onClick = { id ->
                        items.firstOrNull { it.ref.encode() == id }?.let { onOpenMovie(it.ref) }
                    },
                    hasMore = hasMore,
                    loadingMore = catalog.loading,
                    onLoadMore = viewModel::loadMore,
                )

                // Movies open a detail page with play/download; live plays directly.
                else -> ChannelList(
                    sourceId = sourceId,
                    channels = items.filter { matches(it.title) },
                    nowAiring = if (tab == ChannelKind.LIVE) nowAiring else emptyMap(),
                    guideIds = guideIds,
                    downloadsByUrl = if (tab == ChannelKind.MOVIE) downloadsByUrl else emptyMap(),
                    favoriteKeys = favoriteKeys,
                    onToggleFavorite = viewModel::toggleFavorite,
                    onPlay = {
                        if (tab == ChannelKind.MOVIE) onOpenMovie(it.ref)
                        else play(it, true)
                    },
                    onDownload = if (
                        tab == ChannelKind.MOVIE &&
                            (sourceId is SourceId.LocalPlaylist || sourceId is SourceId.Hub)
                    ) download else null,
                    onGuide = if (tab == ChannelKind.LIVE) ({ guideItem = it }) else null,
                    hasMore = hasMore,
                    loadingMore = catalog.loading,
                    onLoadMore = viewModel::loadMore,
                )
            }
        }
    }

    correctingGroup?.let { groupTitle ->
        GroupKindDialog(
            groupTitle = groupTitle,
            onDismiss = { correctingGroup = null },
            onSelect = { kind ->
                correctingGroup = null
                viewModel.setGroupKind(groupTitle, kind)
            },
        )
    }

    guideItem?.let { item ->
        GuideSheet(
            sourceId = sourceId,
            item = item,
            hasEpgConfigured = playlist?.epgUrl != null,
            onDismiss = { guideItem = null },
            onPlayCatchup = { url, title ->
                guideItem = null
                onPlay(
                    item.copy(
                        ref = ContentRef.LocalUrl(url, 0),
                        title = title,
                    ),
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
private fun XtreamSeriesList(
    series: List<CatalogItem>,
    favoriteKeys: Set<String>,
    onToggleFavorite: (CatalogItem) -> Unit,
    onSelect: (CatalogItem) -> Unit,
    hasMore: Boolean,
    loadingMore: Boolean,
    onLoadMore: () -> Unit,
) {
    if (series.isEmpty()) {
        EmptyState(
            stringResource(R.string.browse_no_series_title),
            stringResource(R.string.browse_no_series_category),
        )
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(series, key = { it.ref.encode() }) { item ->
            MediaListRow(
                title = item.title,
                subtitle = listOfNotNull(item.genre, item.rating?.let { "★ %.1f".format(it) })
                    .joinToString(" · ").takeIf { it.isNotEmpty() },
                logo = item.imageUrl,
                fallbackKind = ChannelKind.SERIES,
                onClick = { onSelect(item) },
                isFavorite = favoriteKey(item) in favoriteKeys,
                onToggleFavorite = { onToggleFavorite(item) },
                trailingChevron = true,
            )
        }
        if (hasMore) {
            item(key = "catalog-page-${series.size}") {
                LaunchedEffect(series.size, loadingMore) {
                    if (!loadingMore) onLoadMore()
                }
                OtvProgressBar(Modifier.fillMaxWidth().padding(vertical = 8.dp))
            }
        }
    }
}

@Composable
private fun GroupList(
    groups: List<CatalogGroup>,
    onCorrect: ((String) -> Unit)?,
    onSelect: (String) -> Unit,
) {
    if (groups.isEmpty()) {
        EmptyState(
            stringResource(R.string.browse_empty_title),
            stringResource(R.string.browse_empty_subtitle),
        )
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(groups, key = { it.name }) { groupCount ->
            Card(
                onClick = { onSelect(groupCount.name) },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.focusHighlight(RoundedCornerShape(16.dp)),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(14.dp))
                    Text(
                        groupCount.name,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f).padding(vertical = 10.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${groupCount.count}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (onCorrect != null) {
                        IconButton(onClick = { onCorrect(groupCount.name) }) {
                            Icon(
                                Icons.Outlined.MoreVert,
                                contentDescription = stringResource(R.string.browse_correct_category),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        Icon(
                            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** "This category is actually…" correction dialog for misclassified M3U groups. */
@Composable
private fun GroupKindDialog(
    groupTitle: String,
    onDismiss: () -> Unit,
    onSelect: (kind: Int?) -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(groupTitle, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                Text(
                    stringResource(R.string.browse_kind_dialog_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                listOf(
                    ChannelKind.LIVE to stringResource(R.string.browse_kind_live),
                    ChannelKind.MOVIE to stringResource(R.string.common_movies),
                    ChannelKind.SERIES to stringResource(R.string.common_series),
                ).forEach { (kind, label) ->
                    OtvTextButton(onClick = { onSelect(kind) }) { Text(label) }
                }
                OtvTextButton(onClick = { onSelect(null) }) {
                    Text(stringResource(R.string.browse_kind_auto))
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            OtvTextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
private fun SeriesList(
    series: List<CatalogItem>,
    favoriteKeys: Set<String>,
    onToggleFavorite: (CatalogItem) -> Unit,
    onSelect: (CatalogItem) -> Unit,
    hasMore: Boolean,
    loadingMore: Boolean,
    onLoadMore: () -> Unit,
) {
    if (series.isEmpty()) {
        EmptyState(
            stringResource(R.string.browse_no_series_title),
            stringResource(R.string.browse_no_episodes_category),
        )
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(series, key = { it.ref.encode() }) { item ->
            MediaListRow(
                title = item.title,
                subtitle = seriesEpisodeCount(item.count)?.let { episodes ->
                    pluralStringResource(R.plurals.details_episode_count, episodes, episodes)
                } ?: item.group,
                logo = item.imageUrl,
                fallbackKind = ChannelKind.SERIES,
                onClick = { onSelect(item) },
                isFavorite = favoriteKey(item) in favoriteKeys,
                onToggleFavorite = { onToggleFavorite(item) },
                trailingChevron = true,
            )
        }
        if (hasMore) {
            item(key = "catalog-page-${series.size}") {
                LaunchedEffect(series.size, loadingMore) {
                    if (!loadingMore) onLoadMore()
                }
                OtvProgressBar(Modifier.fillMaxWidth().padding(vertical = 8.dp))
            }
        }
    }
}

@Composable
private fun ChannelList(
    sourceId: SourceId,
    channels: List<CatalogItem>,
    nowAiring: Map<String, CatalogProgramme>,
    guideIds: Set<String>,
    downloadsByUrl: Map<String, Download>,
    favoriteKeys: Set<String>,
    onToggleFavorite: (CatalogItem) -> Unit,
    onPlay: (CatalogItem) -> Unit,
    onDownload: ((CatalogItem) -> Unit)?,
    onGuide: ((CatalogItem) -> Unit)?,
    hasMore: Boolean,
    loadingMore: Boolean,
    onLoadMore: () -> Unit,
) {
    if (channels.isEmpty()) {
        EmptyState(
            stringResource(R.string.browse_empty_category),
            stringResource(R.string.browse_empty_category_subtitle),
        )
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(channels, key = { it.ref.encode() }) { channel ->
            ChannelRow(
                channel = channel,
                airing = channel.tvgId?.let { nowAiring[it] },
                downloadState = downloadIdentityKey(sourceId, channel.ref)
                    ?.let { downloadsByUrl[it] },
                isFavorite = favoriteKey(channel) in favoriteKeys,
                onToggleFavorite = { onToggleFavorite(channel) },
                onPlay = { onPlay(channel) },
                onDownload = onDownload?.let { handler -> { handler(channel) } },
                onGuide = if (
                    onGuide != null &&
                    (channel.hasGuide || channel.tvgId?.let { it in guideIds } == true)
                ) ({ onGuide(channel) }) else null,
            )
        }
        if (hasMore) {
            item(key = "catalog-page-${channels.size}") {
                LaunchedEffect(channels.size, loadingMore) {
                    if (!loadingMore) onLoadMore()
                }
                OtvProgressBar(Modifier.fillMaxWidth().padding(vertical = 8.dp))
            }
        }
    }
}

@Composable
private fun ChannelRow(
    channel: CatalogItem,
    airing: CatalogProgramme?,
    downloadState: Download?,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onPlay: () -> Unit,
    onDownload: (() -> Unit)?,
    onGuide: (() -> Unit)?,
) {
    val episodeTag = if (channel.season != null && channel.episode != null)
        "S%02dE%02d · ".format(channel.season, channel.episode) else ""
    val progress = airing?.let {
        ((System.currentTimeMillis() - it.startMs).toFloat() /
            (it.endMs - it.startMs).coerceAtLeast(1)).coerceIn(0f, 1f)
    }
    MediaListRow(
        title = episodeTag + channel.title,
        logo = channel.imageUrl,
        fallbackKind = channel.kind,
        onClick = onPlay,
        titleTags = mediaTags(channel.title, 1),
        nowAiringTitle = airing?.title,
        nowAiringProgress = progress,
        isFavorite = isFavorite,
        onToggleFavorite = onToggleFavorite,
        downloadState = downloadState,
        onDownload = onDownload,
        onGuide = onGuide,
        guideHighlight = channel.hasCatchup,
    )
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

/**
 * Whether the browse content area owes the user a spinner rather than a list.
 * The answer belongs to the level being shown: inside a category the category
 * list behind it is already loaded, so asking whether *anything* has arrived
 * would render "this category is empty" for the whole of a server round trip.
 */
internal fun browseListLoading(
    loading: Boolean,
    groupSelected: Boolean,
    groupCount: Int,
    itemCount: Int,
): Boolean = loading && if (groupSelected) itemCount == 0 else groupCount == 0

/** Compact filter field for the browse content area. */
@Composable
private fun FilterField(value: String, onValueChange: (String) -> Unit, placeholder: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.browse_clear_filter))
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 4.dp),
    )
}
