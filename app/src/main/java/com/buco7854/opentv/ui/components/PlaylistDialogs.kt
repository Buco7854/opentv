package com.buco7854.opentv.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.AutofillType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.buco7854.opentv.R
import com.buco7854.opentv.core.model.Playlist
import com.buco7854.opentv.core.xtream.Xtream
import com.buco7854.opentv.core.xtream.XtreamCredentials
import androidx.compose.ui.unit.dp

/** Add/edit playlist dialog with Xtream auto-detection for get.php links. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun PlaylistDialog(
    editing: Playlist?,
    onDismiss: () -> Unit,
    onSubmitUrl: (id: Long?, name: String, url: String, epg: String) -> Unit,
    onSubmitXtream: (id: Long?, name: String, server: String, username: String, password: String) -> Unit,
    onSubmitFile: (id: Long?, name: String, uri: android.net.Uri) -> Unit,
    onRename: (id: Long, name: String) -> Unit,
) {
    val isEdit = editing != null
    // On edit the source type is fixed by the playlist; on add the user picks it.
    val initialMode = when {
        editing == null -> 0
        editing.url != null -> 1
        editing.xtreamBase != null -> 0
        else -> 2
    }
    var mode by remember(editing) { mutableStateOf(initialMode) } // 0 = Xtream, 1 = M3U URL, 2 = file
    var name by remember(editing) { mutableStateOf(editing?.name ?: "") }
    var url by remember(editing) { mutableStateOf(editing?.url ?: "") }
    var epg by remember(editing) { mutableStateOf(editing?.epgUrl ?: "") }
    var server by remember(editing) { mutableStateOf(editing?.xtreamBase ?: "") }
    var username by remember(editing) { mutableStateOf(editing?.xtreamUser ?: "") }
    var password by remember(editing) { mutableStateOf(editing?.xtreamPass ?: "") }
    var xtreamSuggestion by remember { mutableStateOf<XtreamCredentials?>(null) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onSubmitFile(editing?.id, name, uri)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isEdit) R.string.playlist_edit_title else R.string.playlist_add_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!isEdit) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = mode == 0, onClick = { mode = 0 }, label = { Text(stringResource(R.string.playlist_type_xtream)) })
                        FilterChip(selected = mode == 1, onClick = { mode = 1 }, label = { Text(stringResource(R.string.playlist_type_m3u_url)) })
                        FilterChip(selected = mode == 2, onClick = { mode = 2 }, label = { Text(stringResource(R.string.playlist_type_file)) })
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.playlist_field_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                when (mode) {
                    0 -> {
                        OutlinedTextField(
                            value = server,
                            onValueChange = { server = it },
                            label = { Text(stringResource(R.string.playlist_field_server)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it },
                            label = { Text(stringResource(R.string.playlist_field_username)) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .autofill(
                                    types = listOf(AutofillType.Username),
                                    onFill = { username = it },
                                ),
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text(stringResource(R.string.playlist_field_password)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier
                                .fillMaxWidth()
                                .autofill(
                                    types = listOf(AutofillType.Password),
                                    onFill = { password = it },
                                ),
                        )
                        if (!isEdit) {
                            Text(
                                stringResource(R.string.playlist_xtream_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    1 -> {
                        OutlinedTextField(
                            value = url,
                            onValueChange = { url = it },
                            label = { Text(stringResource(R.string.playlist_field_m3u_url)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = epg,
                            onValueChange = { epg = it },
                            label = { Text(stringResource(R.string.playlist_field_epg_url)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    else -> {
                        Text(
                            if (isEdit) stringResource(R.string.playlist_file_edit_hint)
                            else stringResource(R.string.playlist_file_add_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (isEdit) {
                            OtvTextButton(onClick = { filePicker.launch(arrayOf("*/*")) }) {
                                Text(stringResource(R.string.playlist_replace_file))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            OtvTextButton(
                onClick = {
                    when (mode) {
                        0 -> if (server.isNotBlank() && username.isNotBlank() && password.isNotBlank()) {
                            onSubmitXtream(editing?.id, name, server, username, password)
                        }
                        1 -> if (url.isNotBlank()) {
                            // A get.php URL carries Xtream creds; offer the richer integration (edit path detects later).
                            val creds = if (isEdit) null else Xtream.detect(url.trim())
                            if (creds != null) xtreamSuggestion = creds
                            else onSubmitUrl(editing?.id, name, url, epg)
                        }
                        else -> if (isEdit) onRename(editing!!.id, name)
                        else filePicker.launch(arrayOf("*/*"))
                    }
                },
            ) {
                Text(
                    stringResource(
                        if (isEdit) R.string.common_save
                        else if (mode == 2) R.string.playlist_choose_file
                        else R.string.common_add
                    )
                )
            }
        },
        dismissButton = { OtvTextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )

    xtreamSuggestion?.let { creds ->
        AlertDialog(
            onDismissRequest = { xtreamSuggestion = null },
            title = { Text(stringResource(R.string.playlist_xtream_detected_title)) },
            text = {
                Text(stringResource(R.string.playlist_xtream_detected_text, creds.base))
            },
            confirmButton = {
                OtvTextButton(onClick = {
                    xtreamSuggestion = null
                    onSubmitXtream(null, name, creds.base, creds.user, creds.pass)
                }) { Text(stringResource(R.string.playlist_use_xtream)) }
            },
            dismissButton = {
                OtvTextButton(onClick = {
                    xtreamSuggestion = null
                    onSubmitUrl(null, name, url, epg)
                }) { Text(stringResource(R.string.playlist_keep_m3u)) }
            },
        )
    }
}

@Composable
fun ConfirmDeletePlaylistDialog(
    playlist: Playlist,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.playlist_delete_title)) },
        text = { Text(stringResource(R.string.playlist_delete_text, playlist.name)) },
        confirmButton = { OtvTextButton(onClick = onConfirm, danger = true) { Text(stringResource(R.string.common_remove)) } },
        dismissButton = { OtvTextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
