package com.buco7854.opentv.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.buco7854.opentv.data.db.PlaylistEntity
import com.buco7854.opentv.data.xtream.Xtream
import com.buco7854.opentv.data.xtream.XtreamCredentials
import com.buco7854.opentv.ui.components.focusHighlight
import com.buco7854.opentv.ui.theme.Mint
import com.buco7854.opentv.ui.theme.Periwinkle
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenPlaylist: (Long) -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenLog: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAccount: (Long) -> Unit,
    viewModel: HomeViewModel = viewModel(),
) {
    val playlists by viewModel.playlists.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val message by viewModel.message.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var showAdd by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<PlaylistEntity?>(null) }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("OpenTV", style = MaterialTheme.typography.headlineMedium) },
                actions = {
                    IconButton(onClick = onOpenLog) {
                        Icon(Icons.Rounded.BugReport, contentDescription = "Error log")
                    }
                    IconButton(onClick = onOpenDownloads) {
                        Icon(Icons.Rounded.Download, contentDescription = "Downloads")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Rounded.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Rounded.Add, contentDescription = "Add playlist")
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (playlists.isEmpty()) {
                EmptyHome()
            } else {
                LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(playlists, key = { it.id }) { playlist ->
                        PlaylistCard(
                            playlist = playlist,
                            refreshEnabled = !busy,
                            onClick = { onOpenPlaylist(playlist.id) },
                            onRefresh = { viewModel.refresh(playlist.id) },
                            onOpenAccount = { onOpenAccount(playlist.id) },
                            onDelete = { pendingDelete = playlist },
                        )
                    }
                }
            }
            if (busy) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        }
    }

    if (showAdd) {
        AddPlaylistDialog(
            onDismiss = { showAdd = false },
            onAddUrl = { name, url, epg ->
                showAdd = false
                viewModel.addFromUrl(name, url, epg)
            },
            onAddXtream = { name, server, user, pass ->
                showAdd = false
                viewModel.addXtream(name, server, user, pass)
            },
            onAddFile = { name, uri ->
                showAdd = false
                viewModel.addFromFile(name, uri)
            },
        )
    }

    pendingDelete?.let { playlist ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Remove playlist?") },
            text = { Text("\"${playlist.name}\" and its cached guide data will be removed.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(playlist.id)
                    pendingDelete = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun EmptyHome() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .background(
                    Brush.linearGradient(listOf(Periwinkle.copy(alpha = 0.35f), Mint.copy(alpha = 0.25f))),
                    RoundedCornerShape(28.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Rounded.PlaylistPlay,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
        Text("Welcome to OpenTV", style = MaterialTheme.typography.titleLarge)
        Text(
            "Add an M3U playlist URL from your provider,\nor import a local playlist file.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun PlaylistCard(
    playlist: PlaylistEntity,
    refreshEnabled: Boolean,
    onClick: () -> Unit,
    onRefresh: () -> Unit,
    onOpenAccount: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.focusHighlight(RoundedCornerShape(20.dp)),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        playlist.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${playlist.channelCount} items" + (playlist.lastRefreshedMs.takeIf { it > 0 }
                            ?.let { " · updated ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))}" }
                            ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (playlist.xtreamBase != null) {
                    // Account & connection details live on their own page so the
                    // provider API is only queried when the user asks for it.
                    IconButton(onClick = onOpenAccount) {
                        Icon(Icons.Rounded.Person, contentDescription = "Account", tint = Mint)
                    }
                }
                if (playlist.url != null) {
                    IconButton(onClick = onRefresh, enabled = refreshEnabled) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh", tint = MaterialTheme.colorScheme.primary)
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Rounded.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun AddPlaylistDialog(
    onDismiss: () -> Unit,
    onAddUrl: (name: String, url: String, epg: String) -> Unit,
    onAddXtream: (name: String, server: String, username: String, password: String) -> Unit,
    onAddFile: (name: String, uri: android.net.Uri) -> Unit,
) {
    var mode by remember { mutableStateOf(0) } // 0 = Xtream login, 1 = M3U URL, 2 = file
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var epg by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var xtreamSuggestion by remember { mutableStateOf<XtreamCredentials?>(null) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onAddFile(name, uri)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add playlist") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = mode == 0, onClick = { mode = 0 }, label = { Text("Xtream") })
                    FilterChip(selected = mode == 1, onClick = { mode = 1 }, label = { Text("M3U URL") })
                    FilterChip(selected = mode == 2, onClick = { mode = 2 }, label = { Text("File") })
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                when (mode) {
                    0 -> {
                        OutlinedTextField(
                            value = server,
                            onValueChange = { server = it },
                            label = { Text("Server (host:port)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it },
                            label = { Text("Username") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Password") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "Uses the provider's API directly: server-side Live / Movies / Series " +
                                "categories, series catalog with details, catch-up, and EPG. No guessing.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    1 -> {
                        OutlinedTextField(
                            value = url,
                            onValueChange = { url = it },
                            label = { Text("M3U / M3U8 URL") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = epg,
                            onValueChange = { epg = it },
                            label = { Text("EPG (XMLTV) URL (optional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    else -> Text(
                        "Pick a .m3u / .m3u8 file from your device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    when (mode) {
                        0 -> if (server.isNotBlank() && username.isNotBlank() && password.isNotBlank()) {
                            onAddXtream(name, server, username, password)
                        }
                        1 -> if (url.isNotBlank()) {
                            // get.php URLs carry an Xtream login: offer the
                            // better integration before falling back to M3U.
                            val creds = Xtream.detect(url.trim())
                            if (creds != null) xtreamSuggestion = creds
                            else onAddUrl(name, url, epg)
                        }
                        else -> filePicker.launch(arrayOf("*/*"))
                    }
                },
            ) { Text(if (mode == 2) "Choose file" else "Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    xtreamSuggestion?.let { creds ->
        AlertDialog(
            onDismissRequest = { xtreamSuggestion = null },
            title = { Text("Xtream account detected") },
            text = {
                Text(
                    "This M3U URL is served by an Xtream panel (${creds.base}). Using the " +
                        "panel's API instead gives exact Live/Movies/Series categories, the " +
                        "series catalog with details, catch-up and automatic EPG, with no " +
                        "classification guessing. Your login was read from the URL."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    xtreamSuggestion = null
                    onAddXtream(name, creds.base, creds.user, creds.pass)
                }) { Text("Use Xtream") }
            },
            dismissButton = {
                TextButton(onClick = {
                    xtreamSuggestion = null
                    onAddUrl(name, url, epg)
                }) { Text("Keep M3U") }
            },
        )
    }
}
