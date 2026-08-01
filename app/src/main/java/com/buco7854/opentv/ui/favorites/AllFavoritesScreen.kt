package com.buco7854.opentv.ui.favorites

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.buco7854.opentv.OpenTvApp
import com.buco7854.opentv.R
import com.buco7854.opentv.core.model.ChannelKind
import com.buco7854.opentv.core.model.DownloadStatus
import com.buco7854.opentv.download.downloadIdentityKey
import com.buco7854.opentv.source.CatalogGuideEntry
import com.buco7854.opentv.source.CatalogItem
import com.buco7854.opentv.source.CatalogLoadError
import com.buco7854.opentv.source.ContentRef
import com.buco7854.opentv.source.FavoritesSection
import com.buco7854.opentv.source.SourceId
import com.buco7854.opentv.source.encode
import com.buco7854.opentv.ui.components.EmptyState
import com.buco7854.opentv.ui.components.GuideSheet
import com.buco7854.opentv.ui.components.MediaListRow
import com.buco7854.opentv.ui.components.OtvProgressBar
import com.buco7854.opentv.ui.components.OtvTextButton
import com.buco7854.opentv.ui.components.PosterCard
import com.buco7854.opentv.ui.components.PosterItem
import com.buco7854.opentv.ui.components.RequestInitialFocusOnTv
import com.buco7854.opentv.ui.components.SourceLoadFailed
import com.buco7854.opentv.ui.components.SourceSignedOut
import com.buco7854.opentv.ui.components.SourceUnreachable
import com.buco7854.opentv.ui.components.focusHighlight
import com.buco7854.opentv.ui.components.isTelevisionUiMode
import com.buco7854.opentv.ui.components.kindIcon
import com.buco7854.opentv.ui.components.mediaTags
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** A selected favorite, remembered with the source that owns it. */
private data class FavKey(val source: SourceId, val item: CatalogItem)

internal fun favoriteItemKey(source: SourceId, item: CatalogItem): String {
    val encodedSource = source.encode()
    return "${encodedSource.length}:$encodedSource${item.ref.encode()}"
}

internal fun retainedFavoriteFilter(
    selected: SourceId?,
    sections: List<FavoritesSection>,
): SourceId? = selected?.takeIf { source -> sections.any { it.source == source } }

/**
 * Whether a published section change should end selection mode: only when
 * everything that was selected has gone. An empty selection is where selection
 * mode starts, so a section reporting its own load state must not close it
 * while the user is still choosing.
 */
internal fun shouldExitFavoriteSelection(selectedBefore: Int, retained: Int): Boolean =
    selectedBefore > 0 && retained == 0

/**
 * Every favorite the user has, from every source, on one page.
 *
 * Favorites stay owned by the source they came from — local ones live in the
 * local database, a server's live on that server — so this only ever presents
 * them side by side. Two consequences shape the layout:
 *
 * - Each source is a section with its **own** load state. One unreachable
 *   server must not blank the page, so its section shows the failure while the
 *   rest still render.
 * - The same channel present in two sources is shown twice on purpose. They are
 *   different playable entries, and the section headers already explain why.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllFavoritesScreen(
    onOpen: (SourceId, CatalogItem) -> Unit,
    onPlay: (SourceId, CatalogItem, Boolean) -> Unit,
    onPlayHubCatchup: (SourceId, CatalogItem, CatalogGuideEntry) -> Unit,
    onSignIn: (SourceId) -> Unit,
) {
    val graph = OpenTvApp.graph
    val favorites = remember { graph.aggregatedFavorites }
    val state by favorites.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val backFocusRequester = remember { FocusRequester() }
    val deleteCancelFocusRequester = remember { FocusRequester() }
    var focusResetNonce by remember { mutableStateOf(0) }
    val television = isTelevisionUiMode(LocalConfiguration.current.uiMode)
    RequestInitialFocusOnTv(backFocusRequester, focusResetNonce)

    var filter by remember { mutableStateOf<SourceId?>(null) }
    val gridPreference = remember(graph.playerPrefs) {
        graph.playerPrefs.settings.map { it.gridBrowse }
    }
    val gridView by gridPreference
        .collectAsStateWithLifecycle(initialValue = true)
    val selected = remember { mutableStateMapOf<String, FavKey>() }
    var selectMode by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var guideTarget by remember { mutableStateOf<Pair<SourceId, CatalogItem>?>(null) }
    val expandedSections = remember { mutableStateMapOf<String, Boolean>() }
    val downloads by graph.downloads.downloads
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val downloadsByKey = remember(downloads) {
        downloads.filter {
            it.status != DownloadStatus.CANCELLED && it.status != DownloadStatus.FAILED
        }.associateBy { it.downloadIdentityKey() }
    }

    val removedLabel = stringResource(R.string.favorites_removed)
    val undoLabel = stringResource(R.string.common_undo)
    val liveLabel = stringResource(R.string.common_live)
    val moviesLabel = stringResource(R.string.common_movies)
    val seriesLabel = stringResource(R.string.common_series)

    val visible = state.sections.filter { filter == null || it.source == filter }
    // Filter chips only earn their space when there is more than one source to switch between.
    val showFilters = state.hasMultipleSources

    LaunchedEffect(state.sections) {
        filter = retainedFavoriteFilter(filter, state.sections)
        val available = state.sections.flatMapTo(mutableSetOf()) { section ->
            section.items.map { favoriteItemKey(section.source, it) }
        }
        val selectedBefore = selected.size
        selected.keys.toList().filterNot { it in available }.forEach(selected::remove)
        if (shouldExitFavoriteSelection(selectedBefore, selected.size)) selectMode = false
    }

    fun keyOf(source: SourceId, item: CatalogItem) = favoriteItemKey(source, item)
    fun exitSelect() { selectMode = false; selected.clear() }
    fun expanded(key: String) = expandedSections.getOrDefault(key, true)
    BackHandler(enabled = selectMode, onBack = ::exitSelect)

    /** Removing and re-adding both go through the owning source, so nothing migrates. */
    fun removeWithUndo(entries: List<FavKey>) {
        if (entries.isEmpty()) return
        exitSelect()
        focusResetNonce++
        scope.launch {
            entries.forEach { favorites.setFavorite(it.source, it.item.ref, favorite = false) }
            val result = snackbar.showSnackbar(removedLabel, actionLabel = undoLabel)
            if (result == SnackbarResult.ActionPerformed) {
                entries.forEach { favorites.setFavorite(it.source, it.item.ref, favorite = true) }
            }
        }
    }

    fun download(source: SourceId, item: CatalogItem) {
        scope.launch {
            val blocked = when (source) {
                is SourceId.LocalPlaylist -> {
                    val ref = item.ref as? ContentRef.LocalUrl ?: return@launch
                    val channel = ref.channelId.takeIf { it != 0L }
                        ?.let { graph.storage.channels.get(it) }
                        ?.takeIf {
                            it.playlistId == source.playlistId && it.url == ref.url
                        }
                        ?: graph.storage.channels.getByUrl(source.playlistId, ref.url)
                        ?: return@launch
                    graph.downloads.enqueue(channel)
                }
                is SourceId.Hub -> {
                    val ref = item.ref as? ContentRef.HubContent ?: return@launch
                    graph.downloads.enqueueHub(source.hubId, ref.contentId, item.title)
                }
                is SourceId.HubConnection -> return@launch
            }
            snackbar.showSnackbar(
                blocked ?: resources.getString(R.string.downloads_started, item.title),
            )
        }
    }

    // Refresh on arrival, not only after the first minute. The aggregate is
    // application-scoped, so favouriting from a detail screen and coming here would
    // otherwise show the previously cached (often empty) sections for a full minute --
    // the server has the favourite, the screen just never asked again. The timer also
    // follows the lifecycle: a hub answers this over the network, and a backgrounded
    // app must not keep polling a server nobody is looking at.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            favorites.refresh()
            while (true) {
                delay(60_000)
                favorites.refresh()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selectMode) {
                            stringResource(R.string.favorites_selected_count, selected.size)
                        } else {
                            stringResource(R.string.favorites_all_title)
                        },
                    )
                },
                navigationIcon = {
                    // Only in selection mode, where it closes the selection. Favourites is a
                    // dock section reached by clearing the back stack, so an arrow outside
                    // selection has nothing to pop and did nothing when pressed. Browse
                    // applies the same rule: it shows an arrow only when leaving a group.
                    if (selectMode) {
                        IconButton(
                            onClick = ::exitSelect,
                            modifier = Modifier
                                .focusRequester(backFocusRequester)
                                .focusHighlight(),
                        ) {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = stringResource(R.string.common_close),
                            )
                        }
                    }
                },
                actions = {
                    if (selectMode) {
                        IconButton(
                            onClick = {
                                val visibleKeys = visible.flatMapTo(mutableSetOf()) { section ->
                                    section.items.map { keyOf(section.source, it) }
                                }
                                if (visibleKeys.isNotEmpty() &&
                                    visibleKeys.all(selected::containsKey)
                                ) {
                                    visibleKeys.forEach(selected::remove)
                                } else {
                                    visible.forEach { section ->
                                        section.items.forEach {
                                            selected[keyOf(section.source, it)] =
                                                FavKey(section.source, it)
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.focusHighlight(),
                        ) {
                            Icon(
                                Icons.Outlined.DoneAll,
                                contentDescription = stringResource(R.string.favorites_select_all),
                            )
                        }
                        IconButton(
                            onClick = { confirmDelete = true },
                            enabled = selected.isNotEmpty(),
                            modifier = Modifier.focusHighlight(),
                        ) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = stringResource(R.string.favorites_remove_selected),
                                tint = if (selected.isNotEmpty()) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        IconButton(
                            onClick = { selectMode = true },
                            modifier = Modifier.focusHighlight(),
                        ) {
                            Icon(
                                Icons.Outlined.Checklist,
                                contentDescription = stringResource(R.string.favorites_select),
                            )
                        }
                        IconButton(
                            onClick = {
                                scope.launch {
                                    val current = graph.playerPrefs.settings.first()
                                    graph.playerPrefs.save(
                                        current.copy(gridBrowse = !current.gridBrowse),
                                    )
                                }
                            },
                            modifier = Modifier.focusHighlight(),
                        ) {
                            Icon(
                                if (gridView) Icons.AutoMirrored.Outlined.ViewList else Icons.Outlined.GridView,
                                contentDescription = stringResource(
                                    if (gridView) R.string.common_list_view else R.string.common_grid_view,
                                ),
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // One indicator for the whole page. Each source used to carry its own bar under
            // its own heading, so two sources meant two bars stacked under two headings --
            // and before any section existed there was a third at the top. Sources appear as
            // they arrive instead; a heading with a spinner under it says nothing the single
            // bar does not already say.
            val anyLoading = state.loading || state.sections.any { it.loading }
            if (anyLoading) OtvProgressBar(Modifier.fillMaxWidth())

            if (showFilters && !selectMode) {
                SourceFilterChips(state.sections, filter) { filter = it }
            }
            if (television && !selectMode && state.sections.isNotEmpty()) {
                Text(
                    stringResource(R.string.favorites_tv_select_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }

            // "No favourites" means no source has any, not merely that no source exists.
            if (!anyLoading &&
                state.sections.none { it.items.isNotEmpty() || it.error != null }
            ) {
                EmptyState(
                    title = stringResource(R.string.favorites_empty_title),
                    subtitle = stringResource(R.string.favorites_empty_subtitle),
                )
                return@Column
            }

            // One grid for both modes: list view is simply a single column, so
            // section headers span correctly either way.
            LazyVerticalGrid(
                columns = if (gridView) GridCells.Adaptive(minSize = 112.dp) else GridCells.Fixed(1),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(if (gridView) 12.dp else 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Sources are a filter, not a layout: the chips above switch between them
                // and default to all, so a heading per source only split one list into
                // several. Kind stays, because that is what people actually scan by.
                visible.filter { it.error != null }.forEach { section ->
                    item(
                        key = "e-${section.source.encode()}",
                        span = { GridItemSpan(maxLineSpan) },
                    ) {
                        SectionFailure(
                            error = section.error!!,
                            onRetry = { favorites.retry(section.source) },
                            onSignIn = { onSignIn(section.source) },
                        )
                    }
                }
                val entries = visible.flatMap { section ->
                    section.items.map { section.source to it }
                }
                listOf(
                    ChannelKind.LIVE to liveLabel,
                    ChannelKind.MOVIE to moviesLabel,
                    ChannelKind.SERIES to seriesLabel,
                ).forEach { (kind, label) ->
                    val kindItems = entries.filter { (_, item) -> item.kind == kind }
                    if (kindItems.isEmpty()) return@forEach
                    val kindKey = "kind-$kind"
                    item(key = "kh-$kindKey", span = { GridItemSpan(maxLineSpan) }) {
                        KindSectionTitle(
                            title = label,
                            count = kindItems.size,
                            expanded = expanded(kindKey),
                            onToggle = { expandedSections[kindKey] = !expanded(kindKey) },
                        )
                    }
                    if (!expanded(kindKey)) return@forEach
                    items(
                        kindItems,
                        key = { (source, item) -> keyOf(source, item) },
                        span = {
                            if (gridView) GridItemSpan(1) else GridItemSpan(maxLineSpan)
                        },
                    ) { (source, item) ->
                                    val key = keyOf(source, item)
                                    val entry = FavKey(source, item)
                                    val isSelected = selected.containsKey(key)
                                    fun toggleSelect() {
                                        if (selected.remove(key) == null) selected[key] = entry
                                        if (selected.isEmpty()) selectMode = false
                                    }
                                    if (gridView) {
                                        PosterCard(
                                            item = PosterItem(
                                                key,
                                                item.imageUrl,
                                                item.title,
                                                item.count?.let {
                                                    resources.getQuantityString(
                                                        R.plurals.details_episode_count,
                                                        it,
                                                        it,
                                                    )
                                                } ?: item.group,
                                                tags = mediaTags(item.title, 1),
                                            ),
                                            fallback = kindIcon(item.kind),
                                            onClick = {
                                                if (selectMode) toggleSelect()
                                                else onOpen(source, item)
                                            },
                                            selected = isSelected.takeIf { selectMode },
                                            onLongClick = {
                                                selectMode = true
                                                selected[key] = entry
                                            },
                                        )
                                    } else {
                                        val airing = item.nowAiring
                                        val airingProgress = airing?.let {
                                            ((System.currentTimeMillis() - it.startMs).toFloat() /
                                                (it.endMs - it.startMs).coerceAtLeast(1))
                                                .coerceIn(0f, 1f)
                                        }
                                        MediaListRow(
                                            title = item.title,
                                            logo = item.imageUrl,
                                            fallbackKind = item.kind,
                                            subtitle = item.count?.let {
                                                pluralStringResource(
                                                    R.plurals.details_episode_count,
                                                    it,
                                                    it,
                                                )
                                            } ?: item.group,
                                            titleTags = mediaTags(item.title, 1),
                                            nowAiringTitle = airing?.title,
                                            nowAiringProgress = airingProgress,
                                            onClick = {
                                                if (selectMode) toggleSelect()
                                                else onOpen(source, item)
                                            },
                                            isFavorite = true.takeIf { !selectMode },
                                            onToggleFavorite = { removeWithUndo(listOf(entry)) },
                                            trailingChevron = item.kind == ChannelKind.SERIES,
                                            selected = isSelected.takeIf { selectMode },
                                            onLongClick = {
                                                selectMode = true
                                                selected[key] = entry
                                            },
                                            downloadState = if (item.kind == ChannelKind.MOVIE) {
                                                downloadIdentityKey(source, item.ref)
                                                    ?.let(downloadsByKey::get)
                                            } else null,
                                            onDownload = if (item.kind == ChannelKind.MOVIE) {
                                                { download(source, item) }
                                            } else null,
                                            onGuide = if (
                                                item.kind == ChannelKind.LIVE && item.hasGuide
                                            ) {
                                                { guideTarget = source to item }
                                            } else null,
                                            guideHighlight = item.hasCatchup,
                                        )
                                    }
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        val entries = selected.values.toList()
        RequestInitialFocusOnTv(deleteCancelFocusRequester)
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.favorites_remove_title)) },
            text = {
                Text(
                    stringResource(R.string.favorites_remove_message, entries.size),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                OtvTextButton(
                    danger = true,
                    onClick = {
                        confirmDelete = false
                        removeWithUndo(entries)
                    },
                ) {
                    Text(stringResource(R.string.favorites_remove_confirm))
                }
            },
            dismissButton = {
                OtvTextButton(
                    onClick = { confirmDelete = false },
                    modifier = Modifier.focusRequester(deleteCancelFocusRequester),
                ) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    guideTarget?.let { (source, item) ->
        GuideSheet(
            sourceId = source,
            item = item,
            hasEpgConfigured = true,
            onDismiss = { guideTarget = null },
            onPlayCatchup = { url, title ->
                guideTarget = null
                onPlay(source, item.copy(ref = ContentRef.LocalUrl(url, 0), title = title), false)
            },
            onPlayHubCatchup = { target, entry ->
                guideTarget = null
                onPlayHubCatchup(source, target, entry)
            },
            onSignIn = { onSignIn(source) },
            onUnavailable = {
                scope.launch {
                    snackbar.showSnackbar(
                        resources.getString(R.string.guide_catchup_unavailable),
                    )
                }
            },
        )
    }
}

@Composable
private fun KindSectionTitle(
    title: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .focusHighlight(RoundedCornerShape(10.dp))
            .clickable(onClick = onToggle)
            // Breathing room above, so one kind's heading is not crowded by the previous
            // kind's last row, and below, so the heading does not sit on its own cards.
            .padding(top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$title · $count",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        Icon(
            if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = stringResource(
                if (expanded) R.string.common_collapse else R.string.common_expand,
            ),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SourceFilterChips(
    sections: List<FavoritesSection>,
    selected: SourceId?,
    onSelect: (SourceId?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selected == null,
            onClick = { onSelect(null) },
            label = { Text(stringResource(R.string.favorites_filter_all)) },
            modifier = Modifier.focusHighlight(),
        )
        sections.forEach { section ->
            FilterChip(
                selected = selected == section.source,
                onClick = { onSelect(section.source) },
                label = { Text(section.title) },
                modifier = Modifier.focusHighlight(),
            )
        }
    }
}

@Composable
private fun SectionFailure(error: CatalogLoadError, onRetry: () -> Unit, onSignIn: () -> Unit) {
    Column(Modifier.padding(vertical = 8.dp)) {
        when (error) {
            CatalogLoadError.SignedOut -> SourceSignedOut(onSignIn = onSignIn)
            CatalogLoadError.Unreachable -> SourceUnreachable(onRetry = onRetry)
            is CatalogLoadError.Failed -> SourceLoadFailed(message = null, onRetry = onRetry)
        }
    }
}
