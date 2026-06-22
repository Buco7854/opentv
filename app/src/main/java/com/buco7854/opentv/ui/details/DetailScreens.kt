package com.buco7854.opentv.ui.details

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.buco7854.opentv.OpenTvApp
import com.buco7854.opentv.data.db.ChannelEntity
import com.buco7854.opentv.data.db.ChannelKind
import com.buco7854.opentv.data.db.DownloadEntity
import com.buco7854.opentv.data.db.DownloadStatus
import com.buco7854.opentv.data.db.FavoriteEntity
import com.buco7854.opentv.data.db.MetadataEntity
import com.buco7854.opentv.data.meta.castFromNames
import com.buco7854.opentv.data.meta.decodeCast
import com.buco7854.opentv.ui.components.BadgeRow
import com.buco7854.opentv.ui.components.CastRow
import com.buco7854.opentv.ui.components.ChannelLogo
import com.buco7854.opentv.ui.components.DownloadStateIcon
import com.buco7854.opentv.ui.components.FavoriteIcon
import com.buco7854.opentv.ui.components.Pill
import com.buco7854.opentv.ui.components.WatchProgressBar
import com.buco7854.opentv.ui.components.focusHighlight
import com.buco7854.opentv.ui.components.mediaTags
import kotlinx.coroutines.launch

private val YEAR_TAG = Regex("""\b(19|20)\d{2}\b""")
private val QUALITY_TAG = Regex("""(?i)\b(4K|UHD|2160p|1080p|FHD|720p|HEVC|HD|SD)\b""")

/** Flips the favorite for [key]; returns the new state. */
internal suspend fun toggleFavorite(playlistId: Long, key: String, kind: Int, current: Boolean): Boolean {
    val dao = OpenTvApp.graph.db.favoriteDao()
    if (current) dao.remove(playlistId, key)
    else dao.add(FavoriteEntity(playlistId = playlistId, key = key, kind = kind))
    return !current
}

/** Facts from the playlist entry plus whatever enrichment found. */
private fun metaChips(channel: ChannelEntity, meta: MetadataEntity?): List<String> = buildList {
    add(channel.groupTitle)
    (meta?.year ?: YEAR_TAG.find(channel.name)?.value)?.let { add(it) }
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
internal fun MetadataBlock(meta: MetadataEntity?) {
    if (meta == null) return
    meta.overview?.let {
        Spacer(Modifier.height(14.dp))
        Text(
            it,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 6,
            overflow = TextOverflow.Ellipsis,
        )
    }
    val cast = decodeCast(meta.castJson)
    if (cast.isNotEmpty()) {
        Spacer(Modifier.height(14.dp))
        Text("Cast", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
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
    channelId: Long,
    onBack: () -> Unit,
    onPlay: (url: String, title: String) -> Unit,
) {
    var channel by remember { mutableStateOf<ChannelEntity?>(null) }
    var meta by remember { mutableStateOf<MetadataEntity?>(null) }
    var isFavorite by remember { mutableStateOf(false) }
    val downloads by OpenTvApp.graph.downloads.downloads.collectAsState(initial = emptyList())
    val progressByUrl by OpenTvApp.graph.resume.progressByUrl.collectAsState(initial = emptyMap())
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(channelId) {
        channel = OpenTvApp.graph.db.channelDao().get(channelId)
        channel?.let { c ->
            isFavorite = OpenTvApp.graph.db.favoriteDao().get(c.playlistId, c.url) != null
            // Xtream movies have panel-provided details; keyless lookups are the fallback.
            meta = c.xtreamStreamId?.let { OpenTvApp.graph.xtream.vodMetadata(c) }
                ?: OpenTvApp.graph.metadata.forTitle(isSeries = false, rawName = c.name)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    channel?.let { c ->
                        FavoriteIcon(isFavorite = isFavorite) {
                            scope.launch {
                                isFavorite = toggleFavorite(c.playlistId, c.url, c.kind, isFavorite)
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        val movie = channel ?: return@Scaffold
        val downloadState = downloads.firstOrNull {
            it.url == movie.url && it.status != DownloadStatus.CANCELLED && it.status != DownloadStatus.FAILED
        }
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
        ) {
            item {
                Poster(meta?.posterUrl ?: movie.logo, Icons.Rounded.Movie)
                Spacer(Modifier.height(18.dp))
                Text(movie.name, style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(10.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    metaChips(movie, meta).take(3).forEach { Pill(it) }
                    BadgeRow(mediaTags(movie.name))
                }
                MetadataBlock(meta)
                Spacer(Modifier.height(24.dp))
                val progress = progressByUrl[movie.url]
                if (progress != null) {
                    WatchProgressBar(progress, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${(progress * 100).toInt()}% watched",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                }
                Button(
                    onClick = { onPlay(movie.url, movie.name) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (progress != null) "Resume" else "Play")
                }
                Spacer(Modifier.height(10.dp))
                DownloadButton(
                    state = downloadState,
                    onDownload = {
                        scope.launch {
                            val blocked = OpenTvApp.graph.downloads.enqueue(movie)
                            snackbar.showSnackbar(blocked ?: "Download started")
                        }
                    },
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** Full-width download button reflecting live state: idle / progress / done. */
@Composable
private fun DownloadButton(state: DownloadEntity?, onDownload: () -> Unit) {
    when (state?.status) {
        DownloadStatus.RUNNING, DownloadStatus.QUEUED -> FilledTonalButton(
            onClick = {},
            enabled = false,
            modifier = Modifier.fillMaxWidth(),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            val percent = if (state.totalBytes > 0) {
                " ${(state.downloadedBytes * 100 / state.totalBytes).toInt()}%"
            } else ""
            Text(if (state.status == DownloadStatus.QUEUED) "Queued…" else "Downloading…$percent")
        }

        DownloadStatus.DONE -> FilledTonalButton(
            onClick = {},
            enabled = false,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Rounded.DownloadDone, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Downloaded, see Downloads")
        }

        else -> FilledTonalButton(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.Download, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Download for offline viewing")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesDetailScreen(
    playlistId: Long,
    seriesKey: String,
    onBack: () -> Unit,
    onOpenEpisode: (channelId: Long) -> Unit,
) {
    val episodes by OpenTvApp.graph.db.channelDao()
        .observeEpisodes(playlistId, seriesKey)
        .collectAsState(initial = emptyList())
    var meta by remember { mutableStateOf<MetadataEntity?>(null) }
    var isFavorite by remember { mutableStateOf(false) }
    val downloads by OpenTvApp.graph.downloads.downloads.collectAsState(initial = emptyList())
    val downloadsByUrl = remember(downloads) {
        downloads.filter { it.status != DownloadStatus.CANCELLED && it.status != DownloadStatus.FAILED }
            .associateBy { it.url }
    }
    val progressByUrl by OpenTvApp.graph.resume.progressByUrl.collectAsState(initial = emptyMap())
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(seriesKey) {
        isFavorite = OpenTvApp.graph.db.favoriteDao().get(playlistId, seriesKey) != null
        meta = OpenTvApp.graph.metadata.forTitle(isSeries = true, rawName = seriesKey)
    }

    val seasons = remember(episodes) { episodes.mapNotNull { it.season }.distinct().sorted() }
    var selectedSeason by remember { mutableStateOf<Int?>(null) } // null = all seasons
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
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    FavoriteIcon(isFavorite = isFavorite) {
                        scope.launch {
                            isFavorite =
                                toggleFavorite(playlistId, seriesKey, ChannelKind.SERIES, isFavorite)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
        ) {
            item {
                Poster(
                    meta?.posterUrl ?: episodes.firstOrNull { it.logo != null }?.logo,
                    Icons.Rounded.VideoLibrary,
                )
                Spacer(Modifier.height(18.dp))
                Text(seriesKey, style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    episodes.firstOrNull()?.let { Pill(it.groupTitle) }
                    Pill("${episodes.size} episodes")
                    if (seasons.size > 1) Pill("${seasons.size} seasons")
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
                if (seasons.isNotEmpty()) {
                    SeasonPicker(
                        seasons = seasons,
                        selected = selectedSeason,
                        onSelect = { selectedSeason = it },
                    )
                    Spacer(Modifier.height(10.dp))
                }
            }
            items(shown, key = { it.id }) { episode ->
                EpisodeRow(
                    episode = episode,
                    downloadState = downloadsByUrl[episode.url],
                    onOpen = { onOpenEpisode(episode.id) },
                    onDownload = {
                        scope.launch {
                            val blocked = OpenTvApp.graph.downloads.enqueue(episode)
                            snackbar.showSnackbar(blocked ?: "Download started: ${episode.name}")
                        }
                    },
                    progress = progressByUrl[episode.url],
                )
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
            value = selected?.let { "Season $it" } ?: "All seasons",
            onValueChange = {},
            readOnly = true,
            label = { Text("Season") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("All seasons") },
                onClick = { onSelect(null); expanded = false },
            )
            seasons.forEach { season ->
                DropdownMenuItem(
                    text = { Text("Season $season") },
                    onClick = { onSelect(season); expanded = false },
                )
            }
        }
    }
}

internal fun episodeTag(episode: ChannelEntity): String? = when {
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
                Icons.Rounded.VideoLibrary,
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
    episode: ChannelEntity,
    downloadState: DownloadEntity?,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    progress: Float? = null,
) {
    Card(
        onClick = onOpen,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.padding(vertical = 4.dp).focusHighlight(),
    ) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            EpisodeThumb(episode.logo, progress = progress)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                episodeTag(episode)?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                Text(
                    episode.name,
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
            // Single trailing action keeps titles readable; the card itself
            // opens the episode page where Play lives.
            DownloadStateIcon(state = downloadState, onDownload = onDownload)
        }
    }
}

/**
 * Full episode page, consistent with movie/series pages: large still, facts,
 * synopsis, play/download. Stored panel data (Xtream) when present, otherwise
 * lazily enriched from TVMaze's episode endpoint (cached).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpisodeDetailScreen(
    channelId: Long,
    onBack: () -> Unit,
    onPlay: (url: String, title: String) -> Unit,
) {
    val graph = OpenTvApp.graph
    var episode by remember { mutableStateOf<ChannelEntity?>(null) }
    var seriesTitle by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<MetadataEntity?>(null) }
    var seriesCast by remember { mutableStateOf<List<com.buco7854.opentv.data.meta.CastMember>>(emptyList()) }
    val downloads by graph.downloads.downloads.collectAsState(initial = emptyList())
    val progressByUrl by graph.resume.progressByUrl.collectAsState(initial = emptyMap())
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(channelId) {
        val ep = graph.db.channelDao().get(channelId) ?: return@LaunchedEffect
        episode = ep
        // The series name behind this episode: Xtream episodes key by series id,
        // M3U episodes key by the series title itself.
        val key = ep.seriesKey
        if (key != null && key.startsWith("xs:")) {
            val xtreamSeries = key.removePrefix("xs:").toLongOrNull()
                ?.let { graph.db.xtreamSeriesDao().get(ep.playlistId, it) }
            seriesTitle = xtreamSeries?.name
            seriesCast = castFromNames(xtreamSeries?.castNames)
        } else {
            seriesTitle = key
            // Cached series lookup - no extra network beyond the series page.
            seriesCast = key?.let { decodeCast(graph.metadata.forTitle(true, it)?.castJson) }.orEmpty()
        }
        if (ep.description == null && ep.season != null && ep.episode != null && seriesTitle != null) {
            info = graph.metadata.episodeInfo(seriesTitle!!, ep.season, ep.episode)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        val ep = episode ?: return@Scaffold
        val downloadState = downloads.firstOrNull {
            it.url == ep.url && it.status != DownloadStatus.CANCELLED && it.status != DownloadStatus.FAILED
        }
        val image = info?.posterUrl ?: ep.logo
        val plot = ep.description ?: info?.overview

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
                            Icons.Rounded.VideoLibrary,
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
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(info?.title ?: ep.name, style = MaterialTheme.typography.headlineMedium)
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
                        "Cast",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(8.dp))
                    CastRow(seriesCast)
                }
                Spacer(Modifier.height(24.dp))
                val progress = progressByUrl[ep.url]
                if (progress != null) {
                    WatchProgressBar(progress, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${(progress * 100).toInt()}% watched",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                }
                Button(
                    onClick = { onPlay(ep.url, ep.name) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (progress != null) "Resume" else "Play")
                }
                Spacer(Modifier.height(10.dp))
                DownloadButton(
                    state = downloadState,
                    onDownload = {
                        scope.launch {
                            val blocked = graph.downloads.enqueue(ep)
                            snackbar.showSnackbar(blocked ?: "Download started")
                        }
                    },
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
