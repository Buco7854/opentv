package com.buco7854.opentv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.buco7854.opentv.R
import com.buco7854.opentv.core.model.Channel
import com.buco7854.opentv.core.repo.GuideEntry
import com.buco7854.opentv.source.CatalogGuideEntry
import com.buco7854.opentv.source.CatalogItem
import com.buco7854.opentv.source.CatalogLoadError
import com.buco7854.opentv.source.ContentRef
import com.buco7854.opentv.source.SourceId
import com.buco7854.opentv.ui.theme.Mint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Fixed 62% screen height so the sheet stays put; the programme list scrolls inside. */
val guideSheetHeight: Dp
    @Composable get() {
        val windowHeightPx = LocalWindowInfo.current.containerSize.height
        return with(LocalDensity.current) { windowHeightPx.toDp() * 0.62f }
    }

/** Channel guide bottom sheet; loads on demand and replays past programmes via catch-up. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuideSheet(
    sourceId: SourceId,
    item: CatalogItem,
    hasEpgConfigured: Boolean,
    onDismiss: () -> Unit,
    onPlayCatchup: (url: String, title: String) -> Unit,
    onPlayHubCatchup: (item: CatalogItem, entry: CatalogGuideEntry) -> Unit,
    onSignIn: () -> Unit,
    onUnavailable: () -> Unit,
) {
    val viewModel = guideViewModel(sourceId)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val entries = state.entries
    val displayEntries = entries?.map(CatalogGuideEntry::toGuideEntry)

    LaunchedEffect(item.ref) { viewModel.show(item) }
    DisposableEffect(viewModel, item.ref) {
        onDispose { viewModel.hide(item) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .height(guideSheetHeight)
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChannelLogo(item.imageUrl, kindIcon(item.kind))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        item.title,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    GuideHint(anyReplay = entries?.any { it.replayable } == true)
                }
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            when (state.error) {
                CatalogLoadError.SignedOut -> SourceSignedOut(
                    onSignIn = onSignIn,
                    modifier = Modifier.weight(1f),
                )
                CatalogLoadError.Unreachable -> SourceUnreachable(
                    onRetry = viewModel::retry,
                    modifier = Modifier.weight(1f),
                )
                is CatalogLoadError.Failed -> SourceLoadFailed(
                    message = null,
                    onRetry = viewModel::retry,
                    modifier = Modifier.weight(1f),
                )
                null -> GuideEntryContent(
                    entries = displayEntries,
                    emptyText = if (hasEpgConfigured) stringResource(R.string.guide_no_data)
                    else stringResource(R.string.guide_no_data_epg_hint),
                    onReplay = { display ->
                        val entry = entries?.firstOrNull {
                            it.startMs == display.startMs && it.endMs == display.endMs
                        } ?: return@GuideEntryContent
                        if (sourceId is SourceId.Hub) {
                            onPlayHubCatchup(item, entry)
                        } else {
                            scope.launch {
                                val url = viewModel.catchupUrlFor(entry)
                                if (url != null) {
                                    onPlayCatchup(url, "${item.title} · ${entry.title}")
                                } else {
                                    onUnavailable()
                                }
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
fun GuideSheet(
    channel: Channel,
    hasEpgConfigured: Boolean,
    onDismiss: () -> Unit,
    onPlayCatchup: (url: String, title: String) -> Unit,
    onUnavailable: () -> Unit,
) {
    GuideSheet(
        sourceId = SourceId.LocalPlaylist(channel.playlistId),
        item = CatalogItem(
            ref = ContentRef.LocalUrl(channel.url, channel.id),
            title = channel.name,
            imageUrl = channel.logo,
            kind = channel.kind,
            group = channel.groupTitle,
            seriesKey = channel.seriesKey,
            season = channel.season,
            episode = channel.episode,
            durationSecs = channel.durationSecs,
            tvgId = channel.tvgId,
            airDate = channel.airDate,
            catchupDays = channel.catchupDays,
            hasCatchup = channel.catchupDays > 0 || channel.catchupSource != null,
            hasGuide = channel.xtreamStreamId != null,
        ),
        hasEpgConfigured = hasEpgConfigured,
        onDismiss = onDismiss,
        onPlayCatchup = onPlayCatchup,
        onPlayHubCatchup = { _, _ -> },
        onSignIn = {},
        onUnavailable = onUnavailable,
    )
}

private fun CatalogGuideEntry.toGuideEntry() =
    GuideEntry(title, description, startMs, endMs, replayable)

/** Subtitle under the sheet title: catch-up hint when replays exist. */
@Composable
fun GuideHint(anyReplay: Boolean) {
    Text(
        if (anyReplay) stringResource(R.string.guide_catchup_available) else stringResource(R.string.guide_programme_guide),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Guide rows (loading / empty / day-grouped programme list) shared with the player's sheet. */
@Composable
fun GuideEntryContent(
    entries: List<GuideEntry>?,
    emptyText: String,
    onReplay: (GuideEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val list = entries
    Box(modifier) {
        when {
            list == null -> Text(
                stringResource(R.string.common_loading),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 16.dp),
            )
            list.isEmpty() -> Text(
                emptyText,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 16.dp),
            )
            else -> {
                val locale = LocalConfiguration.current.locales[0]
                val timeFormat = remember(locale) { SimpleDateFormat("HH:mm", locale) }
                val dayKeyFormat = remember { SimpleDateFormat("yyyyDDD", Locale.US) }
                val dayFormat = remember(locale) { SimpleDateFormat("EEEE d MMMM", locale) }
                val now = System.currentTimeMillis()
                val listState = rememberLazyListState()
                val expandedKeys = remember { mutableStateMapOf<Long, Boolean>() }
                val initialFocusRequester = remember { FocusRequester() }
                val television = isTelevisionUiMode(LocalConfiguration.current.uiMode)
                val anchor = list.indexOfFirst { it.endMs > now }
                    .takeIf { it >= 0 }
                    ?: (list.size - 1)
                val focusIndex = (anchor - 1).coerceAtLeast(0)
                // Open at the present, not at a week of history.
                LaunchedEffect(list) {
                    listState.scrollToItem(focusIndex)
                    withFrameNanos { }
                    if (television) initialFocusRequester.requestFocus()
                }
                LazyColumn(state = listState) {
                    itemsIndexed(list) { i, entry ->
                        Column {
                            val day = dayKeyFormat.format(Date(entry.startMs))
                            if (i == 0 || day != dayKeyFormat.format(Date(list[i - 1].startMs))) {
                                DayHeader(dayLabel(entry.startMs, now, dayKeyFormat, dayFormat))
                            }
                            GuideRow(
                                entry = entry,
                                timeFormat = timeFormat,
                                now = now,
                                expanded = expandedKeys[entry.startMs] == true,
                                onToggleExpand = {
                                    expandedKeys[entry.startMs] = expandedKeys[entry.startMs] != true
                                },
                                onReplay = onReplay,
                                modifier = if (i == focusIndex) {
                                    Modifier.focusRequester(initialFocusRequester)
                                } else {
                                    Modifier
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun dayLabel(
    ms: Long,
    now: Long,
    keyFormat: SimpleDateFormat,
    dayFormat: SimpleDateFormat,
): String = when (keyFormat.format(Date(ms))) {
    keyFormat.format(Date(now)) -> stringResource(R.string.guide_today)
    keyFormat.format(Date(now - 86_400_000)) -> stringResource(R.string.guide_yesterday)
    keyFormat.format(Date(now + 86_400_000)) -> stringResource(R.string.guide_tomorrow)
    else -> dayFormat.format(Date(ms))
}

@Composable
private fun DayHeader(label: String) {
    Column(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 2.dp, start = 8.dp, end = 8.dp)) {
        Text(
            label.uppercase(LocalConfiguration.current.locales[0]),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun GuideRow(
    entry: GuideEntry,
    timeFormat: SimpleDateFormat,
    now: Long,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onReplay: (GuideEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isNow = entry.startMs <= now && entry.endMs > now
    val isPast = entry.endMs <= now
    Row(
        modifier
            .fillMaxWidth()
            .focusHighlight(RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(if (isNow) Mint.copy(alpha = 0.09f) else Color.Transparent)
            .then(
                when {
                    // Replayable rows keep tap-to-replay; the trailing chevron expands.
                    entry.replayable -> Modifier.clickable { onReplay(entry) }
                    entry.description != null -> Modifier.clickable { onToggleExpand() }
                    else -> Modifier
                }
            )
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            timeFormat.format(Date(entry.startMs)),
            style = MaterialTheme.typography.labelLarge,
            color = if (isNow) Mint else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(56.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                entry.title,
                style = MaterialTheme.typography.bodyMedium,
                color = when {
                    isNow -> Mint
                    isPast && !entry.replayable -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
            entry.description?.let {
                // Tapping the description toggles clamp/expand, leaving the row's tap-to-replay intact.
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .focusHighlight(RoundedCornerShape(4.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onToggleExpand() },
                )
            }
        }
        if (isNow) {
            Text(
                stringResource(R.string.guide_now),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .background(Mint, RoundedCornerShape(999.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        if (entry.replayable) {
            Icon(
                Icons.Outlined.Replay,
                contentDescription = stringResource(R.string.guide_replay),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
