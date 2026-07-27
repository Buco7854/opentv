package com.buco7854.opentv.ui.player

import android.graphics.Typeface
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import com.buco7854.opentv.R
import com.buco7854.opentv.core.repo.GuideEntry
import com.buco7854.opentv.data.prefs.SubtitleStyle
import com.buco7854.opentv.ui.components.GuideEntryContent
import com.buco7854.opentv.ui.components.GuideHint
import com.buco7854.opentv.ui.components.SubtitleStyleControls
import com.buco7854.opentv.ui.components.guideSheetHeight
import java.util.Locale

@OptIn(UnstableApi::class)
internal fun applySubtitleStyle(subtitleView: SubtitleView, style: SubtitleStyle) {
    subtitleView.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * style.scale)
    val typeface = if (style.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    val captionStyle = if (style.background) {
        CaptionStyleCompat(
            android.graphics.Color.WHITE,
            0xB3000000.toInt(),
            android.graphics.Color.TRANSPARENT,
            CaptionStyleCompat.EDGE_TYPE_NONE,
            android.graphics.Color.TRANSPARENT,
            typeface,
        )
    } else {
        CaptionStyleCompat(
            android.graphics.Color.WHITE,
            android.graphics.Color.TRANSPARENT,
            android.graphics.Color.TRANSPARENT,
            CaptionStyleCompat.EDGE_TYPE_OUTLINE,
            android.graphics.Color.BLACK,
            typeface,
        )
    }
    subtitleView.setStyle(captionStyle)
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrackSheet(
    player: Player,
    trackType: Int,
    heading: String,
    emptyText: String,
    allowOff: Boolean,
    onDismiss: () -> Unit,
    extraContent: (@Composable () -> Unit)? = null,
) {
    var tracks by remember { mutableStateOf(player.currentTracks) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(newTracks: Tracks) {
                tracks = newTracks
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    val groups = tracks.groups.filter { it.type == trackType }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            SheetHeading(heading)
            if (allowOff) {
                val anySelected = groups.any { it.isSelected }
                TrackOption(
                    label = stringResource(R.string.player_track_off),
                    selected = !anySelected,
                ) {
                    disableSubtitles(player)
                }
            }
            if (groups.isEmpty()) {
                Text(
                    emptyText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            groups.forEach { group ->
                for (trackIndex in 0 until group.length) {
                    if (!group.isTrackSupported(trackIndex)) continue
                    TrackOption(
                        label = trackLabel(group.getTrackFormat(trackIndex))
                            ?: stringResource(R.string.player_track_n, trackIndex + 1),
                        selected = group.isTrackSelected(trackIndex),
                    ) {
                        selectTrack(player, group, trackIndex)
                    }
                }
            }
            extraContent?.invoke()
        }
    }
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SubtitleTrackSheet(
    player: Player,
    style: SubtitleStyle,
    onStyleChange: (SubtitleStyle) -> Unit,
    onDismiss: () -> Unit,
) {
    TrackSheet(
        player = player,
        trackType = C.TRACK_TYPE_TEXT,
        heading = stringResource(R.string.player_subtitles),
        emptyText = stringResource(R.string.player_no_subtitle_tracks),
        allowOff = true,
        onDismiss = onDismiss,
        extraContent = {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
            SheetHeading(stringResource(R.string.settings_appearance))
            SubtitleStyleControls(style = style, onChange = onStyleChange)
        },
    )
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpeedSheet(player: Player, onDismiss: () -> Unit) {
    val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
    var currentSpeed by remember { mutableFloatStateOf(player.playbackParameters.speed) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            SheetHeading(stringResource(R.string.player_speed))
            Row(Modifier.fillMaxWidth()) {
                speeds.forEach { speed ->
                    FilterChip(
                        selected = currentSpeed == speed,
                        onClick = {
                            player.setPlaybackSpeed(speed)
                            currentSpeed = speed
                        },
                        label = { Text(if (speed == 1f) "1×" else "${speed}×") },
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
            }
        }
    }
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerGuideSheet(
    title: String,
    entries: List<GuideEntry>?,
    onDismiss: () -> Unit,
    onReplay: (GuideEntry) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .height(guideSheetHeight)
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            SheetHeading(title)
            GuideHint(anyReplay = entries?.any { it.replayable } == true)
            Spacer(Modifier.height(8.dp))
            GuideEntryContent(
                entries = entries,
                emptyText = stringResource(R.string.guide_no_data),
                onReplay = onReplay,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SheetHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun TrackOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun trackLabel(format: Format): String? {
    val language = format.language
        ?.takeIf { it.isNotBlank() && !it.equals("und", ignoreCase = true) }
        ?.let { Locale.forLanguageTag(it).displayLanguage.replaceFirstChar { character -> character.uppercase() } }
    return format.label ?: language
}

private fun selectTrack(player: Player, group: Tracks.Group, trackIndex: Int) {
    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
        .setTrackTypeDisabled(group.type, false)
        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
        .build()
}

private fun disableSubtitles(player: Player) {
    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        .build()
}
