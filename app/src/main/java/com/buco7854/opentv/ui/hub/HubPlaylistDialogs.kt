package com.buco7854.opentv.ui.hub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.buco7854.opentv.R
import com.buco7854.opentv.source.PlaylistEditField
import com.buco7854.opentv.source.PlaylistEditForm
import com.buco7854.opentv.source.PlaylistEditUpdate
import com.buco7854.opentv.source.ProviderAccountInfo
import com.buco7854.opentv.ui.components.OtvTextButton

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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shell_edit_playlist)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (form.storedFields.isNotEmpty()) {
                    Text(
                        stringResource(R.string.playlist_credentials_hidden),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (PlaylistEditField.NAME in form.fields) {
                    Field(R.string.playlist_field_name, name) { name = it }
                }
                if (PlaylistEditField.SERVER in form.fields) {
                    Field(R.string.playlist_field_server, server) { server = it }
                }
                if (PlaylistEditField.USERNAME in form.fields) {
                    Field(R.string.playlist_field_username, username) { username = it }
                }
                if (PlaylistEditField.PASSWORD in form.fields) {
                    Field(R.string.playlist_field_password, password, secret = true) {
                        password = it
                    }
                }
                if (PlaylistEditField.URL in form.fields) {
                    Field(R.string.playlist_field_m3u_url, url) { url = it }
                }
                if (PlaylistEditField.EPG_URL in form.fields) {
                    Field(R.string.playlist_field_epg_url, epgUrl) { epgUrl = it }
                }
            }
        },
        confirmButton = {
            OtvTextButton(
                enabled = !busy && name.isNotBlank(),
                onClick = {
                    // Blank stays blank: an untouched field must reach the server as absent,
                    // not as an empty string that would erase a stored credential.
                    onSave(
                        PlaylistEditUpdate(
                            name = name.takeIf { it.isNotBlank() && it != form.name },
                            server = server.takeIf { it.isNotBlank() },
                            username = username.takeIf { it.isNotBlank() },
                            password = password.takeIf { it.isNotBlank() },
                            url = url.takeIf { it.isNotBlank() },
                            epgUrl = epgUrl.takeIf { it.isNotBlank() },
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

@Composable
private fun Field(
    label: Int,
    value: String,
    secret: Boolean = false,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(label)) },
        singleLine = true,
        visualTransformation = if (secret) {
            PasswordVisualTransformation()
        } else {
            androidx.compose.ui.text.input.VisualTransformation.None
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Read-only provider account figures for a server-hosted Xtream playlist. */
@Composable
fun ProviderAccountDialog(info: ProviderAccountInfo, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.account_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (info.stale) {
                    Text(
                        stringResource(R.string.account_stale_error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    "${info.activeConnections} / ${info.maxConnections} " +
                        stringResource(R.string.account_connections_label),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "${stringResource(R.string.account_status)}: ${info.status}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (info.isTrial) {
                    Text(
                        stringResource(R.string.account_trial),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            OtvTextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        },
    )
}
