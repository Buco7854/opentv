package com.buco7854.opentv.ui.browse

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.buco7854.opentv.R
import com.buco7854.opentv.core.model.Channel
import com.buco7854.opentv.core.model.ChannelKind
import com.buco7854.opentv.core.model.Download
import com.buco7854.opentv.core.model.Programme
import com.buco7854.opentv.core.model.XtreamSeries
import com.buco7854.opentv.core.repo.GuideEntry
import com.buco7854.opentv.core.model.hasCatchup
import com.buco7854.opentv.core.model.hasGuide
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
import com.buco7854.opentv.ui.components.OtvTextButton
import com.buco7854.opentv.ui.components.playlistViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    playlistId: Long,
    initialTab: Int? = null,
    initialGroup: String? = null,
    onPlay: (url: String, title: String, tvgId: String?, live: Boolean) -> Unit,
    onOpenMovie: (channelId: Long) -> Unit,
    onOpenSeries: (seriesKey: String) -> Unit,
    onOpenXtreamSeries: (seriesId: Long) -> Unit,
    onOpenAccount: () -> Unit,
) {
    val viewModel = playlistViewModel(playlistId, ::BrowseViewModel)

    // Seed the VM once so returning from player/detail keeps position.
    LaunchedEffect(Unit) { viewModel.seedFromRoute(initialTab, initialGroup) }

    val playlist by viewModel.playlist.collectAsState()
    val tab by viewModel.tab.collectAsState()
    val group by viewModel.group.collectAsState()
    val groups by viewModel.groups.collectAsState()
    val channels by viewModel.channels.collectAsState()
    val seriesGroups by viewModel.seriesGroups.collectAsState()
    val nowAiring by viewModel.nowAiring.collectAsState()
    val guideIds by viewModel.guideIds.collectAsState()
    val downloadsByUrl by viewModel.downloadsByUrl.collectAsState()
    val isXtreamNative by viewModel.isXtreamNative.collectAsState()
    val xtreamSeries by viewModel.xtreamSeries.collectAsState()
    val gridView by viewModel.gridView.collectAsState()
    val favoriteKeys by viewModel.favoriteKeys.collectAsState()
    val account by viewModel.account.collectAsState()
    val liveCount by viewModel.liveCount.collectAsState()
    val movieCount by viewModel.movieCount.collectAsState()
    val seriesCount by viewModel.seriesCount.collectAsState()
    val message by viewModel.message.collectAsState()

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var guideChannel by remember { mutableStateOf<Channel?>(null) }
    var correctingGroup by remember { mutableStateOf<String?>(null) }
    // Filters the currently-shown list (categories at the root, items inside one).
    var filter by remember { mutableStateOf("") }
    LaunchedEffect(group, tab) { filter = "" }

    // Download's progress notification needs a runtime grant on Android 13+.
    val context = LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* download proceeds either way; notification just hidden if denied */ }
    val download: (Channel) -> Unit = { channel ->
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        viewModel.download(channel)
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

    // Single-group playlists skip the pointless category level.
    LaunchedEffect(groups, tab) {
        if (group == null && groups.size == 1) {
            viewModel.group.value = groups.first().groupTitle
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
                            (if (singleGroup) null else group) ?: playlist?.name ?: stringResource(R.string.browse_fallback_title),
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
            val hasContent = groups.isNotEmpty() || channels.isNotEmpty() ||
                seriesGroups.isNotEmpty() || xtreamSeries.isNotEmpty()
            if (hasContent) {
                FilterField(
                    value = filter,
                    onValueChange = { filter = it },
                    placeholder = stringResource(if (group == null) R.string.browse_filter_categories else R.string.browse_filter_category),
                )
            }
            val f = filter.trim()
            fun matches(s: String) = f.isBlank() || s.contains(f, ignoreCase = true)

            when {
                group == null -> GroupList(
                    groups = groups.filter { matches(it.groupTitle) },
                    // Xtream categories come from the panel; only M3U guessing needs correcting.
                    onCorrect = if (isXtreamNative) null else ({ correctingGroup = it }),
                    onSelect = { viewModel.group.value = it },
                )

                // Native Xtream playlists list the panel's series catalog.
                tab == ChannelKind.SERIES && isXtreamNative -> {
                    val shown = xtreamSeries.filter { matches(it.name) }
                    if (gridView) {
                        PosterGrid(
                            items = shown.map {
                                PosterItem(it.seriesId.toString(), it.cover, it.name, it.genre)
                            },
                            fallback = kindIcon(ChannelKind.SERIES),
                            onClick = { id -> onOpenXtreamSeries(id.toLong()) },
                        )
                    } else {
                        XtreamSeriesList(
                            series = shown,
                            favoriteKeys = favoriteKeys,
                            onToggleFavorite = {
                                viewModel.toggleFavorite(
                                    BrowseViewModel.xtreamFavKey(it),
                                    ChannelKind.SERIES,
                                )
                            },
                            onSelect = { onOpenXtreamSeries(it) },
                        )
                    }
                }

                // Series open their own page (poster, season picker, episodes).
                tab == ChannelKind.SERIES -> {
                    val shown = seriesGroups.filter { matches(it.seriesKey) }
                    if (gridView) {
                        PosterGrid(
                            items = shown.map {
                                PosterItem(
                                    it.seriesKey, it.logo, it.seriesKey,
                                    pluralStringResource(R.plurals.details_episode_count, it.count, it.count),
                                )
                            },
                            fallback = kindIcon(ChannelKind.SERIES),
                            onClick = { onOpenSeries(it) },
                        )
                    } else {
                        SeriesList(
                            series = shown,
                            favoriteKeys = favoriteKeys,
                            onToggleFavorite = { viewModel.toggleFavorite(it, ChannelKind.SERIES) },
                            onSelect = { onOpenSeries(it) },
                        )
                    }
                }

                tab == ChannelKind.MOVIE && gridView -> PosterGrid(
                    items = channels.filter { matches(it.name) }.map {
                        PosterItem(it.id.toString(), it.logo, it.name, null, tags = mediaTags(it.name, 1))
                    },
                    fallback = kindIcon(ChannelKind.MOVIE),
                    onClick = { onOpenMovie(it.toLong()) },
                )

                // Movies open a detail page with play/download; live plays directly.
                else -> ChannelList(
                    channels = channels.filter { matches(it.name) },
                    nowAiring = if (tab == ChannelKind.LIVE) nowAiring else emptyMap(),
                    guideIds = guideIds,
                    downloadsByUrl = if (tab == ChannelKind.MOVIE) downloadsByUrl else emptyMap(),
                    favoriteKeys = favoriteKeys,
                    onToggleFavorite = { viewModel.toggleFavorite(it.url, it.kind) },
                    onPlay = {
                        if (tab == ChannelKind.MOVIE) onOpenMovie(it.id)
                        else onPlay(it.url, it.name, it.tvgId, true)
                    },
                    onDownload = if (tab == ChannelKind.MOVIE) download else null,
                    onGuide = if (tab == ChannelKind.LIVE) ({ guideChannel = it }) else null,
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

    guideChannel?.let { channel ->
        GuideSheet(
            channel = channel,
            hasEpgConfigured = playlist?.epgUrl != null,
            onDismiss = { guideChannel = null },
            onPlayCatchup = { url, title ->
                guideChannel = null
                onPlay(url, title, null, false)
            },
            onUnavailable = {
                scope.launch { snackbar.showSnackbar(context.getString(R.string.guide_catchup_unavailable)) }
            },
        )
    }
}

@Composable
private fun XtreamSeriesList(
    series: List<XtreamSeries>,
    favoriteKeys: Set<String>,
    onToggleFavorite: (Long) -> Unit,
    onSelect: (Long) -> Unit,
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
        items(series, key = { it.seriesId }) { item ->
            MediaListRow(
                title = item.name,
                subtitle = listOfNotNull(item.genre, item.rating?.let { "★ %.1f".format(it) })
                    .joinToString(" · ").takeIf { it.isNotEmpty() },
                logo = item.cover,
                fallbackKind = ChannelKind.SERIES,
                onClick = { onSelect(item.seriesId) },
                isFavorite = BrowseViewModel.xtreamFavKey(item.seriesId) in favoriteKeys,
                onToggleFavorite = { onToggleFavorite(item.seriesId) },
                trailingChevron = true,
            )
        }
    }
}

@Composable
private fun GroupList(
    groups: List<com.buco7854.opentv.core.model.GroupCount>,
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
        items(groups, key = { it.groupTitle }) { groupCount ->
            Card(
                onClick = { onSelect(groupCount.groupTitle) },
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
                        groupCount.groupTitle,
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
                        IconButton(onClick = { onCorrect(groupCount.groupTitle) }) {
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
    series: List<com.buco7854.opentv.core.model.SeriesGroup>,
    favoriteKeys: Set<String>,
    onToggleFavorite: (String) -> Unit,
    onSelect: (String) -> Unit,
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
        items(series, key = { it.seriesKey }) { item ->
            MediaListRow(
                title = item.seriesKey,
                subtitle = pluralStringResource(R.plurals.details_episode_count, item.count, item.count),
                logo = item.logo,
                fallbackKind = ChannelKind.SERIES,
                onClick = { onSelect(item.seriesKey) },
                isFavorite = item.seriesKey in favoriteKeys,
                onToggleFavorite = { onToggleFavorite(item.seriesKey) },
                trailingChevron = true,
            )
        }
    }
}

@Composable
private fun ChannelList(
    channels: List<Channel>,
    nowAiring: Map<String, Programme>,
    guideIds: Set<String>,
    downloadsByUrl: Map<String, Download>,
    favoriteKeys: Set<String>,
    onToggleFavorite: (Channel) -> Unit,
    onPlay: (Channel) -> Unit,
    onDownload: ((Channel) -> Unit)?,
    onGuide: ((Channel) -> Unit)?,
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
        items(channels, key = { it.id }) { channel ->
            ChannelRow(
                channel = channel,
                airing = channel.tvgId?.let { nowAiring[it] },
                downloadState = downloadsByUrl[channel.url],
                isFavorite = channel.url in favoriteKeys,
                onToggleFavorite = { onToggleFavorite(channel) },
                onPlay = { onPlay(channel) },
                onDownload = onDownload?.let { handler -> { handler(channel) } },
                onGuide = if (onGuide != null && channel.hasGuide(guideIds)) ({ onGuide(channel) }) else null,
            )
        }
    }
}

@Composable
private fun ChannelRow(
    channel: Channel,
    airing: Programme?,
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
        title = episodeTag + channel.name,
        logo = channel.logo,
        fallbackKind = channel.kind,
        onClick = onPlay,
        titleTags = mediaTags(channel.name, 1),
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
