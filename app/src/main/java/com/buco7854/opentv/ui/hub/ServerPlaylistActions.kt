package com.buco7854.opentv.ui.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.buco7854.opentv.OpenTvApp
import com.buco7854.opentv.R
import com.buco7854.opentv.ui.components.ConfirmDeletePlaylistDialog
import com.buco7854.opentv.ui.components.OtvMenuDefaults
import com.buco7854.opentv.ui.components.OtvTextButton
import com.buco7854.opentv.ui.components.ServerPlaylistNotice
import com.buco7854.opentv.ui.components.focusHighlight
import com.buco7854.opentv.ui.hub.HandoffResult
import com.buco7854.opentv.ui.hub.HubBrowserHandoff
import com.buco7854.opentv.ui.hub.HubPlaylistEditDialog
import com.buco7854.opentv.source.CatalogResult
import com.buco7854.opentv.source.PlaylistCapabilities
import com.buco7854.opentv.source.PlaylistDeleteInfo
import com.buco7854.opentv.source.PlaylistEditForm
import com.buco7854.opentv.source.PlaylistOperation
import com.buco7854.opentv.source.PlaylistOperationAvailability
import com.buco7854.opentv.ui.home.CatalogSourceEntry
import kotlinx.coroutines.launch

import com.buco7854.opentv.ui.shell.playlistRefreshMessage

/**
 * A server-backed playlist row.
 *
 * It offers the same operations a local playlist does, but the server decides which:
 * clearing your own watch progress is yours to do, while refreshing, editing or deleting
 * change a catalog everyone on that server shares, so they belong to an administrator.
 * Each advertised operation says whether Android handles it in-app or opens the server's
 * page. The list is fetched per playlist and never inferred from a locally cached role.
 */
@Composable
internal fun ServerPlaylistActionsRow(
    source: CatalogSourceEntry,
    hubBaseUrl: String,
    selected: Boolean,
    onOpenAccount: () -> Unit,
    onNotify: (String) -> Unit,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val handoff = remember(context) { HubBrowserHandoff(context) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var capabilities by remember(source.sourceId) {
        mutableStateOf<HubPlaylistActions>(HubPlaylistActions.Loading)
    }
    var action by remember(source.sourceId) { mutableStateOf<HubRowAction?>(null) }
    var editForm by remember(source.sourceId) { mutableStateOf<PlaylistEditForm?>(null) }
    var deleteInfo by remember(source.sourceId) { mutableStateOf<PlaylistDeleteInfo?>(null) }
    var working by remember(source.sourceId) { mutableStateOf(false) }
    var qrUrl by remember(source.sourceId) { mutableStateOf<String?>(null) }
    val rejectedMessage = stringResource(R.string.hub_handoff_rejected)
    val failedMessage = stringResource(R.string.source_load_failed)
    val refreshStartedMessage = stringResource(R.string.playlist_refreshing)
    val savedMessage = stringResource(R.string.playlist_updated)
    val deletedMessage = stringResource(R.string.playlist_removed)
    val clearedMessage = stringResource(R.string.playlist_progress_cleared)
    val clearFailedMessage = stringResource(R.string.watch_together_action_failed)
    // Fetched when the menu is first opened rather than for every row up front: this is a
    // network round trip per playlist, and most rows are never asked.
    LaunchedEffect(menuOpen, source.sourceId) {
        if (!menuOpen || capabilities is HubPlaylistActions.Ready) return@LaunchedEffect
        capabilities = HubPlaylistActions.Loading
        // A server too old to know this endpoint, or simply unreachable, must not leave the
        // menu saying "Loading" for ever. Retrying on each open is cheap and self-healing.
        capabilities = when (
            val result = OpenTvApp.graph.catalogFor(source.sourceId).playlistCapabilities()
        ) {
            is CatalogResult.Success -> HubPlaylistActions.Ready(result.value)
            else -> HubPlaylistActions.Unavailable
        }
    }

    fun openInBrowser(url: String) {
        menuOpen = false
        when (val result = handoff.open(hubBaseUrl, url)) {
            is HandoffResult.ShowQrInstead -> qrUrl = result.url
            HandoffResult.Rejected -> onNotify(rejectedMessage)
            HandoffResult.Opened -> Unit
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .background(
                if (selected) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent,
                RoundedCornerShape(10.dp),
            )
            .padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.AutoMirrored.Outlined.PlaylistPlay,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(source.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            source.itemCount?.let { count ->
                Text(stringResource(R.string.home_item_count, count), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.focusHighlight()) {
                Icon(
                    Icons.Outlined.MoreVert,
                    contentDescription = stringResource(R.string.management_playlist_actions, source.title),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                shape = OtvMenuDefaults.shape,
                containerColor = OtvMenuDefaults.containerColor,
                border = OtvMenuDefaults.border,
            ) {
                val available = when (val current = capabilities) {
                    HubPlaylistActions.Loading -> {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_loading)) },
                            enabled = false,
                            onClick = {},
                        )
                        return@DropdownMenu
                    }
                    HubPlaylistActions.Unavailable -> {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.source_load_failed)) },
                            enabled = false,
                            onClick = {},
                        )
                        return@DropdownMenu
                    }
                    is HubPlaylistActions.Ready -> current.capabilities
                }
                if (available.operations.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.shell_no_playlist_actions)) },
                        enabled = false,
                        onClick = {},
                    )
                    return@DropdownMenu
                }
                HubPlaylistMenuItem(
                    availability = available[PlaylistOperation.VIEW_PROVIDER_ACCOUNT],
                    label = stringResource(R.string.management_provider_account),
                    icon = Icons.Outlined.Person,
                    onBrowser = ::openInBrowser,
                    onInApp = { menuOpen = false; action = HubRowAction.ACCOUNT },
                )
                HubPlaylistMenuItem(
                    availability = available[PlaylistOperation.REFRESH],
                    label = stringResource(R.string.common_refresh),
                    icon = Icons.Outlined.Refresh,
                    onBrowser = ::openInBrowser,
                    onInApp = { menuOpen = false; action = HubRowAction.REFRESH },
                )
                HubPlaylistMenuItem(
                    availability = available[PlaylistOperation.EDIT],
                    label = stringResource(R.string.common_edit),
                    icon = Icons.Outlined.Edit,
                    onBrowser = ::openInBrowser,
                    onInApp = { menuOpen = false; action = HubRowAction.EDIT },
                )
                available[PlaylistOperation.CLEAR_WATCH_PROGRESS]?.let {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.playlist_clear_progress)) },
                        leadingIcon = { Icon(Icons.Outlined.Restore, contentDescription = null) },
                        onClick = { menuOpen = false; confirmClear = true },
                    )
                }
                HubPlaylistMenuItem(
                    availability = available[PlaylistOperation.DELETE],
                    label = stringResource(R.string.common_delete),
                    icon = Icons.Outlined.Delete,
                    danger = true,
                    onBrowser = ::openInBrowser,
                    onInApp = { menuOpen = false; action = HubRowAction.DELETE },
                )
            }
        }
    }

    qrUrl?.let { url ->
        UrlQrDialog(url = url, title = source.title, onDismiss = { qrUrl = null })
    }

    LaunchedEffect(action) {
        val requested = action ?: return@LaunchedEffect
        val gateway = OpenTvApp.graph.catalogFor(source.sourceId)
        when (requested) {
            HubRowAction.ACCOUNT -> {
                action = null
                onOpenAccount()
            }
            HubRowAction.EDIT -> {
                val result = gateway.playlistEditForm()
                if (result is CatalogResult.Success) {
                    editForm = result.value
                } else {
                    onNotify(failedMessage)
                    action = null
                }
            }
            HubRowAction.DELETE -> {
                val result = gateway.playlistDeleteInfo()
                if (result is CatalogResult.Success) {
                    deleteInfo = result.value
                } else {
                    onNotify(failedMessage)
                    action = null
                }
            }
            HubRowAction.REFRESH -> {
                action = null
                onNotify(refreshStartedMessage)
                OpenTvApp.graph.applicationScope.launch {
                    val result = gateway.refreshPlaylist(force = true) {}
                    if (result is CatalogResult.Success) onChanged()
                    onNotify(
                        if (result is CatalogResult.Success) {
                            resources.getString(playlistRefreshMessage(result.value.epg))
                        } else {
                            failedMessage
                        },
                    )
                }
            }
        }
    }

    editForm?.let { form ->
        HubPlaylistEditDialog(
            form = form,
            busy = working,
            onDismiss = { editForm = null; action = null },
            onSave = { update ->
                working = true
                OpenTvApp.graph.applicationScope.launch {
                    val saved = OpenTvApp.graph.catalogFor(source.sourceId).updatePlaylist(update)
                    working = false
                    editForm = null
                    action = null
                    if (saved is CatalogResult.Success) onChanged()
                    onNotify(if (saved is CatalogResult.Success) savedMessage else failedMessage)
                }
            },
        )
    }

    deleteInfo?.let { info ->
        ConfirmDeletePlaylistDialog(
            // The server supplies this copy, so the warning cannot drift from what
            // deletion actually does on the server that performs it.
            message = info.warning,
            focusKey = info.id,
            onConfirm = {
                deleteInfo = null
                action = null
                OpenTvApp.graph.applicationScope.launch {
                    val done = OpenTvApp.graph.catalogFor(source.sourceId).deletePlaylist()
                    if (done is CatalogResult.Success) onChanged()
                    onNotify(if (done is CatalogResult.Success) deletedMessage else failedMessage)
                }
            },
            onDismiss = { deleteInfo = null; action = null },
            serverHosted = true,
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.playlist_clear_progress_title)) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ServerPlaylistNotice()
                    Text(stringResource(R.string.playlist_clear_progress_message, source.title))
                }
            },
            confirmButton = {
                OtvTextButton(
                    onClick = {
                        confirmClear = false
                        // Application-scoped: closing the panel must not cancel a write
                        // that has already left for the server.
                        OpenTvApp.graph.applicationScope.launch {
                            val gateway = OpenTvApp.graph.catalogFor(source.sourceId)
                            val cleared = gateway.clearWatchProgress() is CatalogResult.Success
                            onNotify(if (cleared) clearedMessage else clearFailedMessage)
                        }
                    },
                ) { Text(stringResource(R.string.playlist_clear_progress)) }
            },
            dismissButton = {
                OtvTextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

/** What we know about a hub playlist's operations: still asking, told, or could not ask. */
private sealed interface HubPlaylistActions {
    data object Loading : HubPlaylistActions
    data object Unavailable : HubPlaylistActions
    data class Ready(val capabilities: PlaylistCapabilities) : HubPlaylistActions
}

/** Renders one capability, marking the ones that leave for the server's own web pages. */
@Composable
private fun HubPlaylistMenuItem(
    availability: PlaylistOperationAvailability?,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    danger: Boolean = false,
    onBrowser: (String) -> Unit,
    onInApp: () -> Unit = {},
) {
    // Absent means the server does not offer this to this user, so draw nothing at all.
    if (availability == null) return
    val url = (availability as? PlaylistOperationAvailability.Browser)?.url
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        trailingIcon = if (url == null) {
            null
        } else {
            {
                Icon(
                    Icons.AutoMirrored.Outlined.OpenInNew,
                    contentDescription = stringResource(R.string.hub_opens_in_browser),
                    modifier = Modifier.size(16.dp),
                )
            }
        },
        colors = if (danger) {
            MenuDefaults.itemColors(
                textColor = MaterialTheme.colorScheme.error,
                leadingIconColor = MaterialTheme.colorScheme.error,
            )
        } else {
            MenuDefaults.itemColors()
        },
        onClick = { if (url != null) onBrowser(url) else onInApp() },
    )
}

/** Which management dialog a hub playlist row currently has open. */
private enum class HubRowAction { ACCOUNT, REFRESH, EDIT, DELETE }
