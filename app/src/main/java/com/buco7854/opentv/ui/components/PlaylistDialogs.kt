package com.buco7854.opentv.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.buco7854.opentv.R
import com.buco7854.opentv.core.model.Playlist
import com.buco7854.opentv.core.xtream.Xtream
import com.buco7854.opentv.core.xtream.XtreamCredentials
import androidx.compose.ui.unit.dp

/** Source chooser mode that routes to the server sign-in flow instead of adding a playlist. */
private const val HUB_MODE = 3

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
    /** Null hides the server option (nothing to route to). */
    onConnectHub: (() -> Unit)? = null,
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
    val cancelFocusRequester = remember { FocusRequester() }
    val suggestionFocusRequester = remember { FocusRequester() }
    RequestInitialFocusOnTv(cancelFocusRequester, editing)

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onSubmitFile(editing?.id, name, uri)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isEdit) R.string.playlist_edit_title else R.string.playlist_add_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!isEdit) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = mode == 0,
                            onClick = { mode = 0 },
                            label = { Text(stringResource(R.string.playlist_type_xtream)) },
                            modifier = Modifier.focusHighlight(),
                        )
                        FilterChip(
                            selected = mode == 1,
                            onClick = { mode = 1 },
                            label = { Text(stringResource(R.string.playlist_type_m3u_url)) },
                            modifier = Modifier.focusHighlight(),
                        )
                        FilterChip(
                            selected = mode == 2,
                            onClick = { mode = 2 },
                            label = { Text(stringResource(R.string.playlist_type_file)) },
                            modifier = Modifier.focusHighlight(),
                        )
                        if (onConnectHub != null) {
                            FilterChip(
                                selected = mode == HUB_MODE,
                                onClick = { mode = HUB_MODE },
                                label = { Text(stringResource(R.string.hub_type)) },
                                modifier = Modifier.focusHighlight(),
                            )
                        }
                    }
                }
                if (mode != HUB_MODE) {
                    PlaylistField(
                        label = R.string.playlist_field_name,
                        value = name,
                        onValueChange = { name = it },
                    )
                }
                when (mode) {
                    0 -> {
                        PlaylistField(
                            label = R.string.playlist_field_server,
                            value = server,
                            onValueChange = { server = it },
                        )
                        PlaylistField(
                            label = R.string.playlist_field_username,
                            value = username,
                            onValueChange = { username = it },
                            autofillType = AutofillType.Username,
                        )
                        PlaylistField(
                            label = R.string.playlist_field_password,
                            value = password,
                            onValueChange = { password = it },
                            autofillType = AutofillType.Password,
                            secret = true,
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
                        PlaylistField(
                            label = R.string.playlist_field_m3u_url,
                            value = url,
                            onValueChange = { url = it },
                        )
                        PlaylistField(
                            label = R.string.playlist_field_epg_url,
                            value = epg,
                            onValueChange = { epg = it },
                        )
                    }
                    HUB_MODE -> {
                        Text(
                            stringResource(R.string.hub_type_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        // Connecting to a server is a flow of its own, not a form.
                        HUB_MODE -> onConnectHub?.invoke()
                        else -> if (isEdit) onRename(editing!!.id, name)
                        else filePicker.launch(arrayOf("*/*"))
                    }
                },
            ) {
                Text(
                    stringResource(
                        if (isEdit) R.string.common_save
                        else if (mode == HUB_MODE) R.string.hub_connect
                        else if (mode == 2) R.string.playlist_choose_file
                        else R.string.common_add
                    )
                )
            }
        },
        dismissButton = {
            OtvTextButton(
                onClick = onDismiss,
                modifier = Modifier.focusRequester(cancelFocusRequester),
            ) { Text(stringResource(R.string.common_cancel)) }
        },
    )

    xtreamSuggestion?.let { creds ->
        RequestInitialFocusOnTv(suggestionFocusRequester, creds)
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
                }, modifier = Modifier.focusRequester(suggestionFocusRequester)) {
                    Text(stringResource(R.string.playlist_keep_m3u))
                }
            },
        )
    }
}

/**
 * One input in a playlist form, shared by the local and server-hosted variants.
 *
 * They ask for the same things -- a name, a provider address, a login -- and differ only in
 * where the values are kept, so the inputs themselves have no business being written twice.
 */
/**
 * States that an action lands on the server, not on this device.
 *
 * Local and server-hosted playlists reach the same menu with the same words, so nothing in
 * "Edit" or "Delete" says that one changes a catalog every other user of that server browses.
 * Bold on purpose: it is the difference between undoing a mistake and apologising for one.
 */
@Composable
fun ServerPlaylistNotice(modifier: Modifier = Modifier) {
    Text(
        stringResource(R.string.playlist_server_scope_notice),
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.error,
        modifier = modifier,
    )
}

@Composable
fun PlaylistField(
    label: Int,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    autofillType: AutofillType? = null,
    secret: Boolean = false,
) {
    val base = modifier.fillMaxWidth()
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(label)) },
        singleLine = true,
        visualTransformation = if (secret) {
            PasswordVisualTransformation()
        } else {
            VisualTransformation.None
        },
        keyboardOptions = if (secret) {
            KeyboardOptions(keyboardType = KeyboardType.Password)
        } else {
            KeyboardOptions.Default
        },
        modifier = if (autofillType == null) {
            base
        } else {
            base.autofill(types = listOf(autofillType), onFill = onValueChange)
        },
    )
}

@Composable
fun ConfirmDeletePlaylistDialog(
    /** Already-resolved copy: a server-hosted playlist is warned about in the server's words. */
    message: String,
    focusKey: Any?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    /** Server-hosted playlists say so: the deletion is not local to this device. */
    serverHosted: Boolean = false,
) {
    val cancelFocusRequester = remember { FocusRequester() }
    RequestInitialFocusOnTv(cancelFocusRequester, focusKey)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.playlist_delete_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (serverHosted) ServerPlaylistNotice()
                Text(message)
            }
        },
        confirmButton = { OtvTextButton(onClick = onConfirm, danger = true) { Text(stringResource(R.string.common_remove)) } },
        dismissButton = {
            OtvTextButton(
                onClick = onDismiss,
                modifier = Modifier.focusRequester(cancelFocusRequester),
            ) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}
