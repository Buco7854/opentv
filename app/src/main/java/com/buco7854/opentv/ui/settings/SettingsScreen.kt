package com.buco7854.opentv.ui.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuAnchorType
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.buco7854.opentv.R
import com.buco7854.opentv.data.net.Http
import com.buco7854.opentv.data.prefs.PlayerSettings
import com.buco7854.opentv.download.DownloadStorage
import com.buco7854.opentv.ui.components.OtvMenuDefaults
import com.buco7854.opentv.ui.components.OtvTextButton
import com.buco7854.opentv.ui.components.SubtitleStyleControls
import java.util.Locale

private val LANGUAGE_CODES =
    listOf("", "en", "fr", "es", "de", "it", "pt", "nl", "pl", "ru", "tr", "ar", "hi", "zh", "ja", "ko")

@Composable
private fun languageLabel(code: String): String =
    if (code.isEmpty()) stringResource(R.string.settings_auto)
    else Locale.forLanguageTag(code)
        .getDisplayLanguage(LocalConfiguration.current.locales[0])
        .replaceFirstChar { it.uppercase() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val moveDownloads by viewModel.moveDownloads.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val current = settings ?: return
    val resources = LocalResources.current
    val update: (PlayerSettings) -> Unit = viewModel::save

    LaunchedEffect(current.downloadDirUri) {
        viewModel.refreshMoveCount()
    }
    LaunchedEffect(moveDownloads.result) {
        val result = moveDownloads.result ?: return@LaunchedEffect
        snackbar.showSnackbar(
            if (result.failed > 0) {
                resources.getString(R.string.settings_move_result_failed, result.moved, result.failed)
            } else {
                resources.getString(R.string.settings_move_result, result.moved)
            },
        )
        viewModel.consumeMoveResult()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.common_settings)) },
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
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            SectionCard(stringResource(R.string.settings_appearance)) {
                ChipSetting(
                    label = stringResource(R.string.settings_theme),
                    options = listOf(
                        PlayerSettings.THEME_SYSTEM to stringResource(R.string.settings_auto),
                        PlayerSettings.THEME_DARK to stringResource(R.string.settings_theme_dark),
                        PlayerSettings.THEME_LIGHT to stringResource(R.string.settings_theme_light),
                    ),
                    selected = current.themeMode,
                    onSelect = { update(current.copy(themeMode = it)) },
                    hint = stringResource(R.string.settings_theme_hint),
                )
            }

            SectionCard(stringResource(R.string.settings_tracks)) {
                LanguagePicker(
                    label = stringResource(R.string.settings_preferred_audio),
                    value = current.preferredAudioLang,
                    onSelect = { update(current.copy(preferredAudioLang = it)) },
                )
                Spacer(Modifier.height(12.dp))
                LanguagePicker(
                    label = stringResource(R.string.settings_preferred_subtitles),
                    value = current.preferredTextLang,
                    onSelect = { update(current.copy(preferredTextLang = it)) },
                )
                Hint(stringResource(R.string.settings_tracks_hint))
            }

            SectionCard(stringResource(R.string.settings_playback)) {
                ChipSetting(
                    label = stringResource(R.string.settings_seek_step),
                    options = listOf(5, 10, 30).map { it to stringResource(R.string.settings_seconds, it) },
                    selected = current.seekSeconds,
                    onSelect = { update(current.copy(seekSeconds = it)) },
                )
                SettingDivider()
                ChipSetting(
                    label = stringResource(R.string.settings_buffering),
                    options = listOf(
                        PlayerSettings.BUFFER_FAST_START to stringResource(R.string.settings_buffer_fast),
                        PlayerSettings.BUFFER_BALANCED to stringResource(R.string.settings_buffer_balanced),
                        PlayerSettings.BUFFER_STABLE to stringResource(R.string.settings_buffer_stable),
                    ),
                    selected = current.bufferPreset,
                    onSelect = { update(current.copy(bufferPreset = it)) },
                    hint = stringResource(R.string.settings_buffer_hint),
                )
                SettingDivider()
                ChipSetting(
                    label = stringResource(R.string.settings_video_scaling),
                    // androidx.media3.ui.AspectRatioFrameLayout: FIT = 0, FILL = 3, ZOOM = 4
                    options = listOf(
                        0 to stringResource(R.string.player_scale_fit),
                        4 to stringResource(R.string.player_scale_zoom),
                        3 to stringResource(R.string.player_scale_stretch),
                    ),
                    selected = current.resizeMode,
                    onSelect = { update(current.copy(resizeMode = it)) },
                )
                SettingDivider()
                SwitchSetting(
                    label = stringResource(R.string.settings_decoder_fallback),
                    description = stringResource(R.string.settings_decoder_fallback_desc),
                    checked = current.decoderFallback,
                    onChange = { update(current.copy(decoderFallback = it)) },
                )
            }

            SectionCard(stringResource(R.string.common_downloads)) {
                ChipSetting(
                    label = stringResource(R.string.settings_simultaneous_downloads),
                    options = listOf(
                        PlayerSettings.DOWNLOADS_AUTO to stringResource(R.string.settings_auto),
                        1 to "1", 2 to "2", 3 to "3",
                    ),
                    selected = current.downloadLimit,
                    onSelect = { update(current.copy(downloadLimit = it)) },
                    hint = stringResource(R.string.settings_downloads_hint),
                )
                SettingDivider()
                DownloadLocationSetting(current = current, update = update)
                SettingDivider()
                MoveDownloadsSetting(
                    state = moveDownloads,
                    onMove = viewModel::moveDownloads,
                )
            }

            SectionCard(stringResource(R.string.settings_network)) {
                UserAgentSetting(current = current, update = update)
            }

            SectionCard(stringResource(R.string.player_subtitles)) {
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
        color = MaterialTheme.colorScheme.onSurface,
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipSetting(
    label: String,
    options: List<Pair<Int, String>>,
    selected: Int,
    onSelect: (Int) -> Unit,
    hint: String? = null,
) {
    Text(label, style = MaterialTheme.typography.titleSmall)
    // FlowRow so chips wrap on narrow screens.
    FlowRow(
        Modifier.padding(top = 2.dp).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (value, text) ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                label = { Text(text) },
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
    Text(stringResource(R.string.settings_location), style = MaterialTheme.typography.titleSmall)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (current.downloadDirUri.isEmpty()) stringResource(R.string.settings_app_storage)
            else DownloadStorage.describeTree(current.downloadDirUri),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (current.downloadDirUri.isNotEmpty()) {
            OtvTextButton(onClick = { update(current.copy(downloadDirUri = "")) }) { Text(stringResource(R.string.settings_reset)) }
        }
        OtvTextButton(onClick = { folderPicker.launch(null) }) { Text(stringResource(R.string.settings_choose)) }
    }
    Hint(stringResource(R.string.settings_location_hint))
}

@Composable
private fun MoveDownloadsSetting(
    state: MoveDownloadsState,
    onMove: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(stringResource(R.string.settings_move_downloads), style = MaterialTheme.typography.titleSmall)
            Text(
                when {
                    state.moving -> stringResource(R.string.settings_moving_files)
                    state.pending == 0 -> stringResource(R.string.settings_all_in_folder)
                    else -> pluralStringResource(
                        R.plurals.settings_files_elsewhere,
                        state.pending,
                        state.pending,
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.moving) {
            CircularProgressIndicator(
                Modifier.size(22.dp),
                strokeWidth = 2.5.dp,
                color = MaterialTheme.colorScheme.onSurface,
            )
        } else {
            OtvTextButton(
                enabled = state.pending > 0,
                onClick = onMove,
            ) { Text(stringResource(R.string.settings_move)) }
        }
    }
}

/** Common agents IPTV panels recognise; empty value = the app default. */
private val USER_AGENT_PRESETS = listOf(
    null to "",
    "VLC" to "VLC/3.0.20 LibVLC/3.0.20",
    "IPTV Smarters" to "IPTVSmartersPlayer",
    "Kodi" to "Kodi/20.0 (Linux; Android) Inputstream.adaptive",
    "TiviMate" to "TiviMate/4.7.0 (Android)",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UserAgentSetting(current: PlayerSettings, update: (PlayerSettings) -> Unit) {
    // Local edit buffer so typing stays smooth; persisted on each change.
    var text by remember(current.userAgent) { mutableStateOf(current.userAgent) }
    Text(stringResource(R.string.settings_user_agent), style = MaterialTheme.typography.titleSmall)
    FlowRow(
        Modifier.padding(top = 2.dp).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        USER_AGENT_PRESETS.forEach { (name, value) ->
            FilterChip(
                selected = current.userAgent == value,
                onClick = {
                    text = value
                    update(current.copy(userAgent = value))
                },
                label = { Text(name ?: stringResource(R.string.settings_ua_default)) },
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
        label = { Text(stringResource(R.string.settings_custom_user_agent)) },
        placeholder = { Text(Http.DEFAULT_USER_AGENT) },
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
    )
    Hint(stringResource(R.string.settings_user_agent_hint))
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
