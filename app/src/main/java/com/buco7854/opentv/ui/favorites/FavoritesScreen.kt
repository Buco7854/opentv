package com.buco7854.opentv.ui.favorites

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.buco7854.opentv.OpenTvApp
import com.buco7854.opentv.R
import com.buco7854.opentv.core.model.Channel
import com.buco7854.opentv.core.model.ChannelKind
import com.buco7854.opentv.core.model.Download
import com.buco7854.opentv.core.model.DownloadStatus
import com.buco7854.opentv.core.repo.FavoriteRef
import com.buco7854.opentv.core.model.Programme
import com.buco7854.opentv.core.model.SeriesGroup
import com.buco7854.opentv.core.model.XtreamSeries
import com.buco7854.opentv.core.model.hasCatchup
import com.buco7854.opentv.core.model.hasGuide
import com.buco7854.opentv.core.repo.xtreamFavoriteKey
import com.buco7854.opentv.ui.components.EmptyState
import com.buco7854.opentv.ui.components.GuideSheet
import com.buco7854.opentv.ui.components.MediaListRow
import com.buco7854.opentv.ui.components.OtvTextButton
import com.buco7854.opentv.ui.components.PosterCard
import com.buco7854.opentv.ui.components.PosterItem
import com.buco7854.opentv.ui.components.combinedPadding
import com.buco7854.opentv.ui.components.kindIcon
import com.buco7854.opentv.ui.components.mediaTags
import com.buco7854.opentv.ui.components.playlistViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class FavoritesViewModel(app: Application, private val playlistId: Long) : AndroidViewModel(app) {

    private val graph = OpenTvApp.graph

    private val favorites = graph.favorites.observeAll(playlistId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private fun channelsOfKind(kind: Int): StateFlow<List<Channel>> =
        favorites.flatMapLatest { favs ->
            val urls = favs.filter { it.kind == kind }.map { it.key }
            if (urls.isEmpty()) flowOf(emptyList())
            else graph.storage.channels.observeByUrls(playlistId, kind, urls.take(900))
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val live = channelsOfKind(ChannelKind.LIVE)
    val movies = channelsOfKind(ChannelKind.MOVIE)

    val m3uSeries: StateFlow<List<SeriesGroup>> = favorites.flatMapLatest { favs ->
        val keys = favs.filter { it.kind == ChannelKind.SERIES && !it.key.startsWith("x:") }
            .map { it.key }.toSet()
        if (keys.isEmpty()) flowOf(emptyList())
        else graph.storage.channels.observeAllSeries(playlistId)
            .map { list -> list.filter { it.seriesKey in keys } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val xtreamSeries: StateFlow<List<XtreamSeries>> = favorites.flatMapLatest { favs ->
        val ids = favs.filter { it.key.startsWith("x:") }
            .mapNotNull { it.key.removePrefix("x:").toLongOrNull() }.toSet()
        if (ids.isEmpty()) flowOf(emptyList())
        else graph.storage.xtreamSeries.observeAll(playlistId)
            .map { list -> list.filter { it.seriesId in ids } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** tvg ids with programme data, for the guide affordance on live rows. */
    val guideIds: StateFlow<Set<String>> = graph.epg.observeGuideIds(playlistId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    /** url -> active download, for the live progress icon on movie rows. */
    val downloadsByUrl: StateFlow<Map<String, Download>> = graph.downloads.downloads
        .map { list ->
            list.filter { it.status != DownloadStatus.CANCELLED && it.status != DownloadStatus.FAILED }
                .associateBy { it.url }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** tvgId -> programme currently on air (local DB only, no network). */
    private val _nowAiring = MutableStateFlow<Map<String, Programme>>(emptyMap())
    val nowAiring: StateFlow<Map<String, Programme>> = _nowAiring

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message
    fun consumeMessage() { _message.value = null }

    fun reloadNowAiring() {
        viewModelScope.launch { _nowAiring.value = graph.epg.nowAiring(playlistId) }
    }

    init {
        reloadNowAiring()
    }

    /** Same persisted view-mode preference as browse. */
    val gridView: StateFlow<Boolean> = graph.playerPrefs.settings
        .map { it.gridBrowse }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun toggleGridView() {
        viewModelScope.launch {
            val current = graph.playerPrefs.settings.first()
            graph.playerPrefs.save(current.copy(gridBrowse = !current.gridBrowse))
        }
    }

    fun download(channel: Channel) {
        viewModelScope.launch {
            val blocked = graph.downloads.enqueue(channel)
            _message.value = blocked
                ?: getApplication<Application>().getString(R.string.downloads_started, channel.name)
        }
    }

    fun remove(key: String) {
        viewModelScope.launch { graph.favorites.remove(playlistId, key) }
    }

    fun removeMany(favs: List<FavRef>) {
        viewModelScope.launch {
            graph.favorites.removeAll(playlistId, favs.map { FavoriteRef(it.key, it.kind) })
        }
    }

    /** Re-add favorites (for Undo). */
    fun readd(favs: List<FavRef>) {
        viewModelScope.launch {
            graph.favorites.restoreAll(playlistId, favs.map { FavoriteRef(it.key, it.kind) })
        }
    }
}

/** A favorite's identity for selection: its store key and kind. */
data class FavRef(val key: String, val kind: Int)

/** Dedicated favorites page: explicit destination instead of a hidden filter. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    playlistId: Long,
    onBack: () -> Unit,
    onPlay: (url: String, title: String, tvgId: String?, live: Boolean) -> Unit,
    onOpenMovie: (channelId: Long) -> Unit,
    onOpenSeries: (seriesKey: String) -> Unit,
    onOpenXtreamSeries: (seriesId: Long) -> Unit,
) {
    val viewModel = playlistViewModel(playlistId, ::FavoritesViewModel)
    val live by viewModel.live.collectAsStateWithLifecycle()
    val movies by viewModel.movies.collectAsStateWithLifecycle()
    val m3uSeries by viewModel.m3uSeries.collectAsStateWithLifecycle()
    val xtreamSeries by viewModel.xtreamSeries.collectAsStateWithLifecycle()
    val gridView by viewModel.gridView.collectAsStateWithLifecycle()
    val guideIds by viewModel.guideIds.collectAsStateWithLifecycle()
    val downloadsByUrl by viewModel.downloadsByUrl.collectAsStateWithLifecycle()
    val nowAiring by viewModel.nowAiring.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var guideChannel by remember { mutableStateOf<Channel?>(null) }
    val expandedSections = remember { mutableStateMapOf<String, Boolean>() }
    fun expanded(key: String) = expandedSections.getOrDefault(key, true)

    // Multi-select: id -> favorite ref.
    val selected = remember { mutableStateMapOf<String, FavRef>() }
    var selectMode by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val removedLabel = stringResource(R.string.favorites_removed)
    val undoLabel = stringResource(R.string.common_undo)

    // Every favorite as selectionId -> FavRef, for select-all and counts.
    val allFavs = remember(live, movies, xtreamSeries, m3uSeries) {
        buildMap {
            live.forEach { put("l${it.id}", FavRef(it.url, ChannelKind.LIVE)) }
            movies.forEach { put("m${it.id}", FavRef(it.url, ChannelKind.MOVIE)) }
            xtreamSeries.forEach { put("x${it.seriesId}", FavRef(xtreamFavoriteKey(it.seriesId), ChannelKind.SERIES)) }
            m3uSeries.forEach { put("s${it.seriesKey}", FavRef(it.seriesKey, ChannelKind.SERIES)) }
        }
    }
    fun exitSelect() { selectMode = false; selected.clear() }
    fun toggle(id: String, ref: FavRef) { if (selected.remove(id) == null) selected[id] = ref }
    // Long-press enters selection mode with that item already selected.
    fun startSelect(id: String, ref: FavRef) { selectMode = true; selected[id] = ref }

    // Unfavorite with a 5s Undo snackbar that re-adds exactly what was removed.
    fun removeWithUndo(favs: List<FavRef>) {
        viewModel.removeMany(favs)
        scope.launch {
            val result = snackbar.showSnackbar(
                message = removedLabel,
                actionLabel = undoLabel,
                duration = androidx.compose.material3.SnackbarDuration.Short,
            )
            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) viewModel.readd(favs)
        }
    }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    // Keep "now airing" rows fresh (local DB query, no network).
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            viewModel.reloadNowAiring()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selectMode) stringResource(R.string.favorites_selected_count, selected.size)
                        else stringResource(R.string.common_favorites),
                    )
                },
                navigationIcon = {
                    if (selectMode) {
                        IconButton(onClick = { exitSelect() }) {
                            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.common_cancel))
                        }
                    } else {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back))
                        }
                    }
                },
                actions = {
                    if (selectMode) {
                        IconButton(onClick = {
                            if (selected.size == allFavs.size) selected.clear()
                            else { selected.clear(); selected.putAll(allFavs) }
                        }) {
                            Icon(Icons.Outlined.DoneAll, contentDescription = stringResource(R.string.favorites_select_all))
                        }
                        IconButton(onClick = { confirmDelete = true }, enabled = selected.isNotEmpty()) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = stringResource(R.string.favorites_remove_selected),
                                tint = if (selected.isNotEmpty()) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        IconButton(onClick = { selectMode = true }) {
                            Icon(Icons.Outlined.Checklist, contentDescription = stringResource(R.string.favorites_select))
                        }
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
        if (live.isEmpty() && movies.isEmpty() && m3uSeries.isEmpty() && xtreamSeries.isEmpty()) {
            Column(Modifier.padding(padding).fillMaxSize()) {
                EmptyState(
                    stringResource(R.string.favorites_empty_title),
                    stringResource(R.string.favorites_empty_subtitle),
                )
            }
            return@Scaffold
        }

        val liveLabel = stringResource(R.string.common_live)
        val moviesLabel = stringResource(R.string.common_movies)
        val seriesLabel = stringResource(R.string.common_series)
        if (gridView) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 112.dp),
                contentPadding = combinedPadding(padding, PaddingValues(16.dp)),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                fun LazyGridScope.gridSection(
                    key: String,
                    title: String,
                    count: Int,
                    content: LazyGridScope.() -> Unit,
                ) {
                    if (count == 0) return
                    item(key = "h-$key", span = { GridItemSpan(maxLineSpan) }) {
                        SectionHeader(title, count, expanded(key)) {
                            expandedSections[key] = !expanded(key)
                        }
                    }
                    if (expanded(key)) content()
                }
                gridSection("live", liveLabel, live.size) {
                    items(live, key = { "l${it.id}" }) { channel ->
                        val id = "l${channel.id}"
                        val ref = FavRef(channel.url, ChannelKind.LIVE)
                        PosterCard(
                            item = PosterItem(
                                id, channel.logo, channel.name, channel.groupTitle,
                                tags = mediaTags(channel.name, 1),
                            ),
                            fallback = kindIcon(ChannelKind.LIVE),
                            onClick = { if (selectMode) toggle(id, ref) else onPlay(channel.url, channel.name, channel.tvgId, true) },
                            onLongClick = { startSelect(id, ref) },
                            selected = if (selectMode) selected.containsKey(id) else null,
                        )
                    }
                }
                gridSection("movies", moviesLabel, movies.size) {
                    items(movies, key = { "m${it.id}" }) { channel ->
                        val id = "m${channel.id}"
                        val ref = FavRef(channel.url, ChannelKind.MOVIE)
                        PosterCard(
                            item = PosterItem(
                                id, channel.logo, channel.name, null,
                                tags = mediaTags(channel.name, 1),
                            ),
                            fallback = kindIcon(ChannelKind.MOVIE),
                            onClick = { if (selectMode) toggle(id, ref) else onOpenMovie(channel.id) },
                            onLongClick = { startSelect(id, ref) },
                            selected = if (selectMode) selected.containsKey(id) else null,
                        )
                    }
                }
                gridSection("series", seriesLabel, m3uSeries.size + xtreamSeries.size) {
                    items(xtreamSeries, key = { "x${it.seriesId}" }) { series ->
                        val id = "x${series.seriesId}"
                        val ref = FavRef(xtreamFavoriteKey(series.seriesId), ChannelKind.SERIES)
                        PosterCard(
                            item = PosterItem(
                                id, series.cover, series.name,
                                series.genre ?: series.categoryName,
                            ),
                            fallback = kindIcon(ChannelKind.SERIES),
                            onClick = { if (selectMode) toggle(id, ref) else onOpenXtreamSeries(series.seriesId) },
                            onLongClick = { startSelect(id, ref) },
                            selected = if (selectMode) selected.containsKey(id) else null,
                        )
                    }
                    items(m3uSeries, key = { "s${it.seriesKey}" }) { series ->
                        val id = "s${series.seriesKey}"
                        val ref = FavRef(series.seriesKey, ChannelKind.SERIES)
                        PosterCard(
                            item = PosterItem(
                                id, series.logo, series.seriesKey,
                                pluralStringResource(R.plurals.details_episode_count, series.count, series.count),
                            ),
                            fallback = kindIcon(ChannelKind.SERIES),
                            onClick = { if (selectMode) toggle(id, ref) else onOpenSeries(series.seriesKey) },
                            onLongClick = { startSelect(id, ref) },
                            selected = if (selectMode) selected.containsKey(id) else null,
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                contentPadding = combinedPadding(padding, PaddingValues(16.dp)),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (live.isNotEmpty()) {
                    item(key = "h-live") {
                        SectionHeader(stringResource(R.string.common_live), live.size, expanded("live")) {
                            expandedSections["live"] = !expanded("live")
                        }
                    }
                    if (expanded("live")) {
                        items(live.size, key = { "l${live[it].id}" }) { index ->
                            val channel = live[index]
                            val id = "l${channel.id}"
                            val ref = FavRef(channel.url, ChannelKind.LIVE)
                            val airing = channel.tvgId?.let { nowAiring[it] }
                            val progress = airing?.let {
                                ((System.currentTimeMillis() - it.startMs).toFloat() /
                                    (it.endMs - it.startMs).coerceAtLeast(1)).coerceIn(0f, 1f)
                            }
                            MediaListRow(
                                title = channel.name,
                                logo = channel.logo,
                                fallbackKind = ChannelKind.LIVE,
                                onClick = { if (selectMode) toggle(id, ref) else onPlay(channel.url, channel.name, channel.tvgId, true) },
                                onLongClick = { startSelect(id, ref) },
                                subtitle = channel.groupTitle,
                                titleTags = mediaTags(channel.name, 1),
                                nowAiringTitle = airing?.title,
                                nowAiringProgress = progress,
                                isFavorite = true,
                                onToggleFavorite = { removeWithUndo(listOf(ref)) },
                                onGuide = if (channel.hasGuide(guideIds)) ({ guideChannel = channel }) else null,
                                guideHighlight = channel.hasCatchup,
                                selected = if (selectMode) selected.containsKey(id) else null,
                            )
                        }
                    }
                }
                if (movies.isNotEmpty()) {
                    item(key = "h-movies") {
                        SectionHeader(stringResource(R.string.common_movies), movies.size, expanded("movies")) {
                            expandedSections["movies"] = !expanded("movies")
                        }
                    }
                    if (expanded("movies")) {
                        items(movies.size, key = { "m${movies[it].id}" }) { index ->
                            val channel = movies[index]
                            val id = "m${channel.id}"
                            val ref = FavRef(channel.url, ChannelKind.MOVIE)
                            MediaListRow(
                                title = channel.name,
                                logo = channel.logo,
                                fallbackKind = ChannelKind.MOVIE,
                                onClick = { if (selectMode) toggle(id, ref) else onOpenMovie(channel.id) },
                                onLongClick = { startSelect(id, ref) },
                                subtitle = channel.groupTitle,
                                titleTags = mediaTags(channel.name, 1),
                                isFavorite = true,
                                onToggleFavorite = { removeWithUndo(listOf(ref)) },
                                downloadState = downloadsByUrl[channel.url],
                                onDownload = { viewModel.download(channel) },
                                selected = if (selectMode) selected.containsKey(id) else null,
                            )
                        }
                    }
                }
                if (m3uSeries.isNotEmpty() || xtreamSeries.isNotEmpty()) {
                    item(key = "h-series") {
                        SectionHeader(stringResource(R.string.common_series), m3uSeries.size + xtreamSeries.size, expanded("series")) {
                            expandedSections["series"] = !expanded("series")
                        }
                    }
                    if (expanded("series")) {
                        items(xtreamSeries.size, key = { "x${xtreamSeries[it].seriesId}" }) { index ->
                            val series = xtreamSeries[index]
                            val id = "x${series.seriesId}"
                            val ref = FavRef(xtreamFavoriteKey(series.seriesId), ChannelKind.SERIES)
                            MediaListRow(
                                title = series.name,
                                logo = series.cover,
                                fallbackKind = ChannelKind.SERIES,
                                onClick = { if (selectMode) toggle(id, ref) else onOpenXtreamSeries(series.seriesId) },
                                onLongClick = { startSelect(id, ref) },
                                subtitle = series.genre ?: series.categoryName,
                                isFavorite = true,
                                onToggleFavorite = { removeWithUndo(listOf(ref)) },
                                trailingChevron = true,
                                selected = if (selectMode) selected.containsKey(id) else null,
                            )
                        }
                        items(m3uSeries.size, key = { "s${m3uSeries[it].seriesKey}" }) { index ->
                            val series = m3uSeries[index]
                            val id = "s${series.seriesKey}"
                            val ref = FavRef(series.seriesKey, ChannelKind.SERIES)
                            MediaListRow(
                                title = series.seriesKey,
                                logo = series.logo,
                                fallbackKind = ChannelKind.SERIES,
                                onClick = { if (selectMode) toggle(id, ref) else onOpenSeries(series.seriesKey) },
                                onLongClick = { startSelect(id, ref) },
                                subtitle = pluralStringResource(R.plurals.details_episode_count, series.count, series.count),
                                isFavorite = true,
                                onToggleFavorite = { removeWithUndo(listOf(ref)) },
                                trailingChevron = true,
                                selected = if (selectMode) selected.containsKey(id) else null,
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        val favs = selected.values.toList()
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.favorites_remove_title)) },
            text = { Text(stringResource(R.string.favorites_remove_message, favs.size)) },
            confirmButton = {
                OtvTextButton(danger = true, onClick = {
                    confirmDelete = false
                    exitSelect()
                    removeWithUndo(favs)
                }) { Text(stringResource(R.string.favorites_remove_confirm)) }
            },
            dismissButton = {
                OtvTextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    guideChannel?.let { channel ->
        GuideSheet(
            channel = channel,
            hasEpgConfigured = true,
            onDismiss = { guideChannel = null },
            onPlayCatchup = { url, title ->
                guideChannel = null
                onPlay(url, title, null, false)
            },
            onUnavailable = {
                scope.launch { snackbar.showSnackbar(resources.getString(R.string.guide_catchup_unavailable)) }
            },
        )
    }
}

@Composable
private fun SectionHeader(title: String, count: Int, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$title · $count",
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
