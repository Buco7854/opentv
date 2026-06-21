package com.buco7854.opentv.ui.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
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
import com.buco7854.opentv.OpenTvApp
import com.buco7854.opentv.data.net.Http
import com.buco7854.opentv.data.prefs.PlayerSettings
import com.buco7854.opentv.download.DownloadStorage
import com.buco7854.opentv.ui.components.SubtitleStyleControls
import kotlinx.coroutines.launch
import java.util.Locale

private val LANGUAGE_CODES =
    listOf("", "en", "fr", "es", "de", "it", "pt", "nl", "pl", "ru", "tr", "ar", "hi", "zh", "ja", "ko")

private fun languageLabel(code: String): String =
    if (code.isEmpty()) "Auto"
    else Locale(code).getDisplayLanguage(Locale.getDefault()).replaceFirstChar { it.uppercase() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val prefs = OpenTvApp.graph.playerPrefs
    val settings by prefs.settings.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val current = settings ?: return
    val update: (PlayerSettings) -> Unit = { scope.launch { prefs.save(it) } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            SectionCard("Tracks") {
                LanguagePicker(
                    label = "Preferred audio language",
                    value = current.preferredAudioLang,
                    onSelect = { update(current.copy(preferredAudioLang = it)) },
                )
                Spacer(Modifier.height(12.dp))
                LanguagePicker(
                    label = "Preferred subtitle language",
                    value = current.preferredTextLang,
                    onSelect = { update(current.copy(preferredTextLang = it)) },
                )
                Hint("Picked automatically when a stream offers a matching track.")
            }

            SectionCard("Playback") {
                ChipSetting(
                    label = "Seek step",
                    options = listOf(5 to "5 s", 10 to "10 s", 30 to "30 s"),
                    selected = current.seekSeconds,
                    onSelect = { update(current.copy(seekSeconds = it)) },
                )
                SettingDivider()
                ChipSetting(
                    label = "Buffering",
                    options = listOf(
                        PlayerSettings.BUFFER_FAST_START to "Fast start",
                        PlayerSettings.BUFFER_BALANCED to "Balanced",
                        PlayerSettings.BUFFER_STABLE to "Stable",
                    ),
                    selected = current.bufferPreset,
                    onSelect = { update(current.copy(bufferPreset = it)) },
                    hint = "Stable buffers more to ride out shaky connections.",
                )
                SettingDivider()
                ChipSetting(
                    label = "Default video scaling",
                    // androidx.media3.ui.AspectRatioFrameLayout: FIT = 0, FILL = 3, ZOOM = 4
                    options = listOf(0 to "Fit", 4 to "Zoom", 3 to "Stretch"),
                    selected = current.resizeMode,
                    onSelect = { update(current.copy(resizeMode = it)) },
                )
                SettingDivider()
                SwitchSetting(
                    label = "Software decoder fallback",
                    description = "Retry with a software decoder if the hardware one fails.",
                    checked = current.decoderFallback,
                    onChange = { update(current.copy(decoderFallback = it)) },
                )
            }

            SectionCard("Downloads") {
                ChipSetting(
                    label = "Simultaneous downloads",
                    options = listOf(PlayerSettings.DOWNLOADS_AUTO to "Auto", 1 to "1", 2 to "2", 3 to "3"),
                    selected = current.downloadLimit,
                    onSelect = { update(current.copy(downloadLimit = it)) },
                    hint = "Auto uses your provider's connection limit, keeping one slot free for watching.",
                )
                SettingDivider()
                DownloadLocationSetting(current = current, update = update)
                SettingDivider()
                MoveDownloadsSetting(snackbar = snackbar)
            }

            SectionCard("Network") {
                UserAgentSetting(current = current, update = update)
            }

            SectionCard("Subtitles") {
                SubtitleStyleControls(
                    style = current.subtitleStyle,
                    onChange = { update(current.copy(subtitleStyle = it)) },
                )
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 24.dp, bottom = 10.dp),
    )
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) { content() }
    }
}

@Composable
private fun SettingDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun ChipSetting(
    label: String,
    options: List<Pair<Int, String>>,
    selected: Int,
    onSelect: (Int) -> Unit,
    hint: String? = null,
) {
    Text(label, style = MaterialTheme.typography.titleSmall)
    Row(Modifier.padding(top = 2.dp)) {
        options.forEach { (value, text) ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                label = { Text(text) },
                modifier = Modifier.padding(end = 8.dp),
            )
        }
    }
    hint?.let { Hint(it) }
}

@Composable
private fun SwitchSetting(
    label: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun DownloadLocationSetting(current: PlayerSettings, update: (PlayerSettings) -> Unit) {
    val context = LocalContext.current
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            update(current.copy(downloadDirUri = uri.toString()))
        }
    }
    Text("Location", style = MaterialTheme.typography.titleSmall)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            DownloadStorage.describeTree(current.downloadDirUri),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (current.downloadDirUri.isNotEmpty()) {
            TextButton(onClick = { update(current.copy(downloadDirUri = "")) }) { Text("Reset") }
        }
        TextButton(onClick = { folderPicker.launch(null) }) { Text("Choose") }
    }
    Hint("A custom folder is visible to other apps and keeps your files after uninstall. Applies to new downloads.")
}

@Composable
private fun MoveDownloadsSetting(snackbar: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    val downloads = OpenTvApp.graph.downloads
    var pending by remember { mutableStateOf(0) }
    var moving by remember { mutableStateOf(false) }
    // Recount when the screen (re)composes, e.g. after changing the folder.
    LaunchedEffect(Unit) { pending = downloads.completedElsewhereCount() }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text("Move existing downloads here", style = MaterialTheme.typography.titleSmall)
            Text(
                when {
                    moving -> "Moving files…"
                    pending == 0 -> "All downloads are already in this folder."
                    else -> "$pending downloaded file${if (pending == 1) "" else "s"} elsewhere."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (moving) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
        } else {
            TextButton(
                enabled = pending > 0,
                onClick = {
                    scope.launch {
                        moving = true
                        val r = downloads.moveCompletedToCurrentFolder()
                        pending = downloads.completedElsewhereCount()
                        moving = false
                        snackbar.showSnackbar(
                            buildString {
                                append("Moved ${r.moved}")
                                if (r.failed > 0) append(", ${r.failed} failed")
                            }
                        )
                    }
                },
            ) { Text("Move") }
        }
    }
}

/** Common agents IPTV panels recognise; empty value = the app default. */
private val USER_AGENT_PRESETS = listOf(
    "Default" to "",
    "VLC" to "VLC/3.0.20 LibVLC/3.0.20",
    "IPTV Smarters" to "IPTVSmartersPlayer",
    "Kodi" to "Kodi/20.0 (Linux; Android) Inputstream.adaptive",
    "TiviMate" to "TiviMate/4.7.0 (Android)",
)

@Composable
private fun UserAgentSetting(current: PlayerSettings, update: (PlayerSettings) -> Unit) {
    // Local edit buffer so typing stays smooth; persisted on each change.
    var text by remember(current.userAgent) { mutableStateOf(current.userAgent) }
    Text("User-Agent", style = MaterialTheme.typography.titleSmall)
    Row(Modifier.padding(top = 2.dp)) {
        USER_AGENT_PRESETS.forEach { (name, value) ->
            FilterChip(
                selected = current.userAgent == value,
                onClick = {
                    text = value
                    update(current.copy(userAgent = value))
                },
                label = { Text(name) },
                modifier = Modifier.padding(end = 8.dp),
            )
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            update(current.copy(userAgent = it))
        },
        singleLine = true,
        label = { Text("Custom User-Agent") },
        placeholder = { Text(Http.DEFAULT_USER_AGENT) },
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
    )
    Hint("Some providers reject unknown apps with a 404 or 403. Match what your provider expects. Leave blank for the default.")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguagePicker(label: String, value: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = languageLabel(value),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            LANGUAGE_CODES.forEach { code ->
                DropdownMenuItem(
                    text = { Text(languageLabel(code)) },
                    onClick = {
                        onSelect(code)
                        expanded = false
                    },
                )
            }
        }
    }
}
