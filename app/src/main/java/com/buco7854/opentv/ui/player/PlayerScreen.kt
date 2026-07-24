package com.buco7854.opentv.ui.player

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import com.buco7854.opentv.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Full-screen playback coordinator. Rendering, player runtime, and system effects live in focused collaborators. */
@OptIn(UnstableApi::class)
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
    val configuration = LocalConfiguration.current
    val scope = rememberCoroutineScope()
    val viewModel = playerViewModel(url, playlistId, tvgId)
    val bootstrap by viewModel.bootstrap.collectAsStateWithLifecycle()
    val settingsState by viewModel.settings.collectAsStateWithLifecycle()
    val channel by viewModel.channel.collectAsStateWithLifecycle()
    val nowNext by viewModel.nowNext.collectAsStateWithLifecycle()
    val guideEntries by viewModel.guideEntries.collectAsStateWithLifecycle()
    val inPip by PipController.isInPip.collectAsStateWithLifecycle()

    val initial = bootstrap
    if (initial == null) {
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }
    val settings = settingsState ?: initial.settings
    val session = remember(url, initial) {
        PlayerSession(
            context = context.applicationContext,
            url = url,
            title = title,
            settings = initial.settings,
            initialLive = initialLive,
            resumeTargetMs = initial.resumePositionMs,
            saveProgress = viewModel::saveProgress,
            clearProgress = viewModel::clearProgress,
        )
    }
    DisposableEffect(session) {
        onDispose(session::close)
    }

    val playback by session.state.collectAsStateWithLifecycle()
    val systemController = rememberPlayerSystemController(session.player)
    PlayerSystemEffects(session, systemController)
    BackHandler(onBack = onBack)

    var currentTitle by remember(session) { mutableStateOf(title) }
    var controlsVisible by remember(session) { mutableStateOf(true) }
    var interactionNonce by remember(session) { mutableIntStateOf(0) }
    var scrubFraction by remember(session) { mutableStateOf<Float?>(null) }
    var resizeMode by remember(session) { mutableIntStateOf(initial.settings.resizeMode) }
    var hint by remember(session) { mutableStateOf<String?>(null) }
    var videoSurface by remember(session) { mutableStateOf<VideoSurface?>(null) }
    var showGuide by remember(session) { mutableStateOf(false) }
    var showSubtitleTracks by remember(session) { mutableStateOf(false) }
    var showAudioTracks by remember(session) { mutableStateOf(false) }
    var showSpeed by remember(session) { mutableStateOf(false) }

    fun markInteraction() {
        interactionNonce++
    }

    LaunchedEffect(hint) {
        if (hint != null) {
            delay(1_400)
            hint = null
        }
    }
    LaunchedEffect(controlsVisible, playback.playing, scrubFraction, interactionNonce) {
        if (controlsVisible && playback.playing && scrubFraction == null) {
            delay(3_000)
            controlsVisible = false
        }
    }
    LaunchedEffect(videoSurface, settings.subtitleStyle) {
        videoSurface?.subtitleView?.let { applySubtitleStyle(it, settings.subtitleStyle) }
    }

    val seekBackHint = stringResource(R.string.player_seek_back_hint, settings.seekSeconds)
    val seekForwardHint = stringResource(R.string.player_seek_forward_hint, settings.seekSeconds)
    val catchupUnavailableHint = stringResource(R.string.player_catchup_unavailable)
    val subtitleLine = nowNext?.let { programme ->
        val timeFormat = DateFormat.getTimeInstance(DateFormat.SHORT)
        stringResource(
            R.string.player_now_until,
            programme.currentTitle,
            timeFormat.format(Date(programme.currentEndMs)),
        )
    } ?: if (playback.isLive) stringResource(R.string.common_live) else null

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { surfaceContext ->
                VideoSurface(surfaceContext).apply {
                    keepScreenOn = true
                    videoSurface = this
                }
            },
            update = { surface ->
                surface.setPlayer(session.player)
                surface.resizeMode = resizeMode
            },
            onRelease = { surface -> surface.setPlayer(null) },
            modifier = Modifier.fillMaxSize(),
        )

        if (!inPip && playback.error == null) {
            PlayerGestureSurface(
                seekSeconds = settings.seekSeconds,
                onToggleControls = { controlsVisible = !controlsVisible },
                onSeekBack = {
                    session.seekBack()
                    hint = seekBackHint
                },
                onSeekForward = {
                    session.seekForward()
                    hint = seekForwardHint
                },
            )
        }

        if (!inPip && playback.error == null && playback.buffering && !controlsVisible) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center).size(44.dp),
                color = Color.White,
                strokeWidth = 3.dp,
            )
        }

        hint?.let { message ->
            Text(
                message,
                color = Color.White,
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 18.dp, vertical = 8.dp),
            )
        }

        if (!inPip && playback.error == null && controlsVisible) {
            PlayerChrome(
                state = PlayerChromeState(
                    title = currentTitle,
                    subtitleLine = subtitleLine,
                    isLive = playback.isLive,
                    playing = playback.playing,
                    buffering = playback.buffering,
                    positionMs = playback.positionMs,
                    durationMs = playback.durationMs,
                    scrubFraction = scrubFraction,
                    showGuide = channel?.let { it.tvgId != null || it.xtreamStreamId != null } == true,
                    pipSupported = systemController.pipSupported,
                ),
                actions = PlayerChromeActions(
                    onBack = onBack,
                    onInteraction = ::markInteraction,
                    onTogglePlayback = {
                        session.togglePlayback()
                        markInteraction()
                    },
                    onSeekBack = {
                        session.seekBack()
                        hint = seekBackHint
                        markInteraction()
                    },
                    onSeekForward = {
                        session.seekForward()
                        hint = seekForwardHint
                        markInteraction()
                    },
                    onScrub = { scrubFraction = it },
                    onScrubFinished = {
                        scrubFraction?.let { fraction ->
                            session.seekTo((fraction * playback.durationMs).toLong())
                        }
                        scrubFraction = null
                        markInteraction()
                    },
                    onOpenGuide = {
                        viewModel.loadGuide()
                        showGuide = true
                        markInteraction()
                    },
                    onOpenAudio = {
                        showAudioTracks = true
                        markInteraction()
                    },
                    onOpenSubtitles = {
                        showSubtitleTracks = true
                        markInteraction()
                    },
                    onOpenSpeed = {
                        showSpeed = true
                        markInteraction()
                    },
                    onChangeScale = {
                        resizeMode = nextResizeMode(resizeMode)
                        hint = scaleHintFor(resizeMode, context)
                        viewModel.saveResizeMode(resizeMode)
                        markInteraction()
                    },
                    onEnterPip = systemController::enterPictureInPicture,
                    onRotate = {
                        systemController.toggleOrientation(
                            configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
                        )
                        markInteraction()
                    },
                ),
            )
        }

        if (!inPip) {
            playback.error?.let { message ->
                PlayerErrorOverlay(message = message, onClose = onBack)
            }
        }
    }

    if (showGuide) {
        PlayerGuideSheet(
            title = currentTitle,
            entries = guideEntries,
            onDismiss = { showGuide = false },
            onReplay = { entry ->
                scope.launch {
                    val catchupUrl = viewModel.catchupUrlFor(entry)
                    if (catchupUrl == null) {
                        hint = catchupUnavailableHint
                    } else {
                        showGuide = false
                        currentTitle = "${channel?.name ?: title} · ${entry.title}"
                        session.playCatchup(catchupUrl)
                    }
                }
            },
        )
    }
    if (showSubtitleTracks) {
        SubtitleTrackSheet(
            player = session.player,
            style = settings.subtitleStyle,
            onStyleChange = viewModel::saveSubtitleStyle,
            onDismiss = { showSubtitleTracks = false },
        )
    }
    if (showAudioTracks) {
        TrackSheet(
            player = session.player,
            trackType = C.TRACK_TYPE_AUDIO,
            heading = stringResource(R.string.player_audio),
            emptyText = stringResource(R.string.player_no_audio_tracks),
            allowOff = false,
            onDismiss = { showAudioTracks = false },
        )
    }
    if (showSpeed) {
        SpeedSheet(player = session.player, onDismiss = { showSpeed = false })
    }
}

@Composable
private fun PlayerGestureSurface(
    seekSeconds: Int,
    onToggleControls: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(seekSeconds) {
                detectTapGestures(
                    onTap = { onToggleControls() },
                    onDoubleTap = { offset ->
                        when {
                            offset.x < size.width / 3f -> onSeekBack()
                            offset.x > size.width * 2f / 3f -> onSeekForward()
                            else -> onToggleControls()
                        }
                    },
                )
            },
    )
}

@OptIn(UnstableApi::class)
private fun scaleHintFor(resizeMode: Int, context: android.content.Context): String =
    context.getString(
        when (resizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT -> R.string.player_scale_fit
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> R.string.player_scale_zoom
            else -> R.string.player_scale_stretch
        },
    )
