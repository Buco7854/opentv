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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.buco7854.opentv.data.db.ChannelEntity
import com.buco7854.opentv.data.db.ChannelKind
import com.buco7854.opentv.data.db.DownloadEntity
import com.buco7854.opentv.data.db.ProgrammeEntity
import com.buco7854.opentv.data.db.XtreamSeriesEntity
import com.buco7854.opentv.data.repo.GuideEntry
import com.buco7854.opentv.data.repo.hasCatchup
import com.buco7854.opentv.ui.components.BadgeRow
import com.buco7854.opentv.ui.components.ChannelLogo
import com.buco7854.opentv.ui.components.DownloadStateIcon
import com.buco7854.opentv.ui.components.FavoriteIcon
import com.buco7854.opentv.ui.components.GuideSheet
import com.buco7854.opentv.ui.components.mediaTags
import com.buco7854.opentv.ui.components.PosterGrid
import com.buco7854.opentv.ui.components.PosterItem
import com.buco7854.opentv.ui.components.focusHighlight
import com.buco7854.opentv.ui.components.kindIcon
import com.buco7854.opentv.ui.components.EmptyState
import com.buco7854.opentv.ui.components.playlistViewModel
import com.buco7854.opentv.ui.theme.Mint
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
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onPlay: (url: String, title: String, tvgId: String?) -> Unit,
    onOpenMovie: (channelId: Long) -> Unit,
    onOpenSeries: (seriesKey: String) -> Unit,
    onOpenXtreamSeries: (seriesId: Long) -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenAccount: () -> Unit,
) {
    val viewModel = playlistViewModel(playlistId, ::BrowseViewModel)

    // Deep link from search ("open this category"): applied once on entry.
    LaunchedEffect(Unit) {
        if (initialTab != null) viewModel.selectTab(initialTab)
        if (initialGroup != null) viewModel.group.value = initialGroup
    }

    val playlist by viewModel.playlist.collectAsState()
    val tab by viewModel.tab.collectAsState()
    val group by viewModel.group.collectAsState()
    val groups by viewModel.groups.collectAsState()
    val channels by viewModel.channels.collectAsState()
    val seriesGroups by viewModel.seriesGroups.collectAsState()
    val nowAiring by viewModel.nowAiring.collectAsState()
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
    var guideChannel by remember { mutableStateOf<ChannelEntity?>(null) }
    var correctingGroup by remember { mutableStateOf<String?>(null) }

    // Downloads show a progress notification; on Android 13+ that needs a runtime grant.
    val context = LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* download proceeds either way; the notification is just hidden if denied */ }
    val download: (ChannelEntity) -> Unit = { channel ->
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

    // Keep "now airing" rows and their progress bars fresh during long
    // sessions. Local DB query only - no network involved.
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            viewModel.reloadNowAiring()
        }
    }

    // Playlists without categories collapse to one "Uncategorized" group:
    // skip the pointless category level and show the content directly.
    LaunchedEffect(groups, tab) {
        if (group == null && groups.size == 1) {
            viewModel.group.value = groups.first().groupTitle
        }
    }
    val singleGroup = groups.size == 1

    BackHandler {
        if (group != null && !singleGroup) viewModel.group.value = null else onBack()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            (if (singleGroup) null else group) ?: playlist?.name ?: "Browse",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        account?.let { info ->
                            val warn = info.maxConnections in 1..info.activeConnections
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable { viewModel.refreshAccount(force = true) },
                            ) {
                                Icon(
                                    Icons.Rounded.Person,
                                    contentDescription = null,
                                    tint = if (warn) MaterialTheme.colorScheme.error else Mint,
                                    modifier = Modifier.size(14.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "${info.activeConnections}/${info.maxConnections} connections",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (warn) MaterialTheme.colorScheme.error else Mint,
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (group != null && !singleGroup) viewModel.group.value = null else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenFavorites) {
                        Icon(Icons.Rounded.Favorite, contentDescription = "Favorites")
                    }
                    if (group != null && tab != ChannelKind.LIVE) {
                        IconButton(onClick = { viewModel.toggleGridView() }) {
                            Icon(
                                if (gridView) Icons.AutoMirrored.Rounded.ViewList else Icons.Rounded.GridView,
                                contentDescription = if (gridView) "List view" else "Grid view",
                            )
                        }
                    } else if (playlist?.xtreamBase != null) {
                        IconButton(onClick = onOpenAccount) {
                            Icon(Icons.Rounded.Person, contentDescription = "Account")
                        }
                    }
                    IconButton(onClick = onSearch) {
                        Icon(Icons.Rounded.Search, contentDescription = "Search")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(
                selectedTabIndex = tab,
                containerColor = MaterialTheme.colorScheme.background,
            ) {
                Tab(selected = tab == ChannelKind.LIVE, onClick = { viewModel.selectTab(ChannelKind.LIVE) },
                    text = { Text("Live · $liveCount") })
                Tab(selected = tab == ChannelKind.MOVIE, onClick = { viewModel.selectTab(ChannelKind.MOVIE) },
                    text = { Text("Movies · $movieCount") })
                Tab(selected = tab == ChannelKind.SERIES, onClick = { viewModel.selectTab(ChannelKind.SERIES) },
                    text = { Text("Series · $seriesCount") })
            }

            when {
                group == null -> GroupList(
                    groups = groups,
                    // Xtream categories come from the panel; only M3U guessing needs correcting.
                    onCorrect = if (isXtreamNative) null else ({ correctingGroup = it }),
                    onSelect = { viewModel.group.value = it },
                )

                // Native Xtream playlists list the panel's series catalog.
                tab == ChannelKind.SERIES && isXtreamNative ->
                    if (gridView) {
                        PosterGrid(
                            items = xtreamSeries.map {
                                PosterItem(it.seriesId.toString(), it.cover, it.name, it.genre)
                            },
                            fallback = kindIcon(ChannelKind.SERIES),
                            onClick = { id -> onOpenXtreamSeries(id.toLong()) },
                        )
                    } else {
                        XtreamSeriesList(
                            series = xtreamSeries,
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

                // Series open their own page (poster, season picker, episodes).
                tab == ChannelKind.SERIES ->
                    if (gridView) {
                        PosterGrid(
                            items = seriesGroups.map {
                                PosterItem(it.seriesKey, it.logo, it.seriesKey, "${it.count} episodes")
                            },
                            fallback = kindIcon(ChannelKind.SERIES),
                            onClick = { onOpenSeries(it) },
                        )
                    } else {
                        SeriesList(
                            series = seriesGroups,
                            favoriteKeys = favoriteKeys,
                            onToggleFavorite = { viewModel.toggleFavorite(it, ChannelKind.SERIES) },
                            onSelect = { onOpenSeries(it) },
                        )
                    }

                tab == ChannelKind.MOVIE && gridView -> PosterGrid(
                    items = channels.map {
                        PosterItem(it.id.toString(), it.logo, it.name, null, tags = mediaTags(it.name, 1))
                    },
                    fallback = kindIcon(ChannelKind.MOVIE),
                    onClick = { onOpenMovie(it.toLong()) },
                )

                // Movies open a detail page with play/download; live plays directly.
                else -> ChannelList(
                    channels = channels,
                    nowAiring = if (tab == ChannelKind.LIVE) nowAiring else emptyMap(),
                    downloadsByUrl = if (tab == ChannelKind.MOVIE) downloadsByUrl else emptyMap(),
                    favoriteKeys = favoriteKeys,
                    onToggleFavorite = { viewModel.toggleFavorite(it.url, it.kind) },
                    onPlay = {
                        if (tab == ChannelKind.MOVIE) onOpenMovie(it.id)
                        else onPlay(it.url, it.name, it.tvgId)
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
                onPlay(url, title, null)
            },
            onUnavailable = {
                scope.launch { snackbar.showSnackbar("Catch-up isn't available for this programme.") }
            },
        )
    }
}

@Composable
private fun XtreamSeriesList(
    series: List<XtreamSeriesEntity>,
    favoriteKeys: Set<String>,
    onToggleFavorite: (Long) -> Unit,
    onSelect: (Long) -> Unit,
) {
    if (series.isEmpty()) {
        EmptyState("No series found", "This category has no series.")
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(series, key = { it.seriesId }) { item ->
            Card(
                onClick = { onSelect(item.seriesId) },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.focusHighlight(RoundedCornerShape(16.dp)),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ChannelLogo(item.cover, kindIcon(ChannelKind.SERIES))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        item.genre?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    item.rating?.let {
                        Text(
                            "★ %.1f".format(it),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FavoriteIcon(
                        isFavorite = BrowseViewModel.xtreamFavKey(item.seriesId) in favoriteKeys,
                        onToggle = { onToggleFavorite(item.seriesId) },
                    )
                    Icon(
                        Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupList(
    groups: List<com.buco7854.opentv.data.db.GroupCount>,
    onCorrect: ((String) -> Unit)?,
    onSelect: (String) -> Unit,
) {
    if (groups.isEmpty()) {
        EmptyState("Nothing here yet", "This playlist has no items of this type.")
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
                        Icons.Rounded.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
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
                                Icons.Rounded.MoreVert,
                                contentDescription = "Correct category type",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        Icon(
                            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
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
                    "Wrongly classified? Tell the app what this whole category really contains. " +
                        "Remembered and re-applied at every refresh.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                listOf(
                    ChannelKind.LIVE to "Live TV",
                    ChannelKind.MOVIE to "Movies",
                    ChannelKind.SERIES to "Series",
                ).forEach { (kind, label) ->
                    androidx.compose.material3.TextButton(onClick = { onSelect(kind) }) { Text(label) }
                }
                androidx.compose.material3.TextButton(onClick = { onSelect(null) }) {
                    Text("Automatic (use detection)")
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun SeriesList(
    series: List<com.buco7854.opentv.data.db.SeriesGroup>,
    favoriteKeys: Set<String>,
    onToggleFavorite: (String) -> Unit,
    onSelect: (String) -> Unit,
) {
    if (series.isEmpty()) {
        EmptyState("No series found", "No episodes were detected in this category.")
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(series, key = { it.seriesKey }) { item ->
            Card(
                onClick = { onSelect(item.seriesKey) },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.focusHighlight(RoundedCornerShape(16.dp)),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ChannelLogo(item.logo, kindIcon(ChannelKind.SERIES))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.seriesKey,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${item.count} episodes",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FavoriteIcon(
                        isFavorite = item.seriesKey in favoriteKeys,
                        onToggle = { onToggleFavorite(item.seriesKey) },
                    )
                    Icon(
                        Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelList(
    channels: List<ChannelEntity>,
    nowAiring: Map<String, ProgrammeEntity>,
    downloadsByUrl: Map<String, DownloadEntity>,
    favoriteKeys: Set<String>,
    onToggleFavorite: (ChannelEntity) -> Unit,
    onPlay: (ChannelEntity) -> Unit,
    onDownload: ((ChannelEntity) -> Unit)?,
    onGuide: ((ChannelEntity) -> Unit)?,
) {
    if (channels.isEmpty()) {
        EmptyState("Empty category", "No items in this category.")
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
                onGuide = if (onGuide != null && channel.tvgId != null) ({ onGuide(channel) }) else null,
            )
        }
    }
}

@Composable
private fun ChannelRow(
    channel: ChannelEntity,
    airing: ProgrammeEntity?,
    downloadState: DownloadEntity?,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onPlay: () -> Unit,
    onDownload: (() -> Unit)?,
    onGuide: (() -> Unit)?,
) {
    Card(
        onClick = onPlay,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.focusHighlight(RoundedCornerShape(16.dp)),
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            ChannelLogo(channel.logo, kindIcon(channel.kind))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                val episodeTag = if (channel.season != null && channel.episode != null)
                    "S%02dE%02d · ".format(channel.season, channel.episode) else ""
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        episodeTag + channel.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    val tags = mediaTags(channel.name, 1)
                    if (tags.isNotEmpty()) {
                        Spacer(Modifier.width(6.dp))
                        BadgeRow(tags)
                    }
                }
                if (airing != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        airing.title,
                        style = MaterialTheme.typography.bodySmall,
                        color = Mint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    val progress = ((System.currentTimeMillis() - airing.startMs).toFloat() /
                        (airing.endMs - airing.startMs).coerceAtLeast(1)).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                }
            }
            FavoriteIcon(isFavorite = isFavorite, onToggle = onToggleFavorite)
            if (onGuide != null) {
                IconButton(onClick = onGuide) {
                    Icon(
                        Icons.Rounded.CalendarMonth,
                        contentDescription = if (channel.hasCatchup()) "Guide & catch-up" else "Guide",
                        // Mint marks channels with a replay archive.
                        tint = if (channel.hasCatchup()) Mint
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (onDownload != null) {
                DownloadStateIcon(state = downloadState, onDownload = onDownload)
            }
        }
    }
}
