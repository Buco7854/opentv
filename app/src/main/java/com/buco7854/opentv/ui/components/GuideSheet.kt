package com.buco7854.opentv.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.buco7854.opentv.OpenTvApp
import com.buco7854.opentv.data.db.ChannelEntity
import com.buco7854.opentv.data.repo.GuideEntry
import com.buco7854.opentv.data.repo.hasCatchup
import com.buco7854.opentv.ui.theme.Mint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Channel guide bottom sheet, shared by browse, search and the player. Loads
 * the guide on demand (one request per channel-open, cached in the repo) and
 * replays past programmes via catch-up when available.
 *
 * @param onPlayCatchup receives a resolved catch-up URL and a display title.
 * @param onUnavailable called when a tapped programme has no usable catch-up.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuideSheet(
    channel: ChannelEntity,
    hasEpgConfigured: Boolean,
    onDismiss: () -> Unit,
    onPlayCatchup: (url: String, title: String) -> Unit,
    onUnavailable: () -> Unit,
) {
    var entries by remember(channel.id) { mutableStateOf<List<GuideEntry>?>(null) }
    val scope = rememberCoroutineScope()
    val hasCatchup = channel.hasCatchup() || channel.xtreamStreamId != null
    LaunchedEffect(channel.id) { entries = OpenTvApp.graph.xtream.guideFor(channel) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(channel.name, style = MaterialTheme.typography.titleLarge)
            val list = entries
            val replayPresent = list?.any { it.replayable } == true
            if (replayPresent) {
                Text(
                    "Catch-up available. Tap a past programme to replay it",
                    style = MaterialTheme.typography.bodySmall,
                    color = Mint,
                )
            }
            Spacer(Modifier.height(12.dp))
            when {
                list == null -> Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                list.isEmpty() -> Text(
                    if (hasEpgConfigured) "No guide data for this channel."
                    else "No guide data for this channel. Add an XMLTV EPG URL to your playlist.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> {
                    val anyReplay = list.any { it.replayable }
                    val timeFormat = SimpleDateFormat(
                        if (anyReplay) "EEE HH:mm" else "HH:mm", Locale.getDefault(),
                    )
                    val now = System.currentTimeMillis()
                    LazyColumn {
                        itemsIndexed(list) { _, entry ->
                            val isNow = entry.startMs <= now && entry.endMs > now
                            val isPast = entry.endMs <= now
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .then(
                                        if (entry.replayable) Modifier.clickable {
                                            scope.launch {
                                                val url = OpenTvApp.graph.xtream
                                                    .catchupUrlFor(channel, entry.startMs, entry.endMs)
                                                if (url != null) {
                                                    onPlayCatchup(url, "${channel.name} · ${entry.title}")
                                                } else onUnavailable()
                                            }
                                        } else Modifier
                                    )
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    timeFormat.format(Date(entry.startMs)),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (isNow) Mint else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.width(if (anyReplay) 88.dp else 64.dp),
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
                                        Text(
                                            it,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                if (entry.replayable) {
                                    Icon(
                                        Icons.Rounded.Replay,
                                        contentDescription = "Replay",
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
