package com.buco7854.opentv.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.Add
import com.buco7854.opentv.ui.components.OtvButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.buco7854.opentv.OpenTvApp
import com.buco7854.opentv.R
import com.buco7854.opentv.ui.components.PlaylistDialog

/** Forwards into the active (or first) playlist, or greets a fresh install. */
@Composable
fun HomeScreen(
    onOpenPlaylist: (Long) -> Unit,
    viewModel: HomeViewModel = viewModel(),
) {
    val graph = OpenTvApp.graph
    val playlists by graph.playlists.playlists.collectAsState(initial = null)
    val settings by graph.playerPrefs.settings.collectAsState(initial = null)
    val message by viewModel.message.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var showAdd by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    // Forward once both the list and the saved active id are known.
    LaunchedEffect(playlists, settings) {
        val list = playlists ?: return@LaunchedEffect
        val active = settings?.activePlaylistId ?: return@LaunchedEffect
        if (list.isNotEmpty()) {
            onOpenPlaylist(list.firstOrNull { it.id == active }?.id ?: list.first().id)
        }
    }

    Box(Modifier.fillMaxSize()) {
        when {
            playlists == null -> CircularProgressIndicator(
                Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.onSurface,
            )
            playlists!!.isEmpty() -> Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(20.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.PlaylistPlay,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(48.dp),
                    )
                }
                Spacer(Modifier.height(24.dp))
                Text(stringResource(R.string.home_welcome_title), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(R.string.home_welcome_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Spacer(Modifier.height(24.dp))
                OtvButton(onClick = { showAdd = true }) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.shell_add_playlist))
                }
            }
            else -> {}
        }
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
        )
    }
}
