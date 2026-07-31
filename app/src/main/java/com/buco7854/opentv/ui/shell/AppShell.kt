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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.buco7854.opentv.OpenTvApp
import com.buco7854.opentv.R
import com.buco7854.opentv.core.model.ChannelKind
import com.buco7854.opentv.core.model.HubSource
import com.buco7854.opentv.core.model.Playlist
import com.buco7854.opentv.ui.components.ConfirmDeletePlaylistDialog
import com.buco7854.opentv.ui.components.RequestInitialFocusOnTv
import com.buco7854.opentv.ui.components.OtvMenuDefaults
import com.buco7854.opentv.ui.components.OtvTextButton
import com.buco7854.opentv.ui.components.OtvProgressBar
import com.buco7854.opentv.ui.components.PlaylistDialog
import com.buco7854.opentv.ui.components.ServerPlaylistNotice
import com.buco7854.opentv.ui.components.focusHighlight
import com.buco7854.opentv.ui.hub.HandoffResult
import com.buco7854.opentv.ui.hub.HubBrowserHandoff
import com.buco7854.opentv.ui.hub.HubPlaylistEditDialog
import com.buco7854.opentv.ui.home.HomeViewModel
import com.buco7854.opentv.source.CatalogResult
import com.buco7854.opentv.source.PlaylistCapabilities
import com.buco7854.opentv.source.PlaylistDeleteInfo
import com.buco7854.opentv.source.PlaylistEditForm
import com.buco7854.opentv.source.PlaylistOperation
import com.buco7854.opentv.source.PlaylistOperationAvailability
import com.buco7854.opentv.source.SourceId
import com.buco7854.opentv.ui.home.CatalogSourceEntry
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Persistent bottom dock: burger opens the playlists panel, center icons are the active playlist's sections. */
@Composable
fun OpenTvDock(
    hasActivePlaylist: Boolean,
    activeSection: DockSection?,
    onOpenPanel: () -> Unit,
    onSection: (DockSection) -> Unit,
) {
    Column(Modifier.background(MaterialTheme.colorScheme.background)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(64.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DockButton(
                icon = { Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.shell_playlists_and_more)) },
                active = false,
                enabled = true,
                onClick = onOpenPanel,
            )
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DockSection.entries.forEach { section ->
                    DockButton(
                        icon = { Icon(section.icon, contentDescription = stringResource(section.labelRes)) },
                        active = activeSection == section,
                        enabled = hasActivePlaylist,
                        onClick = { onSection(section) },
                    )
                }
            }
            // Symmetry spacer matching the burger button.
            Spacer(Modifier.width(48.dp))
        }
    }
}

enum class DockSection(val labelRes: Int) {
    LIVE(R.string.common_live),
    MOVIES(R.string.common_movies),
    SERIES(R.string.common_series),
    FAVORITES(R.string.common_favorites),
    SEARCH(R.string.common_search);

    val icon get() = when (this) {
        LIVE -> Icons.Outlined.LiveTv
        MOVIES -> Icons.Outlined.Movie
        SERIES -> Icons.Outlined.VideoLibrary
        FAVORITES -> Icons.Outlined.FavoriteBorder
        SEARCH -> Icons.Outlined.Search
    }

    /** Browse tab index for the three content sections. */
    val tab get() = when (this) {
        LIVE -> ChannelKind.LIVE
        MOVIES -> ChannelKind.MOVIE
        SERIES -> ChannelKind.SERIES
        else -> -1
    }
}

@Composable
private fun DockButton(
    icon: @Composable () -> Unit,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tint = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        active -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .size(width = 48.dp, height = 48.dp)
            .pressablePill(active = active, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides tint,
            content = icon,
        )
    }
}

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
    onOpenSource: (SourceId) -> Unit,
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
    val hubs by OpenTvApp.graph.hubAccounts.sources.collectAsStateWithLifecycle(initialValue = emptyList())
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
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
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        if (busy) OtvProgressBar(Modifier.fillMaxWidth().padding(horizontal = 20.dp))
        LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
            // Both headers live inside the list so they share its content padding and
            // line up with each other; one outside and one inside sat 12dp apart.
            item {
                PanelSectionHeader(
                    title = stringResource(R.string.shell_playlists),
                    addDescription = stringResource(R.string.shell_add_playlist),
                    onAdd = { showAdd = true },
                    addFocusRequester = addFocusRequester,
                )
            }
            items(playlists.orEmpty(), key = { it.id }) { playlist ->
                PanelPlaylistRow(
                    playlist = playlist,
                    selected = SourceId.LocalPlaylist(playlist.id) == activeSourceId,
                    refreshEnabled = !busy,
                    onClick = { onOpenSource(SourceId.LocalPlaylist(playlist.id)) },
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
                // Servers get the same header-plus-plus affordance as playlists. Adding one
                // used to be a row carrying the very same icon as the servers listed above
                // it, which read as another server rather than as the way to add one.
                PanelSectionHeader(
                    title = stringResource(R.string.shell_servers),
                    addDescription = stringResource(R.string.hub_add_title),
                    onAdd = onConnectHub,
                )
            }
            items(hubs, key = { "hub-${it.id}" }) { hub ->
                // Signing out clears the stored identity, so an absent user id is this
                // server saying it no longer has a session -- show that plainly and send
                // the row to sign-in rather than to settings for an account we do not have.
                val signedOut = hub.userId == null
                PanelHubRow(
                    hub = hub,
                    signedOut = signedOut,
                    onClick = { if (signedOut) onSignInHub(hub.id) else onOpenHub(hub.id) },
                )
                if (signedOut) return@items
                catalogSources.filter {
                    (it.sourceId as? SourceId.Hub)?.hubId == hub.id
                }.forEach { source ->
                    PanelHubPlaylistRow(
                        source = source,
                        hubBaseUrl = hub.baseUrl,
                        selected = source.sourceId == activeSourceId,
                        onClick = { onOpenSource(source.sourceId) },
                        onOpenAccount = { onOpenAccount(source.sourceId) },
                        onNotify = { message -> scope.launch { snackbar.showSnackbar(message) } },
                    )
                }
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
            onConnectHub = { showAdd = false; editing = null; onConnectHub() },
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

@Composable
private fun PanelPlaylistRow(
    playlist: Playlist,
    selected: Boolean,
    refreshEnabled: Boolean,
    onClick: () -> Unit,
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
            .pressablePill(active = selected, activeAlpha = 0.12f, onClick = onClick)
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
            IconButton(onClick = { menuOpen = true }) {
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

/** "Playlists" and "Servers" both read as a title with a plus to add one of that thing. */
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
        IconButton(
            onClick = onAdd,
            modifier = Modifier
                .then(addFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
                .focusHighlight(),
        ) {
            Icon(Icons.Outlined.Add, contentDescription = addDescription)
        }
    }
}

@Composable
private fun PanelHubRow(hub: HubSource, signedOut: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusHighlight(RoundedCornerShape(10.dp))
            .pressablePill(active = false, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Dns,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(hub.name, style = MaterialTheme.typography.titleSmall)
            val subtitle = if (signedOut) {
                stringResource(R.string.hub_status_signed_out)
            } else {
                hub.username
            }
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (signedOut) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/**
 * A server-backed playlist row.
 *
 * It offers the same operations a local playlist does, but the server decides which:
 * clearing your own watch progress is yours to do, while refreshing, editing or deleting
 * change a catalog everyone on that server shares, so they belong to an administrator and
 * open the server's own pages. The capability list is fetched per playlist and is never
 * inferred from a locally cached role — the server enforces it regardless of what we draw.
 */
@Composable
private fun PanelHubPlaylistRow(
    source: CatalogSourceEntry,
    hubBaseUrl: String,
    selected: Boolean,
    onClick: () -> Unit,
    onOpenAccount: () -> Unit,
    onNotify: (String) -> Unit,
) {
    val context = LocalContext.current
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
    val rejectedMessage = stringResource(R.string.hub_handoff_rejected)
    val failedMessage = stringResource(R.string.source_load_failed)
    val refreshStartedMessage = stringResource(R.string.playlist_refreshing)
    val refreshedMessage = stringResource(R.string.playlist_refreshed)
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
        if (handoff.open(hubBaseUrl, url) == HandoffResult.Rejected) {
            onNotify(rejectedMessage)
        }
    }

    Row(
        // Same metrics as a local playlist row -- only the leading indent differs, to show
        // these belong to the server above them.
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, top = 2.dp, bottom = 2.dp)
            .focusHighlight(RoundedCornerShape(10.dp))
            .pressablePill(active = selected, activeAlpha = 0.12f, onClick = onClick)
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
        Text(
            source.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Box {
            IconButton(onClick = { menuOpen = true }) {
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
                    label = stringResource(R.string.account_title),
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
                    onNotify(
                        if (result is CatalogResult.Success) {
                            refreshedMessage
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
