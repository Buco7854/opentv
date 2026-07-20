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
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.buco7854.opentv.R
import com.buco7854.opentv.core.model.ChannelKind
import com.buco7854.opentv.core.model.Playlist
import com.buco7854.opentv.ui.components.ConfirmDeletePlaylistDialog
import com.buco7854.opentv.ui.components.OtvMenuDefaults
import com.buco7854.opentv.ui.components.OtvTextButton
import com.buco7854.opentv.ui.components.OtvProgressBar
import com.buco7854.opentv.ui.components.PlaylistDialog
import com.buco7854.opentv.ui.home.HomeViewModel
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
    activePlaylistId: Long,
    onDismiss: () -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    onOpenAccount: (Long) -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLog: () -> Unit,
    viewModel: HomeViewModel = viewModel(),
) {
    val playlists by viewModel.playlists.collectAsState()
    val busy by viewModel.busy.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Playlist?>(null) }
    var pendingDelete by remember { mutableStateOf<Playlist?>(null) }
    var pendingClearProgress by remember { mutableStateOf<Playlist?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.shell_playlists), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { showAdd = true }) {
                Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.shell_add_playlist))
            }
        }
        if (busy) OtvProgressBar(Modifier.fillMaxWidth().padding(horizontal = 20.dp))
        LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
            items(playlists, key = { it.id }) { playlist ->
                PanelPlaylistRow(
                    playlist = playlist,
                    selected = playlist.id == activePlaylistId,
                    refreshEnabled = !busy,
                    onClick = { onOpenPlaylist(playlist.id) },
                    onRefresh = { viewModel.refresh(playlist.id) },
                    onOpenAccount = { onOpenAccount(playlist.id) },
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
            playlist = playlist,
            onConfirm = { viewModel.delete(playlist.id); pendingDelete = null },
            onDismiss = { pendingDelete = null },
        )
    }

    pendingClearProgress?.let { playlist ->
        AlertDialog(
            onDismissRequest = { pendingClearProgress = null },
            title = { Text(stringResource(R.string.playlist_clear_progress_title)) },
            text = { Text(stringResource(R.string.playlist_clear_progress_message, playlist.name)) },
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
