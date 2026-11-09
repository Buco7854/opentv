package com.buco7854.opentv.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.buco7854.opentv.OpenTvApp
import com.buco7854.opentv.R
import com.buco7854.opentv.core.model.HubSource
import com.buco7854.opentv.core.model.Playlist
import com.buco7854.opentv.ui.components.ConfirmDeletePlaylistDialog
import com.buco7854.opentv.ui.components.RequestInitialFocusOnTv
import com.buco7854.opentv.ui.components.OtvMenuDefaults
import com.buco7854.opentv.ui.components.OtvTextButton
import com.buco7854.opentv.ui.components.OtvProgressBar
import com.buco7854.opentv.ui.components.PlaylistDialog
import com.buco7854.opentv.ui.components.focusHighlight
import com.buco7854.opentv.ui.home.HomeViewModel
import com.buco7854.opentv.source.PlaylistEpgRefreshOutcome
import com.buco7854.opentv.source.SourceId
import java.text.DateFormat
import java.util.Date

/** Shared press treatment for dock/panel items: pressed shows the same rounded pill as active (no rectangular ripple). */
@Composable
private fun Modifier.pressablePill(
    active: Boolean,
    enabled: Boolean = true,
    shape: RoundedCornerShape = RoundedCornerShape(10.dp),
    activeAlpha: Float = 0.08f,
    onClick: () -> Unit,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    return this
        .clip(shape)
        .background(
            if (active || pressed) MaterialTheme.colorScheme.onSurface.copy(alpha = activeAlpha)
            else androidx.compose.ui.graphics.Color.Transparent,
        )
        .clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            onClick = onClick,
        )
}

/** Floating panel over the dock: manage playlists, plus Downloads, Settings and the error log. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistsPanel(
    activeSourceId: SourceId?,
    onDismiss: () -> Unit,
    onOpenAccount: (SourceId) -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLog: () -> Unit,
    onConnectHub: () -> Unit,
    onOpenHub: (Long) -> Unit,
    onSignInHub: (Long) -> Unit,
    viewModel: HomeViewModel = viewModel(),
) {
    val playlists by viewModel.playlists.collectAsStateWithLifecycle(initialValue = null)
    val catalogSources by viewModel.catalogSources.collectAsStateWithLifecycle()
    val catalogSourcesLoading by viewModel.catalogSourcesLoading.collectAsStateWithLifecycle()
    val unreachableHubIds by viewModel.unreachableHubIds.collectAsStateWithLifecycle()
    val hubs by OpenTvApp.graph.hubAccounts.sources.collectAsStateWithLifecycle(initialValue = emptyList())
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val addFocusRequester = remember { FocusRequester() }
    RequestInitialFocusOnTv(addFocusRequester)

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Playlist?>(null) }
    var pendingDelete by remember { mutableStateOf<Playlist?>(null) }
    var pendingClearProgress by remember { mutableStateOf<Playlist?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        if (panelSourcesLoading(busy, playlists != null, catalogSourcesLoading)) {
            OtvProgressBar(Modifier.fillMaxWidth().padding(horizontal = 20.dp))
        }
        LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
            // Both headers live inside the list so they share its content padding and
            // line up with each other; one outside and one inside sat 12dp apart.
            item {
                PanelSectionHeader(
                    title = stringResource(R.string.management_on_device),
                    addDescription = stringResource(R.string.management_add_local),
                    onAdd = { showAdd = true },
                    addFocusRequester = addFocusRequester,
                )
            }
            items(playlists.orEmpty(), key = { it.id }) { playlist ->
                PanelPlaylistRow(
                    playlist = playlist,
                    selected = SourceId.LocalPlaylist(playlist.id) == activeSourceId,
                    refreshEnabled = !busy,
                    onRefresh = { viewModel.refresh(playlist.id) },
                    onOpenAccount = { onOpenAccount(SourceId.LocalPlaylist(playlist.id)) },
                    onEdit = { editing = playlist },
                    onClearProgress = { pendingClearProgress = playlist },
                    onDelete = { pendingDelete = playlist },
                )
            }
            item {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                PanelSectionHeader(
                    title = stringResource(R.string.management_servers),
                    addDescription = stringResource(R.string.home_server_title),
                    onAdd = onConnectHub,
                )
            }
            items(hubs, key = { "hub-${it.id}" }) { hub ->
                // Signing out clears the stored identity, so an absent user id is this
                // server saying it no longer has a session -- show that plainly and send
                // the row to sign-in rather than to settings for an account we do not have.
                ConnectedServerCard(
                    hub = hub,
                    playlistCount = if (catalogSourcesLoading || hub.id in unreachableHubIds) null else catalogSources.count {
                        (it.sourceId as? SourceId.Hub)?.hubId == hub.id
                    },
                    onOpen = { onOpenHub(hub.id) },
                    onSignIn = { onSignInHub(hub.id) },
                    unreachable = hub.id in unreachableHubIds,
                )

            }
            item {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
            item {
                PanelActionRow(Icons.Outlined.Download, stringResource(R.string.common_downloads), onOpenDownloads)
            }
            item {
                PanelActionRow(Icons.Outlined.Settings, stringResource(R.string.common_settings), onOpenSettings)
            }
            item {
                PanelActionRow(Icons.Outlined.BugReport, stringResource(R.string.common_error_log), onOpenLog)
                Spacer(Modifier.height(12.dp))
            }
        }
        SnackbarHost(snackbar)
    }

    if (showAdd || editing != null) {
        PlaylistDialog(
            editing = editing,
            onDismiss = { showAdd = false; editing = null },
            onSubmitUrl = { id, name, url, epg ->
                showAdd = false; editing = null
                if (id == null) viewModel.addFromUrl(name, url, epg) else viewModel.editUrl(id, name, url, epg)
            },
            onSubmitXtream = { id, name, server, user, pass ->
                showAdd = false; editing = null
                if (id == null) viewModel.addXtream(name, server, user, pass)
                else viewModel.editXtream(id, name, server, user, pass)
            },
            onSubmitFile = { id, name, uri ->
                showAdd = false; editing = null
                if (id == null) viewModel.addFromFile(name, uri) else viewModel.replaceFile(id, name, uri)
            },
            onRename = { id, name ->
                showAdd = false; editing = null
                viewModel.rename(id, name)
            },
        )
    }

    pendingDelete?.let { playlist ->
        ConfirmDeletePlaylistDialog(
            message = stringResource(R.string.playlist_delete_text, playlist.name),
            focusKey = playlist.id,
            onConfirm = { viewModel.delete(playlist.id); pendingDelete = null },
            onDismiss = { pendingDelete = null },
        )
    }

    pendingClearProgress?.let { playlist ->
        AlertDialog(
            onDismissRequest = { pendingClearProgress = null },
            title = { Text(stringResource(R.string.playlist_clear_progress_title)) },
            text = {
                Text(
                    stringResource(R.string.playlist_clear_progress_message, playlist.name),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                OtvTextButton(onClick = { viewModel.clearProgress(playlist.id); pendingClearProgress = null }, danger = true) {
                    Text(stringResource(R.string.playlist_clear_progress))
                }
            },
            dismissButton = {
                OtvTextButton(onClick = { pendingClearProgress = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

/**
 * The source sheet must not present an empty list while Room or a connected hub is still
 * answering its first query. That transient state looks exactly like data loss on cold start.
 */
internal fun panelSourcesLoading(
    busy: Boolean,
    localPlaylistsLoaded: Boolean,
    catalogSourcesLoading: Boolean,
): Boolean = busy || !localPlaylistsLoaded || catalogSourcesLoading

@Composable
private fun PanelPlaylistRow(
    playlist: Playlist,
    selected: Boolean,
    refreshEnabled: Boolean,
    onRefresh: () -> Unit,
    onOpenAccount: () -> Unit,
    onEdit: () -> Unit,
    onClearProgress: () -> Unit,
    onDelete: () -> Unit,
) {
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
        Column(Modifier.weight(1f)) {
            Text(
                playlist.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val itemsText = pluralStringResource(
                R.plurals.shell_item_count, playlist.channelCount, playlist.channelCount,
            )
            val updatedText = playlist.lastRefreshedMs.takeIf { it > 0 }?.let {
                stringResource(
                    R.string.shell_updated,
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)),
                )
            }
            Text(
                listOfNotNull(itemsText, updatedText).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // One overflow menu instead of a row of icons, so the row stays readable.
        var menuOpen by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.focusHighlight()) {
                Icon(
                    Icons.Outlined.MoreVert,
                    contentDescription = stringResource(R.string.shell_playlist_actions),
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
                if (playlist.xtreamBase != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.account_title)) },
                        leadingIcon = { Icon(Icons.Outlined.Person, contentDescription = null) },
                        onClick = { menuOpen = false; onOpenAccount() },
                    )
                }
                // Only plain file imports have nothing to re-fetch.
                if (playlist.url != null || playlist.xtreamBase != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.common_refresh)) },
                        leadingIcon = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
                        enabled = refreshEnabled,
                        onClick = { menuOpen = false; onRefresh() },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.common_edit)) },
                    leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                    onClick = { menuOpen = false; onEdit() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.playlist_clear_progress)) },
                    leadingIcon = { Icon(Icons.Outlined.Restore, contentDescription = null) },
                    onClick = { menuOpen = false; onClearProgress() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.common_delete)) },
                    leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                    colors = MenuDefaults.itemColors(
                        textColor = MaterialTheme.colorScheme.error,
                        leadingIconColor = MaterialTheme.colorScheme.error,
                    ),
                    onClick = { menuOpen = false; onDelete() },
                )
            }
        }
    }
}

/** Creation actions are labeled so they cannot be mistaken for source-selection controls. */
@Composable
private fun PanelSectionHeader(
    title: String,
    addDescription: String,
    onAdd: () -> Unit,
    addFocusRequester: FocusRequester? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        OtvTextButton(
            onClick = onAdd,
            modifier = Modifier.then(addFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier),
        ) { Text(addDescription) }
    }
}

/** One server is one destination; its playlists belong on its own management page. */
@Composable
internal fun ConnectedServerCard(hub: HubSource, playlistCount: Int?, onOpen: () -> Unit, onSignIn: () -> Unit, unreachable: Boolean = false) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(hub.name, style = MaterialTheme.typography.titleMedium)
        Text(hub.baseUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            if (hub.userId == null) stringResource(R.string.hub_status_signed_out)
            else stringResource(R.string.hub_signed_in_as, hub.username.orEmpty()),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (unreachable) Text(stringResource(R.string.management_server_unreachable), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (hub.userId != null && playlistCount != null) Text(
            pluralStringResource(R.plurals.management_playlist_count, playlistCount, playlistCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (hub.userId == null) OtvTextButton(onClick = onSignIn) { Text(stringResource(R.string.hub_sign_in_again)) }
        OtvTextButton(onClick = onOpen) {
            Text(stringResource(R.string.management_open_server))
            Spacer(Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(16.dp))
        }
    }
}


internal fun playlistRefreshMessage(outcome: PlaylistEpgRefreshOutcome): Int = when (outcome) {
    PlaylistEpgRefreshOutcome.SUCCEEDED -> R.string.playlist_refreshed
    PlaylistEpgRefreshOutcome.FAILED -> R.string.playlist_refreshed_guide_failed
    PlaylistEpgRefreshOutcome.NOT_CONFIGURED -> R.string.playlist_refreshed_without_guide
}

@Composable
private fun PanelActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressablePill(active = false, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.titleSmall)
    }
}
