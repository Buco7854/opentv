package com.buco7854.opentv.ui.hub

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.buco7854.opentv.R
import com.buco7854.opentv.source.PlaylistEditField
import com.buco7854.opentv.source.PlaylistEditForm
import com.buco7854.opentv.source.PlaylistEditUpdate
import androidx.compose.ui.autofill.AutofillType
import com.buco7854.opentv.ui.components.OtvTextButton
import com.buco7854.opentv.ui.components.PlaylistField
import com.buco7854.opentv.ui.components.ServerPlaylistNotice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Edits a server-hosted playlist.
 *
 * The server never returns the provider's credentials, so nothing here is prefilled except
 * the name: a blank field means "keep whatever is stored". That is not a limitation to work
 * around — it is why an administrator can edit a playlist from a phone without the phone
 * ever holding the provider login.
 */
@Composable
fun HubPlaylistEditDialog(
    form: PlaylistEditForm,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (PlaylistEditUpdate) -> Unit,
) {
    var name by remember(form.id) { mutableStateOf(form.name) }
    // Deliberately not rememberSaveable: saved instance state can reach disk, and a
    // provider password must not outlive this dialog.
    var server by remember(form.id) { mutableStateOf("") }
    var username by remember(form.id) { mutableStateOf("") }
    var password by remember(form.id) { mutableStateOf("") }
    var url by remember(form.id) { mutableStateOf("") }
    var epgUrl by remember(form.id) { mutableStateOf("") }
    var content by remember(form.id) { mutableStateOf<String?>(null) }
    var readingFile by remember(form.id) { mutableStateOf(false) }
    var fileReadFailed by remember(form.id) { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            readingFile = true
            fileReadFailed = false
            content = try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        ?: throw IllegalStateException("The selected playlist file could not be opened")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                fileReadFailed = true
                null
            } finally {
                readingFile = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shell_edit_playlist)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ServerPlaylistNotice()
                if (form.storedFields.isNotEmpty()) {
                    Text(
                        stringResource(R.string.playlist_credentials_hidden),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (PlaylistEditField.NAME in form.fields) {
                    PlaylistField(R.string.playlist_field_name, name, { name = it })
                }
                if (PlaylistEditField.SERVER in form.fields) {
                    PlaylistField(R.string.playlist_field_server, server, { server = it })
                }
                if (PlaylistEditField.USERNAME in form.fields) {
                    PlaylistField(
                        R.string.playlist_field_username,
                        username,
                        { username = it },
                        autofillType = AutofillType.Username,
                    )
                }
                if (PlaylistEditField.PASSWORD in form.fields) {
                    PlaylistField(
                        R.string.playlist_field_password,
                        password,
                        { password = it },
                        autofillType = AutofillType.Password,
                        secret = true,
                    )
                }
                if (PlaylistEditField.URL in form.fields) {
                    PlaylistField(R.string.playlist_field_m3u_url, url, { url = it })
                }
                if (PlaylistEditField.EPG_URL in form.fields) {
                    PlaylistField(R.string.playlist_field_epg_url, epgUrl, { epgUrl = it })
                }
                if (PlaylistEditField.CONTENT in form.fields) {
                    OtvTextButton(
                        onClick = { filePicker.launch(arrayOf("*/*")) },
                        enabled = !busy && !readingFile,
                    ) {
                        Text(stringResource(R.string.playlist_replace_file))
                    }
                    when {
                        fileReadFailed -> Text(
                            stringResource(R.string.playlist_file_read_failed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        content != null -> Text(
                            stringResource(R.string.playlist_file_replacement_selected),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            OtvTextButton(
                enabled = !busy && !readingFile && name.isNotBlank(),
                onClick = {
                    // Blank stays blank: an untouched field must reach the server as absent,
                    // not as an empty string that would erase a stored credential.
                    onSave(
                        playlistEditUpdate(
                            form,
                            name,
                            server,
                            username,
                            password,
                            url,
                            epgUrl,
                            content,
                        ),
                    )
                },
            ) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = {
            OtvTextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

internal fun playlistEditUpdate(
    form: PlaylistEditForm,
    name: String,
    server: String,
    username: String,
    password: String,
    url: String,
    epgUrl: String,
    content: String?,
) = PlaylistEditUpdate(
    name = name.takeIf { it.isNotBlank() && it != form.name },
    server = server.takeIf { it.isNotBlank() },
    username = username.takeIf { it.isNotBlank() },
    password = password.takeIf { it.isNotBlank() },
    url = url.takeIf { it.isNotBlank() },
    epgUrl = epgUrl.takeIf { it.isNotBlank() },
    content = content,
)
