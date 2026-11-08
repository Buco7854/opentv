package com.buco7854.opentv.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.buco7854.opentv.R
import com.buco7854.opentv.core.model.Playlist
import com.buco7854.opentv.source.SourceId
import com.buco7854.opentv.source.encode
import com.buco7854.opentv.ui.components.OtvButton
import com.buco7854.opentv.ui.components.OtvProgressBar
import com.buco7854.opentv.ui.components.OtvTextButton
import com.buco7854.opentv.ui.components.OtvTonalButton
import com.buco7854.opentv.ui.components.PlaylistDialog
import com.buco7854.opentv.ui.components.focusHighlight

/** Home is a stable library overview. Opening a source is always an explicit action. */
@Composable
fun HomeScreen(
    onOpenSource: (SourceId) -> Unit,
    onConnectHub: () -> Unit,
    viewModel: HomeViewModel = viewModel(),
) {
    val playlists by viewModel.playlists.collectAsStateWithLifecycle(initialValue = null)
    val catalogSources by viewModel.catalogSources.collectAsStateWithLifecycle()
    val catalogSourcesLoading by viewModel.catalogSourcesLoading.collectAsStateWithLifecycle()
    val unreachableHubs by viewModel.unreachableHubs.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showAdd by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        HomeLibrary(
            sources = homeSources(playlists, catalogSources),
            loading = playlists == null || catalogSourcesLoading,
            unreachableHubs = unreachableHubs,
            busy = busy,
            onOpenSource = onOpenSource,
            onConnectHub = onConnectHub,
            onAddPlaylist = { showAdd = true },
            onRetry = viewModel::retryCatalogSources,
        )
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    if (showAdd) {
        PlaylistDialog(
            editing = null,
            onDismiss = { showAdd = false },
            onSubmitUrl = { _, name, url, epg -> showAdd = false; viewModel.addFromUrl(name, url, epg) },
            onSubmitXtream = { _, name, server, user, pass ->
                showAdd = false; viewModel.addXtream(name, server, user, pass)
            },
            onSubmitFile = { _, name, uri -> showAdd = false; viewModel.addFromFile(name, uri) },
            onRename = { _, _ -> },
            onConnectHub = { showAdd = false; onConnectHub() },
        )
    }
}

/** Stateless so preview and production use exactly the same layout and state treatment. */
@Composable
internal fun HomeLibrary(
    sources: List<CatalogSourceEntry>,
    loading: Boolean,
    unreachableHubs: Int,
    busy: Boolean,
    onOpenSource: (SourceId) -> Unit,
    onConnectHub: () -> Unit,
    onAddPlaylist: () -> Unit,
    onRetry: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.widthIn(max = 960.dp).fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.home_library_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )

                }
                if (sources.isEmpty()) {
                    Text(
                        stringResource(R.string.home_welcome_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (busy) item { OtvProgressBar(Modifier.fillMaxWidth()) }
        if (sources.isNotEmpty()) {
            item { Text(stringResource(R.string.home_your_playlists), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 8.dp)) }
            items(sources, key = { it.sourceId.encode() }) { source ->
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp)
                            .focusHighlight(RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .clickable(role = Role.Button, onClick = { onOpenSource(source.sourceId) })
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(source.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(
                                    source.hubName ?: stringResource(R.string.home_on_device),
                                    source.itemCount?.let { stringResource(R.string.home_item_count, it) },
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
        if (loading) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.common_loading), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (unreachableHubs > 0) {
            item {
                Column {
                    Text(stringResource(R.string.home_some_servers_unreachable), style = MaterialTheme.typography.bodyMedium)
                    OtvTextButton(onClick = onRetry) { Text(stringResource(R.string.hub_retry)) }
                }
            }
        }
        if (!loading && sources.isEmpty() && unreachableHubs == 0) {
            item {
                Text(stringResource(R.string.home_welcome_title), style = MaterialTheme.typography.titleMedium)
            }
        }
        if (!loading || sources.isNotEmpty()) item {
            Column(Modifier.widthIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (sources.isEmpty() && unreachableHubs == 0) {
                    OtvButton(onClick = onConnectHub, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.home_server_title))
                    }
                    OtvTonalButton(onClick = onAddPlaylist, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.home_local_title))
                    }
                } else {
                    OtvTonalButton(onClick = onAddPlaylist, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.home_local_title))
                    }
                    OtvTextButton(onClick = onConnectHub, enabled = !busy) {
                        Text(stringResource(R.string.home_server_title))
                    }
                }
            }
        }
    }
}

/** Keep local sources available during remote discovery, and never display a source twice. */
internal fun homeSources(playlists: List<Playlist>?, sources: List<CatalogSourceEntry>): List<CatalogSourceEntry> {
    val local = playlists?.map {
        CatalogSourceEntry(SourceId.LocalPlaylist(it.id), it.name, itemCount = it.channelCount)
    } ?: sources.filter { it.sourceId is SourceId.LocalPlaylist }
    return (local + sources.filterNot { it.sourceId is SourceId.LocalPlaylist }).distinctBy { it.sourceId }
}
