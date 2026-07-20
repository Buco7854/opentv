package com.buco7854.opentv.ui.player

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.util.Rational
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import com.buco7854.opentv.OpenTvApp
import com.buco7854.opentv.R
import com.buco7854.opentv.core.model.Channel
import com.buco7854.opentv.data.net.Http
import com.buco7854.opentv.data.prefs.PlayerSettings
import com.buco7854.opentv.data.prefs.SubtitleStyle
import com.buco7854.opentv.diag.ErrorLog
import com.buco7854.opentv.playback.PlaybackMonitor
import com.buco7854.opentv.ui.components.GuideEntryContent
import com.buco7854.opentv.ui.components.OtvButtonShape
import com.buco7854.opentv.ui.components.GuideHint
import com.buco7854.opentv.ui.components.SubtitleStyleControls
import com.buco7854.opentv.ui.components.guideSheetHeight
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Broadcast sent by the PiP window's play/pause button back to the player. */
private const val PIP_ACTION_TOGGLE = "com.buco7854.opentv.player.PIP_TOGGLE"

/** The single play/pause control shown inside the Picture-in-Picture window. */
private fun playPauseAction(context: Context, isPlaying: Boolean): RemoteAction {
    val icon = Icon.createWithResource(
        context,
        if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
    )
    val label = context.getString(if (isPlaying) R.string.common_pause else R.string.common_play)
    val pending = PendingIntent.getBroadcast(
        context,
        0,
        Intent(PIP_ACTION_TOGGLE).setPackage(context.packageName),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    return RemoteAction(icon, label, label, pending)
}

private fun formatClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** Full-screen playback with a custom Compose overlay styled after the web player. */
@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    url: String,
    title: String,
    playlistId: Long,
    tvgId: String?,
    onBack: () -> Unit,
    initialLive: Boolean = false,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var nowNext by remember { mutableStateOf<Pair<String, String?>?>(null) }
    var showGuide by remember { mutableStateOf(false) }
    // Channel behind this stream, for catch-up.
    var channel by remember(url) { mutableStateOf<Channel?>(null) }
    var currentTitle by remember(url) { mutableStateOf(title) }
    LaunchedEffect(url, playlistId) {
        if (playlistId > 0) channel = OpenTvApp.graph.storage.channels.getByUrl(playlistId, url)
    }
    // Seeded from the caller for immediate chrome shape; corrected once the real stream loads.
    var isLiveStream by remember(url) { mutableStateOf(initialLive) }
    var showSubtitleTracks by remember { mutableStateOf(false) }
    var showAudioTracks by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    var playerView by remember { mutableStateOf<VideoSurface?>(null) }
    var scaleHint by remember { mutableStateOf<String?>(null) }
    var controlsVisible by remember { mutableStateOf(true) }
    // Bumped by every overlay interaction to restart the auto-hide timer.
    var hideNonce by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(true) }
    var buffering by remember { mutableStateOf(true) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    // Non-null while the user drags the seek bar; committed on release.
    var scrubFraction by remember { mutableStateOf<Float?>(null) }
    val configuration = LocalConfiguration.current

    fun poke() {
        hideNonce++
    }

    val inPip by PipController.isInPip.collectAsState()
    val pipSupported = remember {
        context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }
    // Saved VOD resume position; applied once on READY.
    var resumeTargetMs by remember(url) { mutableStateOf(0L) }
    var resumeApplied by remember(url) { mutableStateOf(false) }
    LaunchedEffect(url) {
        resumeTargetMs = OpenTvApp.graph.resume.resumePositionFor(url) ?: 0L
    }

    LaunchedEffect(scaleHint) {
        if (scaleHint != null) {
            delay(1400)
            scaleHint = null
        }
    }

    val settingsState by OpenTvApp.graph.playerPrefs.settings.collectAsState(initial = null)
    // Wait for the first settings emission so the player is built once, not rebuilt mid-play.
    val settings = settingsState ?: run {
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }
    val buildSettings = remember { settings }
    var resizeMode by remember { mutableIntStateOf(buildSettings.resizeMode) }

    val player = remember(url) {
        val httpFactory = OkHttpDataSource.Factory(Http.ok).setUserAgent(Http.userAgent)
        val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)
        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(buildSettings.decoderFallback)
        val loadControl = when (buildSettings.bufferPreset) {
            PlayerSettings.BUFFER_FAST_START ->
                DefaultLoadControl.Builder().setBufferDurationsMs(10_000, 30_000, 1_000, 2_000).build()
            PlayerSettings.BUFFER_STABLE ->
                DefaultLoadControl.Builder().setBufferDurationsMs(30_000, 120_000, 2_500, 5_000).build()
            else -> DefaultLoadControl()
        }
        ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            .setSeekBackIncrementMs(buildSettings.seekSeconds * 1000L)
            .setSeekForwardIncrementMs(buildSettings.seekSeconds * 1000L)
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
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setPreferredAudioLanguage(buildSettings.preferredAudioLang.ifEmpty { null })
                    .setPreferredTextLanguage(buildSettings.preferredTextLang.ifEmpty { null })
                    .build()
                setMediaItem(MediaItem.fromUri(url))
                playWhenReady = true
                prepare()
            }
    }

    fun pipParams(): PictureInPictureParams {
        val size = player.videoSize
        return PictureInPictureParams.Builder()
            .apply {
                if (size.width > 0 && size.height > 0) {
                    // System requires the ratio within ~[0.42, 2.39]; clamp to be safe.
                    val ratio = (size.width.toFloat() / size.height).coerceIn(0.42f, 2.39f)
                    setAspectRatio(Rational((ratio * 1000).toInt(), 1000))
                }
                setActions(listOf(playPauseAction(context, player.isPlaying)))
            }
            .build()
    }

    // Refresh so the PiP button's icon matches the current play state.
    fun refreshPipParams() {
        val activity = context as? Activity ?: return
        if (!pipSupported) return
        runCatching { activity.setPictureInPictureParams(pipParams()) }
    }

    fun enterPip() {
        val activity = context as? Activity ?: return
        if (!pipSupported) return
        runCatching { activity.enterPictureInPictureMode(pipParams()) }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(playbackError: PlaybackException) {
                ErrorLog.log("Playback: $title", playbackError)
                val cause = playbackError.cause?.message?.let { ": ${ErrorLog.redact(it)}" } ?: ""
                error = playbackError.errorCodeName + cause
            }

            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                // Only trust the loaded source; the placeholder timeline would stomp the caller's live hint.
                if (reason == Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE) {
                    isLiveStream = player.isCurrentMediaItemDynamic || player.isCurrentMediaItemLive
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                playing = playWhenReady
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (PipController.isInPip.value) refreshPipParams()
            }

            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                val live = player.isCurrentMediaItemLive || player.isCurrentMediaItemDynamic
                if (state == Player.STATE_READY && !resumeApplied && !live) {
                    resumeApplied = true
                    val dur = player.duration
                    if (resumeTargetMs in 1 until (dur - 15_000).coerceAtLeast(1)) {
                        player.seekTo(resumeTargetMs)
                    }
                } else if (state == Player.STATE_ENDED) {
                    OpenTvApp.graph.resume.clear(url) // finished, start fresh next time
                }
            }
        }
        player.addListener(listener)
        // Signal the download worker that this provider host is busy streaming.
        PlaybackMonitor.playbackStarted(url)
        onDispose {
            PlaybackMonitor.playbackStopped()
            // Persist progress so it survives a process kill.
            if (!player.isCurrentMediaItemLive && !player.isCurrentMediaItemDynamic) {
                OpenTvApp.graph.resume.save(url, player.currentPosition, player.duration)
            }
            player.removeListener(listener)
            player.release()
        }
    }

    // Feed the seek bar and time labels.
    LaunchedEffect(player) {
        while (true) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
            durationMs = player.duration.takeIf { it > 0 } ?: 0L
            delay(500)
        }
    }

    // Auto-hide the overlay after 3 s of playback without interaction.
    val scrubbing = scrubFraction != null
    LaunchedEffect(controlsVisible, playing, scrubbing, hideNonce) {
        if (controlsVisible && playing && !scrubbing) {
            delay(3000)
            controlsVisible = false
        }
    }

    // Periodically persist progress, covering process kills that skip onDispose.
    LaunchedEffect(player) {
        while (true) {
            delay(10_000)
            if (player.isPlaying && !player.isCurrentMediaItemLive && !player.isCurrentMediaItemDynamic) {
                OpenTvApp.graph.resume.save(url, player.currentPosition, player.duration)
            }
        }
    }

    // Pause when backgrounded: an invisible stream wastes a concurrent-connection slot.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(player, lifecycleOwner) {
        var wasPlaying = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    wasPlaying = player.playWhenReady
                    player.pause()
                }
                Lifecycle.Event.ON_START -> if (wasPlaying) player.play()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Register auto-PiP (Home press) while this screen is on.
    DisposableEffect(pipSupported) {
        if (pipSupported) PipController.onUserLeave = { enterPip() }
        onDispose { PipController.onUserLeave = null }
    }

    // The PiP window's play/pause button posts this broadcast back to us.
    DisposableEffect(player) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != PIP_ACTION_TOGGLE) return
                if (player.isPlaying) player.pause() else player.play()
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(PIP_ACTION_TOGGLE),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    // Immersive fullscreen while open; restore bars and orientation on leave.
    DisposableEffect(Unit) {
        val activity = context as? Activity
        val window = activity?.window
        val controller = window?.let { WindowInsetsControllerCompat(it, view) }
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        window?.let { WindowCompat.setDecorFitsSystemWindows(it, false) }
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // EPG overlay data comes from the local database only.
    LaunchedEffect(tvgId) {
        if (tvgId != null && playlistId > 0) {
            val programmes = OpenTvApp.graph.epg.upcoming(playlistId, tvgId, limit = 2)
            val now = programmes.firstOrNull()
            if (now != null) {
                val timeFormat = DateFormat.getTimeInstance(DateFormat.SHORT)
                val nowLabel = context.getString(R.string.player_now_until, now.title, timeFormat.format(Date(now.endMs)))
                val nextLabel = programmes.getOrNull(1)?.let { context.getString(R.string.player_next, it.title) }
                nowNext = nowLabel to nextLabel
            }
        }
    }

    LaunchedEffect(playerView, settings.subtitleStyle) {
        playerView?.subtitleView?.let { applySubtitleStyle(it, settings.subtitleStyle) }
    }

    BackHandler { onBack() }

    val seekStep = settings.seekSeconds
    val seekBackHint = stringResource(R.string.player_seek_back_hint, seekStep)
    val seekForwardHint = stringResource(R.string.player_seek_forward_hint, seekStep)
    val scaleLabels = mapOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT to stringResource(R.string.player_scale_fit),
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM to stringResource(R.string.player_scale_zoom),
        AspectRatioFrameLayout.RESIZE_MODE_FILL to stringResource(R.string.player_scale_stretch),
    )
    val catchupUnavailableHint = stringResource(R.string.player_catchup_unavailable)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                VideoSurface(ctx).apply {
                    keepScreenOn = true
                    playerView = this
                }
            },
            update = {
                it.setPlayer(player)
                it.resizeMode = resizeMode
            },
            onRelease = { it.setPlayer(null) },
            modifier = Modifier.fillMaxSize(),
        )

        // Tap toggles overlay; double-tap on the side thirds seeks by the configured step.
        if (!inPip && error == null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(seekStep) {
                        detectTapGestures(
                            onTap = { controlsVisible = !controlsVisible },
                            onDoubleTap = { offset ->
                                when {
                                    offset.x < size.width / 3f -> {
                                        player.seekBack()
                                        scaleHint = seekBackHint
                                    }
                                    offset.x > size.width * 2f / 3f -> {
                                        player.seekForward()
                                        scaleHint = seekForwardHint
                                    }
                                    else -> controlsVisible = !controlsVisible
                                }
                            },
                        )
                    },
            )
        }

        if (!inPip && error == null && buffering && !controlsVisible) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center).size(44.dp),
                color = Color.White,
                strokeWidth = 3.dp,
            )
        }

        scaleHint?.let { hint ->
            Text(
                hint,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 18.dp, vertical = 8.dp),
            )
        }

        if (!inPip && error == null && controlsVisible) {
            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent))
                    )
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
                    )
                    // Consume taps so touching the band never toggles the overlay.
                    .pointerInput(Unit) { detectTapGestures { poke() } }
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayerIconButton(PlayerGlyphs.Back, stringResource(R.string.common_back)) { onBack() }
                Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                    Text(
                        currentTitle.uppercase(Locale.getDefault()),
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.8.sp,
                        ),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val subtitleLine = nowNext?.first ?: if (isLiveStream) stringResource(R.string.common_live) else null
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
                if (channel?.let { it.tvgId != null || it.xtreamStreamId != null } == true) {
                    PlayerIconButton(PlayerGlyphs.Calendar, stringResource(R.string.guide_open)) {
                        showGuide = true
                        poke()
                    }
                }
                PlayerIconButton(PlayerGlyphs.Close, stringResource(R.string.player_stop)) { onBack() }
            }

            Row(
                Modifier.align(Alignment.Center),
                horizontalArrangement = Arrangement.spacedBy(44.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!isLiveStream) {
                    TransportButton(PlayerGlyphs.Replay, stringResource(R.string.player_rewind)) {
                        player.seekBack()
                        scaleHint = seekBackHint
                        poke()
                    }
                }
                IconButton(
                    onClick = {
                        if (player.playWhenReady) player.pause() else player.play()
                        poke()
                    },
                    modifier = Modifier.size(68.dp),
                ) {
                    if (buffering) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(40.dp),
                            color = Color.White,
                            strokeWidth = 3.dp,
                        )
                    } else {
                        Icon(
                            if (playing) PlayerGlyphs.Pause else PlayerGlyphs.Play,
                            contentDescription = stringResource(if (playing) R.string.common_pause else R.string.common_play),
                            tint = Color.White,
                            modifier = Modifier.size(40.dp),
                        )
                    }
                }
                if (!isLiveStream) {
                    TransportButton(PlayerGlyphs.Forward, stringResource(R.string.player_forward)) {
                        player.seekForward()
                        scaleHint = seekForwardHint
                        poke()
                    }
                }
            }

            // Bottom control bar: seek row (elapsed left, total right) then option controls.
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f)))
                    )
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
                    )
                    .pointerInput(Unit) { detectTapGestures { poke() } }
                    .padding(horizontal = 16.dp)
                    .padding(top = 24.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                val timeStyle = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum")
                if (!isLiveStream) {
                    val fraction = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
                    val shownFraction = (scrubFraction ?: fraction).coerceIn(0f, 1f)
                    val shownPosition = scrubFraction?.let { (it * durationMs).toLong() } ?: positionMs
                    val seekColors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.22f),
                        disabledThumbColor = Color.White.copy(alpha = 0.4f),
                        disabledActiveTrackColor = Color.White.copy(alpha = 0.4f),
                        disabledInactiveTrackColor = Color.White.copy(alpha = 0.15f),
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(formatClock(shownPosition), style = timeStyle, color = Color.White.copy(alpha = 0.85f))
                        Slider(
                            value = shownFraction,
                            onValueChange = { scrubFraction = it },
                            onValueChangeFinished = {
                                scrubFraction?.let { player.seekTo((it * durationMs).toLong()) }
                                scrubFraction = null
                                poke()
                            },
                            enabled = durationMs > 0,
                            colors = seekColors,
                            thumb = { Box(Modifier.size(14.dp).background(Color.White, CircleShape)) },
                            track = { state ->
                                SliderDefaults.Track(
                                    sliderState = state,
                                    modifier = Modifier.height(4.dp),
                                    colors = seekColors,
                                    thumbTrackGapSize = 0.dp,
                                    drawStopIndicator = null,
                                )
                            },
                            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                        )
                        Text(formatClock(durationMs), style = timeStyle, color = Color.White.copy(alpha = 0.85f))
                    }
                }
                // Options row: LIVE chip on live, else centered option controls.
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = if (isLiveStream) Arrangement.Start else Arrangement.Center,
                ) {
                    if (isLiveStream) {
                        LiveChip(Modifier.padding(start = 2.dp))
                        Spacer(Modifier.weight(1f))
                    }
                    PlayerIconButton(PlayerGlyphs.Audio, stringResource(R.string.player_audio_track)) {
                        showAudioTracks = true
                        poke()
                    }
                    PlayerIconButton(PlayerGlyphs.Subtitles, stringResource(R.string.player_subtitles)) {
                        showSubtitleTracks = true
                        poke()
                    }
                    // Speed makes no sense on live streams.
                    if (!isLiveStream) {
                        PlayerIconButton(PlayerGlyphs.Speed, stringResource(R.string.player_speed)) {
                            showSpeed = true
                            poke()
                        }
                    }
                    PlayerIconButton(PlayerGlyphs.Aspect, stringResource(R.string.player_scaling)) {
                        resizeMode = when (resizeMode) {
                            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                        scaleHint = scaleLabels[resizeMode]
                        scope.launch { OpenTvApp.graph.playerPrefs.save(settings.copy(resizeMode = resizeMode)) }
                        poke()
                    }
                    if (pipSupported) {
                        PlayerIconButton(PlayerGlyphs.Pip, stringResource(R.string.player_pip)) {
                            enterPip()
                        }
                    }
                    PlayerIconButton(Icons.Outlined.ScreenRotation, stringResource(R.string.player_rotate)) {
                        val activity = context as? Activity
                        val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                        activity?.requestedOrientation =
                            if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                            else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        poke()
                    }
                }
            }
        }

        error?.let { message ->
            if (!inPip) {
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
                        onClick = onBack,
                        shape = OtvButtonShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White.copy(alpha = 0.12f),
                            contentColor = Color.White,
                        ),
                    ) { Text(stringResource(R.string.common_close)) }
                }
            }
        }
    }

    channel?.takeIf { showGuide }?.let { ch ->
        PlayerGuideSheet(
            title = currentTitle,
            channel = ch,
            onDismiss = { showGuide = false },
            onReplay = { entry ->
                scope.launch {
                    val catchupUrl = OpenTvApp.graph.xtream.catchupUrlFor(ch, entry.startMs, entry.endMs)
                    if (catchupUrl != null) {
                        showGuide = false
                        error = null
                        isLiveStream = false // catch-up is seekable VOD
                        currentTitle = "${ch.name} · ${entry.title}"
                        player.setMediaItem(MediaItem.fromUri(catchupUrl))
                        player.prepare()
                        player.playWhenReady = true
                    } else {
                        scaleHint = catchupUnavailableHint
                    }
                }
            },
        )
    }
    if (showSubtitleTracks) {
        TrackSheet(
            player = player,
            trackType = C.TRACK_TYPE_TEXT,
            heading = stringResource(R.string.player_subtitles),
            emptyText = stringResource(R.string.player_no_subtitle_tracks),
            allowOff = true,
            onDismiss = { showSubtitleTracks = false },
            extraContent = {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
                SheetHeading(stringResource(R.string.settings_appearance))
                SubtitleStyleControls(
                    style = settings.subtitleStyle,
                    onChange = {
                        scope.launch {
                            OpenTvApp.graph.playerPrefs.save(settings.copy(subtitleStyle = it))
                        }
                    },
                )
            },
        )
    }
    if (showAudioTracks) {
        TrackSheet(
            player = player,
            trackType = C.TRACK_TYPE_AUDIO,
            heading = stringResource(R.string.player_audio),
            emptyText = stringResource(R.string.player_no_audio_tracks),
            allowOff = false,
            onDismiss = { showAudioTracks = false },
        )
    }
    if (showSpeed) {
        SpeedSheet(player = player, onDismiss = { showSpeed = false })
    }
}

/** Large centered transport button (~68dp target, 40dp glyph), matching web. */
@Composable
private fun TransportButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(68.dp)) {
        Icon(icon, contentDescription = contentDescription, tint = Color.White, modifier = Modifier.size(40.dp))
    }
}

/** 44dp white icon button used across the player chrome (22dp glyph, like web). */
@Composable
private fun PlayerIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(44.dp)) {
        Icon(icon, contentDescription = contentDescription, tint = Color.White, modifier = Modifier.size(22.dp))
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
        Text(stringResource(R.string.player_live_badge), style = MaterialTheme.typography.labelMedium, color = Color.White)
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

private fun trackLabel(format: Format): String? {
    val language = format.language
        ?.takeIf { it.isNotBlank() && !it.equals("und", ignoreCase = true) }
        ?.let { Locale(it).displayLanguage.replaceFirstChar { c -> c.uppercase() } }
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

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackSheet(
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
                .padding(bottom = 28.dp)
        ) {
            SheetHeading(heading)
            if (allowOff) {
                val anySelected = groups.any { it.isSelected }
                TrackOption(label = stringResource(R.string.player_track_off), selected = !anySelected) {
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
                for (i in 0 until group.length) {
                    if (!group.isTrackSupported(i)) continue
                    TrackOption(
                        label = trackLabel(group.getTrackFormat(i))
                            ?: stringResource(R.string.player_track_n, i + 1),
                        selected = group.isTrackSelected(i),
                    ) { selectTrack(player, group, i) }
                }
            }
            extraContent?.invoke()
        }
    }
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpeedSheet(player: Player, onDismiss: () -> Unit) {
    val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
    var currentSpeed by remember { mutableStateOf(player.playbackParameters.speed) }
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

/** Channel guide over playback; replayed programmes load into the same player. */
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerGuideSheet(
    title: String,
    channel: Channel,
    onDismiss: () -> Unit,
    onReplay: (com.buco7854.opentv.core.repo.GuideEntry) -> Unit,
) {
    var entries by remember(channel.id) { mutableStateOf<List<com.buco7854.opentv.core.repo.GuideEntry>?>(null) }
    LaunchedEffect(channel.id) { entries = OpenTvApp.graph.xtream.guideFor(channel) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .height(guideSheetHeight)
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
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
