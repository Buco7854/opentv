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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.buco7854.opentv.OpenTvApp
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
    val current = settings ?: return
    val update: (PlayerSettings) -> Unit = { scope.launch { prefs.save(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Player settings") },
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
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            SectionTitle("Tracks")
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
            Hint("Applied automatically when a stream offers a matching track. New playbacks only.")

            SectionTitle("Playback")
            Text("Seek step", style = MaterialTheme.typography.labelLarge)
            ChipRow(
                options = listOf(5 to "5 s", 10 to "10 s", 30 to "30 s"),
                selected = current.seekSeconds,
                onSelect = { update(current.copy(seekSeconds = it)) },
            )
            Spacer(Modifier.height(12.dp))
            Text("Buffering", style = MaterialTheme.typography.labelLarge)
            ChipRow(
                options = listOf(
                    PlayerSettings.BUFFER_FAST_START to "Fast start",
                    PlayerSettings.BUFFER_BALANCED to "Balanced",
                    PlayerSettings.BUFFER_STABLE to "Stable",
                ),
                selected = current.bufferPreset,
                onSelect = { update(current.copy(bufferPreset = it)) },
            )
            Hint("Fast start begins playback sooner; Stable buffers more to ride out shaky connections.")
            Text("Default video scaling", style = MaterialTheme.typography.labelLarge)
            ChipRow(
                // androidx.media3.ui.AspectRatioFrameLayout: FIT = 0, FILL = 3, ZOOM = 4
                options = listOf(0 to "Fit", 4 to "Zoom", 3 to "Stretch"),
                selected = current.resizeMode,
                onSelect = { update(current.copy(resizeMode = it)) },
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Software decoder fallback", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "If the hardware decoder fails, retry with a software decoder instead of stopping.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = current.decoderFallback,
                    onCheckedChange = { update(current.copy(decoderFallback = it)) },
                )
            }

            SectionTitle("Downloads")
            Text("Simultaneous downloads", style = MaterialTheme.typography.labelLarge)
            ChipRow(
                options = listOf(PlayerSettings.DOWNLOADS_AUTO to "Auto", 1 to "1", 2 to "2", 3 to "3"),
                selected = current.downloadLimit,
                onSelect = { update(current.copy(downloadLimit = it)) },
            )
            Hint(
                "Auto reads your provider's connection limit and keeps one slot free for " +
                    "watching. With a manual value, downloads pause while you stream from the " +
                    "same provider so you never exceed your plan's limit."
            )
            Text("Download location", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    DownloadStorage.describeTree(current.downloadDirUri),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                if (current.downloadDirUri.isNotEmpty()) {
                    TextButton(onClick = { update(current.copy(downloadDirUri = "")) }) { Text("Reset") }
                }
                TextButton(onClick = { folderPicker.launch(null) }) { Text("Choose folder") }
            }
            Hint(
                "App storage is private and removed when the app is uninstalled. A chosen " +
                    "folder is visible to file managers and other apps, and the files stay " +
                    "yours to move or keep. Applies to new downloads."
            )

            SectionTitle("Subtitle appearance")
            SubtitleStyleControls(
                style = current.subtitleStyle,
                onChange = { update(current.copy(subtitleStyle = it)) },
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 24.dp, bottom = 10.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
    )
}

@Composable
private fun ChipRow(options: List<Pair<Int, String>>, selected: Int, onSelect: (Int) -> Unit) {
    Row {
        options.forEach { (value, label) ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                label = { Text(label) },
                modifier = Modifier.padding(end = 8.dp, top = 4.dp),
            )
        }
    }
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
