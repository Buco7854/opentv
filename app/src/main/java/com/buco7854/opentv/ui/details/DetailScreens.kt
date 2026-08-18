package com.buco7854.opentv.ui.details

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import coil3.compose.AsyncImage
import com.buco7854.opentv.R
import com.buco7854.opentv.core.model.Download
import com.buco7854.opentv.core.model.DownloadStatus
import com.buco7854.opentv.core.model.Metadata
import com.buco7854.opentv.core.meta.decodeCast
import com.buco7854.opentv.download.downloadFor
import com.buco7854.opentv.download.downloadIdentityKey
import com.buco7854.opentv.source.CatalogItem
import com.buco7854.opentv.source.CatalogLoadError
import com.buco7854.opentv.source.ContentRef
import com.buco7854.opentv.source.SourceId
import com.buco7854.opentv.source.encode
import com.buco7854.opentv.ui.components.BadgeRow
import com.buco7854.opentv.ui.components.CastRow
import com.buco7854.opentv.ui.components.ChannelLogo
import com.buco7854.opentv.ui.components.DownloadStateIcon
import com.buco7854.opentv.ui.components.ExpandableText
import com.buco7854.opentv.ui.components.FavoriteIcon
import com.buco7854.opentv.ui.components.OtvButton
import com.buco7854.opentv.ui.components.OtvMenuDefaults
import com.buco7854.opentv.ui.components.OtvProgressBar
import com.buco7854.opentv.ui.components.Pill
import com.buco7854.opentv.ui.components.WatchProgressBar
import com.buco7854.opentv.ui.components.SourceLoadFailed
import com.buco7854.opentv.ui.components.SourceSignedOut
import com.buco7854.opentv.ui.components.SourceUnreachable
import com.buco7854.opentv.ui.components.focusHighlight
import com.buco7854.opentv.ui.components.mediaTags
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch

private val YEAR_TAG = Regex("""\b(19|20)\d{2}\b""")
private val QUALITY_TAG = Regex("""(?i)\b(4K|UHD|2160p|1080p|FHD|720p|HEVC|HD|SD)\b""")

/** Refresh retained detail progress exactly when this navigation destination is visible again. */
@Composable
internal fun RefreshProgressOnResume(viewModel: BaseDetailViewModel) {
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.onResumed()
            // Keep this repeat block active until the destination leaves RESUMED. Without
            // this suspension it would immediately restart and refresh in a tight loop.
            awaitCancellation()
        }
    }
}

/** Playlist facts plus any enrichment. */
private fun metaChips(channel: CatalogItem, meta: Metadata?): List<String> = buildList {
    channel.group?.let(::add)
    (meta?.year ?: YEAR_TAG.find(channel.title)?.value)?.let { add(it) }
    meta?.rating?.let { add("★ %.1f".format(it)) }
    meta?.infoLine?.split(" · ")?.take(2)?.forEach { add(it) }
}

@Composable
internal fun Poster(logo: String?, fallback: androidx.compose.ui.graphics.vector.ImageVector) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (logo.isNullOrBlank()) {
            Icon(
                fallback,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(64.dp),
            )
        } else {
            AsyncImage(
                model = logo,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(12.dp),
            )
        }
    }
}

@Composable
internal fun MetadataBlock(meta: Metadata?) {
    if (meta == null) return
    meta.overview?.let {
        Spacer(Modifier.height(14.dp))
        ExpandableText(it)
    }
    val cast = decodeCast(meta.castJson)
    if (cast.isNotEmpty()) {
        Spacer(Modifier.height(14.dp))
        Text(stringResource(R.string.details_cast), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(8.dp))
        CastRow(cast)
        // Non-cast credits (director, genre) still deserve a line.
        meta.castNames?.takeIf { !it.startsWith("Cast:") }?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        // Pre-labelled line: "Cast: A, B, C" (series) or "Director: X · Genre: Y" (movies).
        meta.castNames?.let {
            Spacer(Modifier.height(10.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovieDetailScreen(
    sourceId: SourceId,
    ref: ContentRef,
    onBack: () -> Unit,
    onPlay: (item: CatalogItem) -> Unit,
    onSignIn: () -> Unit,
) {
    val viewModel = detailViewModel<MovieDetailViewModel>(sourceId, ref) {
        MovieDetailViewModel(it, sourceId, ref)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val progressState by viewModel.progress.collectAsStateWithLifecycle()
    RefreshProgressOnResume(viewModel)
    val channel = state.detail?.item
    val meta = state.metadata
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current

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
                    channel?.let { c ->
                        FavoriteIcon(
                            isFavorite = state.isFavorite,
                            onToggle = viewModel::toggleFavorite,
                        )
                    }
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
        if (state.loading && channel == null) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.onSurface)
            }
            return@Scaffold
        }
        val movie = channel ?: return@Scaffold
        val downloadState = downloads.downloadFor(sourceId, movie.ref)
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
        ) {
            item {
                Poster(meta?.posterUrl ?: movie.imageUrl, Icons.Outlined.Movie)
                Spacer(Modifier.height(18.dp))
                Text(movie.title, style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(10.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    metaChips(movie, meta).take(3).forEach { Pill(it) }
                    BadgeRow(mediaTags(movie.title))
                }
                MetadataBlock(meta)
                Spacer(Modifier.height(24.dp))
                val progress = progressState.progressFor(movie)
                if (progress != null) {
                    WatchProgressBar(progress, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.details_percent_watched, (progress * 100).toInt()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OtvButton(
                        onClick = { onPlay(movie) },
                        modifier = Modifier.weight(1f).height(48.dp),
                    ) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(if (progress != null) R.string.common_resume else R.string.common_play))
                    }
                    if (sourceId is SourceId.LocalPlaylist || sourceId is SourceId.Hub) {
                        DownloadSlot(
                            state = downloadState,
                            onDownload = {
                                scope.launch {
                                    val blocked = viewModel.enqueue(movie)
                                    snackbar.showSnackbar(
                                        blocked
                                            ?: resources.getString(
                                                R.string.downloads_started_generic,
                                            ),
                                    )
                                }
                            },
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** Compact download slot next to Play, with the same state icon as list rows. */
@Composable
private fun DownloadSlot(state: Download?, onDownload: () -> Unit) {
    Box(
        Modifier
            .size(width = 60.dp, height = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        DownloadStateIcon(state = state, onDownload = onDownload)
    }
}

/**
 * Whether a series has finished loading and has nothing to list.
 *
 * Not merely "no episodes on screen": the server sends the full season list with an
 * empty first page, so an episode list that is still filling looks identical from the
 * rows alone. Only a total of zero means there is genuinely nothing.
 */
internal fun showsNoEpisodes(loading: Boolean, episodes: Int, episodeTotal: Int): Boolean =
    !loading && episodes == 0 && episodeTotal == 0

/**
 * A series with no episodes, which is otherwise a blank screen.
 *
 * The poster and title still render, so without this the page looks like it simply
 * forgot to draw the episode list, and there is nothing to report or retry. It happens
 * for real: a favorite whose playlist is mid-refresh has no episodes for a moment.
 */
@Composable
internal fun NoEpisodes(onRetry: () -> Unit) {
    Text(
        stringResource(R.string.details_no_episodes),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(10.dp))
    OtvButton(onClick = onRetry) { Text(stringResource(R.string.common_retry)) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesDetailScreen(
    sourceId: SourceId,
    ref: ContentRef,
    seriesKey: String?,
    onBack: () -> Unit,
    onOpenEpisode: (ContentRef) -> Unit,
    onSignIn: () -> Unit,
) {
    val viewModel = detailViewModel<SeriesDetailViewModel>(sourceId, ref) {
        SeriesDetailViewModel(it, sourceId, ref, seriesKey)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val episodes by viewModel.episodes.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val progressState by viewModel.progress.collectAsStateWithLifecycle()
    RefreshProgressOnResume(viewModel)
    val meta = state.metadata
    val detail = state.detail
    val seriesTitle = detail?.item?.title.orEmpty()
    val downloadsByUrl = remember(downloads) {
        downloads.filter { it.status != DownloadStatus.CANCELLED && it.status != DownloadStatus.FAILED }
            .associateBy { it.downloadIdentityKey() }
    }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current

    val seasons = state.seasons.ifEmpty {
        episodes.mapNotNull { it.season }.distinct().sorted()
    }
    // Saveable so the chosen season survives process death, and the config changes
    // this Activity does not declare: it handles orientation itself, but not a
    // theme or locale switch, which still recreates it.
    var selectedSeason by rememberSaveable { mutableStateOf<Int?>(null) } // null = all seasons
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
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.onSurface)
            }
            return@Scaffold
        }
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
        ) {
            item {
                Poster(
                    meta?.posterUrl ?: detail?.item?.imageUrl
                    ?: episodes.firstOrNull { it.imageUrl != null }?.imageUrl,
                    Icons.Outlined.VideoLibrary,
                )
                Spacer(Modifier.height(18.dp))
                Text(seriesTitle, style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (detail?.item?.group ?: episodes.firstOrNull()?.group)?.let { Pill(it) }
                    Pill(
                        pluralStringResource(
                            R.plurals.details_episode_count,
                            state.episodeTotal,
                            state.episodeTotal,
                        ),
                    )
                    if (seasons.size > 1) {
                        Pill(
                            pluralStringResource(
                                R.plurals.details_season_count,
                                seasons.size,
                                seasons.size,
                            ),
                        )
                    }
                    meta?.rating?.let { Pill("★ %.1f".format(it)) }
                }
                meta?.infoLine?.let { line ->
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        line.split(" · ").take(4).forEach { Pill(it) }
                    }
                }
                MetadataBlock(meta)
                Spacer(Modifier.height(18.dp))
                if (showsNoEpisodes(state.loading, episodes.size, state.episodeTotal)) {
                    NoEpisodes(viewModel::retry)
                } else if (seasons.isNotEmpty()) {
                    SeasonPicker(
                        seasons = seasons,
                        selected = selectedSeason,
                        onSelect = { selectedSeason = it },
                    )
                    Spacer(Modifier.height(10.dp))
                }
            }
            items(shown, key = { it.ref.encode() }) { episode ->
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
                    progress = progressState.progressFor(episode),
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SeasonPicker(seasons: List<Int>, selected: Int?, onSelect: (Int?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected?.let { stringResource(R.string.details_season_n, it) } ?: stringResource(R.string.details_all_seasons),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.details_season)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = OtvMenuDefaults.shape,
            containerColor = OtvMenuDefaults.containerColor,
            border = OtvMenuDefaults.border,
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.details_all_seasons)) },
                onClick = { onSelect(null); expanded = false },
            )
            seasons.forEach { season ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.details_season_n, season)) },
                    onClick = { onSelect(season); expanded = false },
                )
            }
        }
    }
}

internal fun episodeTag(episode: CatalogItem): String? = when {
    episode.season != null && episode.episode != null ->
        "S%02dE%02d".format(episode.season, episode.episode)
    episode.episode != null -> "EP %d".format(episode.episode)
    else -> null
}

internal fun formatDuration(secs: Int): String {
    val minutes = secs / 60
    return if (minutes >= 60) "%dh %02dmin".format(minutes / 60, minutes % 60) else "$minutes min"
}

@Composable
private fun EpisodeThumb(image: String?, progress: Float? = null, modifier: Modifier = Modifier) {
    Box(
        modifier
            .width(116.dp)
            .height(66.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (image.isNullOrBlank()) {
            Icon(
                Icons.Outlined.VideoLibrary,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            AsyncImage(
                model = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // "Continue watching" bar across the bottom of the still.
        if (progress != null) {
            WatchProgressBar(
                progress,
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 5.dp)
                    .padding(bottom = 5.dp),
                height = 3.dp,
            )
        }
    }
}

@Composable
internal fun EpisodeRow(
    episode: CatalogItem,
    downloadState: Download?,
    onOpen: () -> Unit,
    onDownload: (() -> Unit)?,
    progress: Float? = null,
) {
    Card(
        onClick = onOpen,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.padding(vertical = 4.dp).focusHighlight(),
    ) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            EpisodeThumb(episode.imageUrl, progress = progress)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                episodeTag(episode)?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    episode.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val metaLine = listOfNotNull(
                    episode.durationSecs?.let { formatDuration(it) },
                    episode.airDate,
                ).joinToString(" · ")
                if (metaLine.isNotEmpty()) {
                    Text(
                        metaLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (onDownload != null) {
                DownloadStateIcon(state = downloadState, onDownload = onDownload)
            }
        }
    }
}

/** Full episode page. Uses stored Xtream panel data when present, else lazily enriches from TVMaze (cached). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpisodeDetailScreen(
    sourceId: SourceId,
    ref: ContentRef,
    onBack: () -> Unit,
    onPlay: (item: CatalogItem) -> Unit,
    onSignIn: () -> Unit,
) {
    val viewModel = detailViewModel<EpisodeDetailViewModel>(sourceId, ref) {
        EpisodeDetailViewModel(it, sourceId, ref)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val progressState by viewModel.progress.collectAsStateWithLifecycle()
    RefreshProgressOnResume(viewModel)
    val episode = state.detail?.item
    val seriesTitle = state.seriesTitle
    val info = state.metadata
    val seriesCast = state.seriesCast
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current

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
        if (state.loading && episode == null) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.onSurface)
            }
            return@Scaffold
        }
        val ep = episode ?: return@Scaffold
        val downloadState = downloads.downloadFor(sourceId, ep.ref)
        val image = info?.posterUrl ?: ep.imageUrl
        val plot = state.detail?.description ?: info?.overview

        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
        ) {
            item {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    if (image.isNullOrBlank()) {
                        Icon(
                            Icons.Outlined.VideoLibrary,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(64.dp),
                        )
                    } else {
                        AsyncImage(
                            model = image,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                Spacer(Modifier.height(18.dp))
                seriesTitle?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(info?.title ?: ep.title, style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    episodeTag(ep)?.let { Pill(it) }
                    (ep.airDate ?: info?.year)?.let { Pill(it) }
                    (ep.durationSecs?.let { formatDuration(it) } ?: info?.infoLine)?.let { Pill(it) }
                    info?.rating?.let { Pill("★ %.1f".format(it)) }
                }
                plot?.let {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (seriesCast.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        stringResource(R.string.details_cast),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(8.dp))
                    CastRow(seriesCast)
                }
                Spacer(Modifier.height(24.dp))
                val progress = progressState.progressFor(ep)
                if (progress != null) {
                    WatchProgressBar(progress, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.details_percent_watched, (progress * 100).toInt()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OtvButton(
                        onClick = { onPlay(ep) },
                        modifier = Modifier.weight(1f).height(48.dp),
                    ) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(if (progress != null) R.string.common_resume else R.string.common_play))
                    }
                    if (sourceId is SourceId.LocalPlaylist || sourceId is SourceId.Hub) {
                        DownloadSlot(
                            state = downloadState,
                            onDownload = {
                                scope.launch {
                                    val blocked = viewModel.enqueue(ep)
                                    snackbar.showSnackbar(
                                        blocked
                                            ?: resources.getString(
                                                R.string.downloads_started_generic,
                                            ),
                                    )
                                }
                            },
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
