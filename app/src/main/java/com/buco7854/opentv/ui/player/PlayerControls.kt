package com.buco7854.opentv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.buco7854.opentv.R
import com.buco7854.opentv.ui.components.OtvButtonShape

internal data class PlayerChromeState(
    val title: String,
    val subtitleLine: String?,
    val isLive: Boolean,
    val playing: Boolean,
    val buffering: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val scrubFraction: Float?,
    val showGuide: Boolean,
    val pipSupported: Boolean,
)

internal data class PlayerChromeActions(
    val onBack: () -> Unit,
    val onInteraction: () -> Unit,
    val onTogglePlayback: () -> Unit,
    val onSeekBack: () -> Unit,
    val onSeekForward: () -> Unit,
    val onScrub: (Float) -> Unit,
    val onScrubFinished: () -> Unit,
    val onOpenGuide: () -> Unit,
    val onOpenAudio: () -> Unit,
    val onOpenSubtitles: () -> Unit,
    val onOpenSpeed: () -> Unit,
    val onChangeScale: () -> Unit,
    val onEnterPip: () -> Unit,
    val onRotate: () -> Unit,
)

@Composable
internal fun PlayerChrome(
    state: PlayerChromeState,
    actions: PlayerChromeActions,
) {
    Box(Modifier.fillMaxSize()) {
        PlayerTopBar(
            title = state.title,
            subtitleLine = state.subtitleLine,
            showGuide = state.showGuide,
            onBack = actions.onBack,
            onOpenGuide = actions.onOpenGuide,
            onInteraction = actions.onInteraction,
            modifier = Modifier.align(Alignment.TopStart),
        )
        PlayerTransportControls(
            isLive = state.isLive,
            playing = state.playing,
            buffering = state.buffering,
            onTogglePlayback = actions.onTogglePlayback,
            onSeekBack = actions.onSeekBack,
            onSeekForward = actions.onSeekForward,
            modifier = Modifier.align(Alignment.Center),
        )
        PlayerBottomBar(
            state = state,
            actions = actions,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun PlayerTopBar(
    title: String,
    subtitleLine: String?,
    showGuide: Boolean,
    onBack: () -> Unit,
    onOpenGuide: () -> Unit,
    onInteraction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)))
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            )
            .pointerInput(Unit) { detectTapGestures { onInteraction() } }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerIconButton(PlayerGlyphs.Back, stringResource(R.string.common_back), onBack)
        Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
            Text(
                title.uppercase(LocalConfiguration.current.locales[0]),
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.8.sp,
                ),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subtitleLine?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (showGuide) {
            PlayerIconButton(
                PlayerGlyphs.Calendar,
                stringResource(R.string.guide_open),
                onOpenGuide,
            )
        }
        PlayerIconButton(PlayerGlyphs.Close, stringResource(R.string.player_stop), onBack)
    }
}

@Composable
private fun PlayerTransportControls(
    isLive: Boolean,
    playing: Boolean,
    buffering: Boolean,
    onTogglePlayback: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!isLive) {
            TransportButton(PlayerGlyphs.Replay, stringResource(R.string.player_rewind), onSeekBack)
        }
        IconButton(onClick = onTogglePlayback, modifier = Modifier.size(68.dp)) {
            if (buffering) {
                CircularProgressIndicator(
                    modifier = Modifier.size(40.dp),
                    color = Color.White,
                    strokeWidth = 3.dp,
                )
            } else {
                Icon(
                    if (playing) PlayerGlyphs.Pause else PlayerGlyphs.Play,
                    contentDescription = stringResource(
                        if (playing) R.string.common_pause else R.string.common_play,
                    ),
                    tint = Color.White,
                    modifier = Modifier.size(40.dp),
                )
            }
        }
        if (!isLive) {
            TransportButton(PlayerGlyphs.Forward, stringResource(R.string.player_forward), onSeekForward)
        }
    }
}

@Composable
private fun PlayerBottomBar(
    state: PlayerChromeState,
    actions: PlayerChromeActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f))))
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
            )
            .pointerInput(Unit) { detectTapGestures { actions.onInteraction() } }
            .padding(horizontal = 16.dp)
            .padding(top = 24.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (!state.isLive) {
            PlayerSeekBar(
                positionMs = state.positionMs,
                durationMs = state.durationMs,
                scrubFraction = state.scrubFraction,
                onScrub = actions.onScrub,
                onScrubFinished = actions.onScrubFinished,
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (state.isLive) Arrangement.Start else Arrangement.Center,
        ) {
            if (state.isLive) {
                LiveChip(Modifier.padding(start = 2.dp))
                Spacer(Modifier.weight(1f))
            }
            PlayerIconButton(
                PlayerGlyphs.Audio,
                stringResource(R.string.player_audio_track),
                actions.onOpenAudio,
            )
            PlayerIconButton(
                PlayerGlyphs.Subtitles,
                stringResource(R.string.player_subtitles),
                actions.onOpenSubtitles,
            )
            if (!state.isLive) {
                PlayerIconButton(
                    PlayerGlyphs.Speed,
                    stringResource(R.string.player_speed),
                    actions.onOpenSpeed,
                )
            }
            PlayerIconButton(
                PlayerGlyphs.Aspect,
                stringResource(R.string.player_scaling),
                actions.onChangeScale,
            )
            if (state.pipSupported) {
                PlayerIconButton(
                    PlayerGlyphs.Pip,
                    stringResource(R.string.player_pip),
                    actions.onEnterPip,
                )
            }
            PlayerIconButton(
                Icons.Outlined.ScreenRotation,
                stringResource(R.string.player_rotate),
                actions.onRotate,
            )
        }
    }
}

@Composable
@kotlin.OptIn(ExperimentalMaterial3Api::class)
private fun PlayerSeekBar(
    positionMs: Long,
    durationMs: Long,
    scrubFraction: Float?,
    onScrub: (Float) -> Unit,
    onScrubFinished: () -> Unit,
) {
    val fraction = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
    val shownFraction = (scrubFraction ?: fraction).coerceIn(0f, 1f)
    val shownPosition = scrubFraction?.let { (it * durationMs).toLong() } ?: positionMs
    val timeStyle = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum")
    val seekColors = SliderDefaults.colors(
        thumbColor = Color.White,
        activeTrackColor = Color.White,
        inactiveTrackColor = Color.White.copy(alpha = 0.22f),
        disabledThumbColor = Color.White.copy(alpha = 0.4f),
        disabledActiveTrackColor = Color.White.copy(alpha = 0.4f),
        disabledInactiveTrackColor = Color.White.copy(alpha = 0.15f),
    )
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            formatPlaybackClock(shownPosition),
            style = timeStyle,
            color = Color.White.copy(alpha = 0.85f),
        )
        Slider(
            value = shownFraction,
            onValueChange = onScrub,
            onValueChangeFinished = onScrubFinished,
            enabled = durationMs > 0,
            colors = seekColors,
            thumb = { Box(Modifier.size(14.dp).background(Color.White, CircleShape)) },
            track = { sliderState ->
                SliderDefaults.Track(
                    sliderState = sliderState,
                    modifier = Modifier.height(4.dp),
                    colors = seekColors,
                    thumbTrackGapSize = 0.dp,
                    drawStopIndicator = null,
                )
            },
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
        )
        Text(
            formatPlaybackClock(durationMs),
            style = timeStyle,
            color = Color.White.copy(alpha = 0.85f),
        )
    }
}

@Composable
internal fun PlayerErrorOverlay(
    message: String,
    onClose: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f))
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.player_error_title),
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 460.dp),
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onClose,
            shape = OtvButtonShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White.copy(alpha = 0.12f),
                contentColor = Color.White,
            ),
        ) {
            Text(stringResource(R.string.common_close))
        }
    }
}

@Composable
private fun TransportButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(68.dp)) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(40.dp),
        )
    }
}

@Composable
private fun PlayerIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(44.dp)) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun LiveChip(modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(Color(0xFFF1544B), CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(
            stringResource(R.string.player_live_badge),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
        )
    }
}
