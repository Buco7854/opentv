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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.buco7854.opentv.OpenTvApp
import com.buco7854.opentv.R
import com.buco7854.opentv.core.model.Channel
import com.buco7854.opentv.core.repo.GuideEntry
import com.buco7854.opentv.ui.theme.Mint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Fixed 62% screen height so the sheet stays put; the programme list scrolls inside. */
val guideSheetHeight: Dp
    @Composable get() = (LocalConfiguration.current.screenHeightDp * 0.62f).dp

/** Channel guide bottom sheet; loads on demand and replays past programmes via catch-up. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuideSheet(
    channel: Channel,
    hasEpgConfigured: Boolean,
    onDismiss: () -> Unit,
    onPlayCatchup: (url: String, title: String) -> Unit,
    onUnavailable: () -> Unit,
) {
    var entries by remember(channel.id) { mutableStateOf<List<GuideEntry>?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(channel.id) { entries = OpenTvApp.graph.xtream.guideFor(channel) }

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
                ChannelLogo(channel.logo, kindIcon(channel.kind))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        channel.name,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    GuideHint(anyReplay = entries?.any { it.replayable } == true)
                }
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            GuideEntryContent(
                entries = entries,
                emptyText = if (hasEpgConfigured) stringResource(R.string.guide_no_data)
                else stringResource(R.string.guide_no_data_epg_hint),
                onReplay = { entry ->
                    scope.launch {
                        val url = OpenTvApp.graph.xtream.catchupUrlFor(channel, entry.startMs, entry.endMs)
                        if (url != null) onPlayCatchup(url, "${channel.name} · ${entry.title}")
                        else onUnavailable()
                    }
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

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
                val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
                val dayKeyFormat = remember { SimpleDateFormat("yyyyDDD", Locale.US) }
                val dayFormat = remember { SimpleDateFormat("EEEE d MMMM", Locale.getDefault()) }
                val now = System.currentTimeMillis()
                val listState = rememberLazyListState()
                val expandedKeys = remember { mutableStateMapOf<Long, Boolean>() }
                // Open at the present, not at a week of history.
                LaunchedEffect(list) {
                    val anchor = list.indexOfFirst { it.endMs > now }.takeIf { it >= 0 } ?: (list.size - 1)
                    listState.scrollToItem((anchor - 1).coerceAtLeast(0))
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
            label.uppercase(Locale.getDefault()),
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
) {
    val isNow = entry.startMs <= now && entry.endMs > now
    val isPast = entry.endMs <= now
    Row(
        Modifier
            .fillMaxWidth()
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
                    modifier = Modifier.clickable(
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
