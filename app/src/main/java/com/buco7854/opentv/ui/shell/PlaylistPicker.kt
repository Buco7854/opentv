package com.buco7854.opentv.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.buco7854.opentv.R
import com.buco7854.opentv.source.SourceId
import com.buco7854.opentv.source.encode
import com.buco7854.opentv.ui.components.OtvTextButton
import com.buco7854.opentv.ui.components.RequestInitialFocusOnTv
import com.buco7854.opentv.ui.components.focusHighlight
import com.buco7854.opentv.ui.home.CatalogSourceEntry
import com.buco7854.opentv.ui.home.HomeViewModel
import com.buco7854.opentv.ui.home.homeSources
import com.buco7854.opentv.ui.theme.ViewingAccent

/** A chooser for existing playlists. Creation and management stay in Home and More. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistPicker(
    selectedSource: SourceId?,
    onDismiss: () -> Unit,
    onChoose: (SourceId) -> Unit,
    viewModel: HomeViewModel = viewModel(),
) {
    val playlists by viewModel.playlists.collectAsStateWithLifecycle(initialValue = null)
    val sources by viewModel.catalogSources.collectAsStateWithLifecycle()
    val loading by viewModel.catalogSourcesLoading.collectAsStateWithLifecycle()
    val unreachable by viewModel.unreachableHubs.collectAsStateWithLifecycle()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        PlaylistPickerContent(
            sources = homeSources(playlists, sources),
            selectedSource = selectedSource,
            loading = playlists == null || loading,
            unreachableHubs = unreachable,
            onChoose = onChoose,
            onDismiss = onDismiss,
            onRetry = viewModel::retryCatalogSources,
        )
    }
}

/** The visible current source and its disclosure affordance have no creation action. */
@Composable
internal fun PlaylistSelector(
    title: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onChoose: () -> Unit,
) {
    val description = stringResource(R.string.playlist_picker_current, title)
    val interaction = remember { MutableInteractionSource() }
    val style = libraryControlStyle(false, enabled, interaction, base = MaterialTheme.colorScheme.surfaceContainer)
    Row(
        modifier.heightIn(min = 48.dp).libraryControl(style, interaction, enabled, onClick = onChoose)
            .semantics { contentDescription = description }.padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.AutoMirrored.Outlined.PlaylistPlay, null, Modifier.size(18.dp), tint = style.foreground)
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
            Text(stringResource(R.string.shell_playlists), style = MaterialTheme.typography.labelSmall, color = style.foreground)
            Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else style.foreground)
        }
        Icon(Icons.Outlined.ExpandMore, null, Modifier.size(14.dp), tint = style.foreground)
    }
}

@Composable
internal fun PlaylistPickerContent(
    sources: List<CatalogSourceEntry>,
    selectedSource: SourceId?,
    loading: Boolean,
    unreachableHubs: Int,
    onChoose: (SourceId) -> Unit,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
) {
    val closeFocus = remember { FocusRequester() }
    RequestInitialFocusOnTv(closeFocus)
    LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.playlist_picker_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                IconButton(onClick = onDismiss, modifier = Modifier.focusRequester(closeFocus).focusHighlight()) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.common_close))
                }
            }
        }
        items(sources, key = { it.sourceId.encode() }) { source ->
            val selected = source.sourceId == selectedSource
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp)
                    .focusHighlight(RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp))
                    .background(if (selected) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainer)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onChoose(source.sourceId) })
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(source.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(
                            source.hubName ?: stringResource(R.string.home_on_device),
                            source.itemCount?.let { stringResource(R.string.home_item_count, it) },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (selected) Icon(Icons.Outlined.Check, contentDescription = null, tint = ViewingAccent)
            }
        }
        if (loading) item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.common_loading), style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (unreachableHubs > 0) item {
            Column {
                Text(stringResource(R.string.home_some_servers_unreachable), style = MaterialTheme.typography.bodyMedium)
                OtvTextButton(onClick = onRetry) { Text(stringResource(R.string.hub_retry)) }
            }
        }
        if (!loading && sources.isEmpty() && unreachableHubs == 0) item {
            Text(stringResource(R.string.playlist_picker_empty), style = MaterialTheme.typography.bodyMedium)
        }
        item {
            Text(
                stringResource(R.string.playlist_picker_manage_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
