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
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import com.buco7854.opentv.ui.components.ChannelLogo
import com.buco7854.opentv.ui.components.Pill
import kotlinx.coroutines.launch

private val YEAR_TAG = Regex("""\b(19|20)\d{2}\b""")
private val QUALITY_TAG = Regex("""(?i)\b(4K|UHD|2160p|1080p|FHD|720p|HEVC|HD|SD)\b""")

/** The metadata an M3U playlist actually carries, mined from the entry itself. */
private fun metaChips(channel: ChannelEntity): List<String> = buildList {
    add(channel.groupTitle)
    YEAR_TAG.find(channel.name)?.let { add(it.value) }
    QUALITY_TAG.find(channel.name)?.let { add(it.value.uppercase()) }
    val extension = channel.url.substringBefore('?').substringAfterLast('/')
        .substringAfterLast('.', "").take(5)
    if (extension.isNotEmpty()) add(extension.uppercase())
}

@Composable
private fun Poster(logo: String?, fallback: androidx.compose.ui.graphics.vector.ImageVector) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovieDetailScreen(
    channelId: Long,
    onBack: () -> Unit,
    onPlay: (url: String, title: String) -> Unit,
) {
    var channel by remember { mutableStateOf<ChannelEntity?>(null) }
    LaunchedEffect(channelId) { channel = OpenTvApp.graph.db.channelDao().get(channelId) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

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
        val movie = channel ?: return@Scaffold
        Column(
            Modifier
                .padding(padding)
                .padding(horizontal = 20.dp)
                .fillMaxSize()
        ) {
            Poster(movie.logo, Icons.Rounded.Movie)
            Spacer(Modifier.height(18.dp))
            Text(movie.name, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                metaChips(movie).take(4).forEach { Pill(it) }
            }
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { onPlay(movie.url, movie.name) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Play")
            }
            Spacer(Modifier.height(10.dp))
            FilledTonalButton(
                onClick = {
                    scope.launch {
                        val blocked = OpenTvApp.graph.downloads.enqueue(movie)
                        snackbar.showSnackbar(blocked ?: "Download started")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.Download, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Download for offline viewing")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesDetailScreen(
    playlistId: Long,
    seriesKey: String,
    onBack: () -> Unit,
    onPlay: (url: String, title: String) -> Unit,
) {
    val episodes by OpenTvApp.graph.db.channelDao()
        .observeEpisodes(playlistId, seriesKey)
        .collectAsState(initial = emptyList())
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

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
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
        ) {
            item {
                Poster(episodes.firstOrNull { it.logo != null }?.logo, Icons.Rounded.VideoLibrary)
                Spacer(Modifier.height(18.dp))
                Text(seriesKey, style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    episodes.firstOrNull()?.let { Pill(it.groupTitle) }
                    Pill("${episodes.size} episodes")
                    if (seasons.size > 1) Pill("${seasons.size} seasons")
                }
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
                    onPlay = { onPlay(episode.url, episode.name) },
                    onDownload = {
                        scope.launch {
                            val blocked = OpenTvApp.graph.downloads.enqueue(episode)
                            snackbar.showSnackbar(blocked ?: "Download started: ${episode.name}")
                        }
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeasonPicker(seasons: List<Int>, selected: Int?, onSelect: (Int?) -> Unit) {
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

@Composable
private fun EpisodeRow(episode: ChannelEntity, onPlay: () -> Unit, onDownload: () -> Unit) {
    Card(
        onClick = onPlay,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            ChannelLogo(episode.logo, Icons.Rounded.VideoLibrary)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                val tag = when {
                    episode.season != null && episode.episode != null ->
                        "S%02dE%02d".format(episode.season, episode.episode)
                    episode.episode != null -> "EP %d".format(episode.episode)
                    else -> null
                }
                tag?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                Text(
                    episode.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onDownload) {
                Icon(Icons.Rounded.Download, contentDescription = "Download", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
