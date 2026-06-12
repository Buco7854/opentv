package com.buco7854.opentv.ui.player

import android.app.Activity
import android.graphics.Typeface
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import com.buco7854.opentv.OpenTvApp
import com.buco7854.opentv.data.net.Http
import com.buco7854.opentv.data.prefs.SubtitleStyle
import com.buco7854.opentv.diag.ErrorLog
import com.buco7854.opentv.ui.theme.Mint
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-screen playback. Hardware decoding is the default: DefaultRenderersFactory
 * uses the device's MediaCodec hardware decoders first and never falls back to
 * software extension renderers (extension mode OFF unless enabled).
 *
 * Embedded subtitle and audio tracks (HLS renditions, MKV/TS streams) are
 * selectable from the tracks sheet; subtitle appearance (size, style, bold) is
 * user-configurable and persisted.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    url: String,
    title: String,
    playlistId: Long,
    tvgId: String?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var nowNext by remember { mutableStateOf<Pair<String, String?>?>(null) }
    var showTracks by remember { mutableStateOf(false) }
    var showStyle by remember { mutableStateOf(false) }
    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }

    val subtitleStyle by OpenTvApp.graph.playerPrefs.subtitleStyle
        .collectAsState(initial = SubtitleStyle())

    val player = remember(url) {
        val httpFactory = OkHttpDataSource.Factory(Http.ok).setUserAgent(Http.USER_AGENT)
        val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)
        ExoPlayer.Builder(context, DefaultRenderersFactory(context))
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(url))
                playWhenReady = true
                prepare()
            }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(playbackError: PlaybackException) {
                ErrorLog.log("Playback: $title", playbackError)
                val cause = playbackError.cause?.message?.let { ": ${ErrorLog.redact(it)}" } ?: ""
                error = playbackError.errorCodeName + cause
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    // Immersive fullscreen while the player is open.
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        val controller = window?.let { WindowInsetsControllerCompat(it, view) }
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        window?.let { WindowCompat.setDecorFitsSystemWindows(it, false) }
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // EPG overlay data comes from the local database only - no network here.
    LaunchedEffect(tvgId) {
        if (tvgId != null && playlistId > 0) {
            val programmes = OpenTvApp.graph.epg.upcoming(playlistId, tvgId, limit = 2)
            val now = programmes.firstOrNull()
            if (now != null) {
                val timeFormat = DateFormat.getTimeInstance(DateFormat.SHORT)
                val nowLabel = "${now.title} (until ${timeFormat.format(Date(now.endMs))})"
                val nextLabel = programmes.getOrNull(1)?.let { "Next: ${it.title}" }
                nowNext = nowLabel to nextLabel
            }
        }
    }

    // Re-apply persisted subtitle styling whenever it changes.
    LaunchedEffect(playerView, subtitleStyle) {
        playerView?.subtitleView?.let { applySubtitleStyle(it, subtitleStyle) }
    }

    BackHandler { onBack() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = true
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                    keepScreenOn = true
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    playerView = this
                }
            },
            update = {
                it.player = player
                it.resizeMode = resizeMode
            },
            modifier = Modifier.fillMaxSize(),
        )

        Row(
            Modifier
                .fillMaxWidth()
                .align(Alignment.TopStart)
                .background(
                    Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.65f), Color.Transparent))
                )
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White)
                nowNext?.let { (now, next) ->
                    Text(now, style = MaterialTheme.typography.bodySmall, color = Mint)
                    next?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
                    }
                }
                error?.let {
                    Text(
                        "Playback error: $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            IconButton(onClick = {
                resizeMode = when (resizeMode) {
                    AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            }) {
                Icon(Icons.Rounded.AspectRatio, contentDescription = "Video scaling", tint = Color.White)
            }
            IconButton(onClick = { showTracks = true }) {
                Icon(Icons.Rounded.Subtitles, contentDescription = "Audio & subtitles", tint = Color.White)
            }
            IconButton(onClick = { showStyle = true }) {
                Icon(Icons.Rounded.Tune, contentDescription = "Subtitle appearance", tint = Color.White)
            }
        }
    }

    if (showTracks) {
        TracksSheet(player = player, onDismiss = { showTracks = false })
    }
    if (showStyle) {
        SubtitleStyleSheet(
            style = subtitleStyle,
            onChange = { scope.launch { OpenTvApp.graph.playerPrefs.save(it) } },
            onDismiss = { showStyle = false },
        )
    }
}

@OptIn(UnstableApi::class)
private fun applySubtitleStyle(subtitleView: SubtitleView, style: SubtitleStyle) {
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

private fun trackLabel(format: Format, index: Int): String {
    val language = format.language
        ?.takeIf { it.isNotBlank() && !it.equals("und", ignoreCase = true) }
        ?.let { Locale(it).displayLanguage.replaceFirstChar { c -> c.uppercase() } }
    return format.label ?: language ?: "Track ${index + 1}"
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

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TracksSheet(player: Player, onDismiss: () -> Unit) {
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

    val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
    val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
    val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
    var currentSpeed by remember { mutableStateOf(player.playbackParameters.speed) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            SheetHeading("Subtitles")
            val anyTextSelected = textGroups.any { it.isSelected }
            TrackOption(label = "Off", selected = !anyTextSelected) {
                disableSubtitles(player)
            }
            if (textGroups.isEmpty()) {
                Text(
                    "This stream has no embedded subtitle tracks.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            textGroups.forEach { group ->
                for (i in 0 until group.length) {
                    if (!group.isTrackSupported(i)) continue
                    TrackOption(
                        label = trackLabel(group.getTrackFormat(i), i),
                        selected = group.isTrackSelected(i),
                    ) { selectTrack(player, group, i) }
                }
            }

            Spacer(Modifier.height(16.dp))
            SheetHeading("Audio")
            if (audioGroups.isEmpty()) {
                Text(
                    "No selectable audio tracks.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            audioGroups.forEach { group ->
                for (i in 0 until group.length) {
                    if (!group.isTrackSupported(i)) continue
                    TrackOption(
                        label = trackLabel(group.getTrackFormat(i), i),
                        selected = group.isTrackSelected(i),
                    ) { selectTrack(player, group, i) }
                }
            }

            Spacer(Modifier.height(16.dp))
            SheetHeading("Speed")
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

@Composable
private fun SheetHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun TrackOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubtitleStyleSheet(
    style: SubtitleStyle,
    onChange: (SubtitleStyle) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            SheetHeading("Subtitle appearance")

            // Live preview approximating how subtitles will render.
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Color.Black)
                    .padding(vertical = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Preview subtitle",
                    color = Color.White,
                    fontSize = (16 * style.scale).sp,
                    fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Normal,
                    modifier = if (style.background) {
                        Modifier.background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 6.dp)
                    } else Modifier,
                )
            }

            Spacer(Modifier.height(16.dp))
            Text("Size · ${(style.scale * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
            Slider(
                value = style.scale,
                onValueChange = { onChange(SubtitleStyle(it, style.background, style.bold)) },
                valueRange = 0.5f..2f,
                steps = 5,
            )

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = !style.background,
                    onClick = { onChange(SubtitleStyle(style.scale, false, style.bold)) },
                    label = { Text("Outline") },
                    modifier = Modifier.padding(end = 8.dp),
                )
                FilterChip(
                    selected = style.background,
                    onClick = { onChange(SubtitleStyle(style.scale, true, style.bold)) },
                    label = { Text("Background") },
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Bold text", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(
                    checked = style.bold,
                    onCheckedChange = { onChange(SubtitleStyle(style.scale, style.background, it)) },
                )
            }
        }
    }
}
