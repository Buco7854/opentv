package com.buco7854.opentv.ui.player

import android.app.Activity
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
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
import com.buco7854.opentv.hub.playback.HubMediaDataSourceFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Full-screen playback coordinator. Rendering, player runtime, and system effects live in focused collaborators. */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    target: PlayerTarget,
    onBack: () -> Unit,
    onSignIn: () -> Unit,
    onPlayTarget: (PlayerTarget) -> Unit,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val scope = rememberCoroutineScope()
    val viewModel = playerViewModel(target)
    val bootstrap by viewModel.bootstrap.collectAsStateWithLifecycle()
    val settingsState by viewModel.settings.collectAsStateWithLifecycle()
    val guideAvailable by viewModel.guideAvailable.collectAsStateWithLifecycle()
    val nowNext by viewModel.nowNext.collectAsStateWithLifecycle()
    val guideEntries by viewModel.guideEntries.collectAsStateWithLifecycle()
    val playbackSource by viewModel.playbackSource.collectAsStateWithLifecycle()
    val hubAudioTracks by viewModel.hubAudioTracks.collectAsStateWithLifecycle()
    val watchTogether by viewModel.watchTogetherState.collectAsStateWithLifecycle()
    val problem by viewModel.problem.collectAsStateWithLifecycle()
    val inPip by PipController.isInPip.collectAsStateWithLifecycle()

    fun closeLeaseAndGoBack() {
        viewModel.closePlayer()
        onBack()
    }

    DisposableEffect(viewModel) {
        onDispose {
            val changingConfigurations = (context as? Activity)?.isChangingConfigurations == true
            if (shouldClosePlayerOnDispose(changingConfigurations)) viewModel.closePlayer()
        }
    }

    fun watchTogetherActions(
        closeSheet: () -> Unit,
        closePlayer: () -> Unit,
    ) = WatchTogetherActions(
        onClose = closePlayer,
        onWatchAlone = {
            viewModel.watchAlone()
            closeSheet()
        },
        onJoin = { peerId ->
            viewModel.askToJoin(peerId)
            closeSheet()
        },
        onAnswerJoin = viewModel::answerJoin,
        onRequestControl = {
            viewModel.requestRoomControl()
            closeSheet()
        },
        onAnswerControl = viewModel::answerRoomControl,
        onSetControl = viewModel::setRoomControl,
        onKick = viewModel::kickRoomMember,
        onLeave = {
            viewModel.leaveRoom()
            closeSheet()
        },
    )

    LaunchedEffect(watchTogether.notice?.id) {
        val notice = watchTogether.notice ?: return@LaunchedEffect
        delay(noticeDwellMs(notice.kind, notice.text?.length ?: 0))
        viewModel.dismissWatchTogetherNotice(notice.id)
    }

    val initial = bootstrap
    if (initial == null) {
        BackHandler(onBack = ::closeLeaseAndGoBack)
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }
    val mediaSource = playbackSource
    if (mediaSource == null) {
        BackHandler(onBack = ::closeLeaseAndGoBack)
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (problem == null) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center).size(44.dp),
                    color = Color.White,
                    strokeWidth = 3.dp,
                )
            } else {
                val action = playerErrorAction(problem)
                PlayerErrorOverlay(
                    message = problemMessage(problem!!),
                    onClose = ::closeLeaseAndGoBack,
                    actionLabel = when (action) {
                        PlayerErrorAction.SIGN_IN -> stringResource(R.string.hub_sign_in_again)
                        PlayerErrorAction.RETRY_HUB -> stringResource(R.string.common_retry)
                        else -> null
                    },
                    onAction = when (action) {
                        PlayerErrorAction.SIGN_IN -> onSignIn
                        PlayerErrorAction.RETRY_HUB -> viewModel::retryHubPlayback
                        else -> null
                    },
                )
            }
            watchTogether.notice?.let { notice ->
                Box(
                    Modifier.fillMaxSize().padding(top = 84.dp),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    WatchTogetherNoticeOverlay(notice)
                }
            }
        }
        if (shouldShowWatchTogetherBeforeMedia(watchTogether)) {
            WatchTogetherSheet(
                state = watchTogether,
                actions = watchTogetherActions(
                    closeSheet = {},
                    closePlayer = ::closeLeaseAndGoBack,
                ),
                // There is no player chrome to return to before media starts. Closing
                // a required decision therefore cancels playback instead of silently
                // submitting "watch alone" on the viewer's behalf. The ordinary optional
                // offer keeps its established dismiss-means-solo behaviour.
                onDismiss = {
                    if (watchTogether.requiresJoin) closeLeaseAndGoBack()
                    else viewModel.watchAlone()
                },
            )
        }
        return
    }
    val settings = settingsState ?: initial.settings
    val dataSourceFactory = remember(target) {
        if (target is PlayerTarget.LocalUrl) {
            null
        } else {
            HubMediaDataSourceFactory(
                defaultPlayerDataSourceFactory(context.applicationContext),
                viewModel::currentGrant,
            )
        }
    }
    val session = remember(target, initial) {
        PlayerSession(
            context = context.applicationContext,
            url = mediaSource.url,
            title = target.title,
            settings = initial.settings,
            initialLive = target.live,
            resumeTargetMs = viewModel.resumePositionForSession(initial.resumePositionMs),
            saveProgress = viewModel::saveProgress,
            clearProgress = viewModel::clearProgress,
            dataSourceFactory = dataSourceFactory,
            onMediaRequestFailed = viewModel::onMediaRequestFailed,
        )
    }
    DisposableEffect(session, viewModel) {
        viewModel.attachWatchTogetherPlayer(session)
        onDispose {
            viewModel.attachWatchTogetherPlayer(null)
            session.close()
        }
    }
    LaunchedEffect(mediaSource, session) {
        session.replaceMediaItem(mediaSource.url, mediaSource.startPositionMs)
    }

    val playback by session.state.collectAsStateWithLifecycle()
    val systemController = rememberPlayerSystemController(session.player)
    PlayerSystemEffects(
        session,
        systemController,
        onHostStopped = viewModel::onHostStopped,
        onHostStarted = viewModel::onHostStarted,
    )

    fun closePlayerAndGoBack() {
        session.pause()
        closeLeaseAndGoBack()
    }
    var currentTitle by remember(session) { mutableStateOf(target.title) }
    var controlsVisible by remember(session) { mutableStateOf(true) }
    var controlsFocused by remember(session) { mutableStateOf(false) }
    var interactionNonce by remember(session) { mutableIntStateOf(0) }
    var scrubFraction by remember(session) { mutableStateOf<Float?>(null) }
    var resizeMode by remember(session) { mutableIntStateOf(initial.settings.resizeMode) }
    var hint by remember(session) { mutableStateOf<String?>(null) }
    var videoSurface by remember(session) { mutableStateOf<VideoSurface?>(null) }
    var showGuide by remember(session) { mutableStateOf(false) }
    var showSubtitleTracks by remember(session) { mutableStateOf(false) }
    var showAudioTracks by remember(session) { mutableStateOf(false) }
    var showSpeed by remember(session) { mutableStateOf(false) }
    var showWatchTogether by remember(session) { mutableStateOf(false) }
    val remoteSurfaceFocusRequester = remember(session) { FocusRequester() }

    fun markInteraction() {
        interactionNonce++
    }

    LaunchedEffect(hint) {
        if (hint != null) {
            delay(1_400)
            hint = null
        }
    }
    LaunchedEffect(watchTogether.choosing) {
        if (watchTogether.choosing) showWatchTogether = true
    }
    LaunchedEffect(watchTogether.available, watchTogether.choosing) {
        if (!watchTogether.available && !watchTogether.choosing) showWatchTogether = false
    }
    LaunchedEffect(watchTogether.notice?.id) {
        val notice = watchTogether.notice ?: return@LaunchedEffect
        if (notice.kind == WatchTogetherNoticeKind.JOIN_REQUEST ||
            notice.kind == WatchTogetherNoticeKind.CONTROL_REQUEST
        ) showWatchTogether = true
    }
    val modalOpen = showGuide || showSubtitleTracks || showAudioTracks ||
        showSpeed || showWatchTogether
    LaunchedEffect(
        controlsVisible,
        controlsFocused,
        modalOpen,
        playback.playing,
        scrubFraction,
        interactionNonce,
    ) {
        if (controlsVisible && !controlsFocused && !modalOpen &&
            playback.playing && scrubFraction == null
        ) {
            delay(3_000)
            controlsVisible = false
        }
    }
    LaunchedEffect(controlsVisible) {
        if (!controlsVisible) remoteSurfaceFocusRequester.requestFocus()
    }
    LaunchedEffect(videoSurface, settings.subtitleStyle) {
        videoSurface?.subtitleView?.let { applySubtitleStyle(it, settings.subtitleStyle) }
    }
    LaunchedEffect(playback) {
        viewModel.updatePlaybackSnapshot(playback)
    }
    // Resolved here: the effect below runs outside composition.
    val problemText = problem?.let { problemMessage(it) }
    LaunchedEffect(problemText) {
        problemText?.let(session::stopWithError)
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

    BackHandler {
        when (playerBackAction(watchTogether.notice != null, controlsVisible)) {
            PlayerBackAction.DISMISS_NOTICE ->
                watchTogether.notice?.let { viewModel.dismissWatchTogetherNotice(it.id) }
            PlayerBackAction.HIDE_CONTROLS -> controlsVisible = false
            PlayerBackAction.EXIT -> closePlayerAndGoBack()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                val action = playerMediaAction(native.keyCode, native.action)
                if (action == PlayerMediaAction.NONE || playback.error != null) {
                    return@onPreviewKeyEvent false
                }
                if (watchTogether.loading) return@onPreviewKeyEvent true
                when (action) {
                    PlayerMediaAction.TOGGLE_PLAYBACK -> session.togglePlayback()
                    PlayerMediaAction.PLAY -> session.play()
                    PlayerMediaAction.PAUSE -> session.pause()
                    PlayerMediaAction.SEEK_BACK -> {
                        session.seekBack()
                        hint = seekBackHint
                    }
                    PlayerMediaAction.SEEK_FORWARD -> {
                        session.seekForward()
                        hint = seekForwardHint
                    }
                    PlayerMediaAction.NONE -> Unit
                }
                markInteraction()
                true
            },
    ) {
        AndroidView(
            factory = { surfaceContext ->
                VideoSurface(surfaceContext).apply {
                    videoSurface = this
                }
            },
            update = { surface ->
                surface.keepScreenOn = playback.playing && playback.error == null
                surface.setPlayer(session.player)
                surface.resizeMode = resizeMode
            },
            onRelease = { surface -> surface.setPlayer(null) },
            modifier = Modifier.fillMaxSize(),
        )

        if (!inPip && playback.error == null) {
            PlayerGestureSurface(
                seekSeconds = settings.seekSeconds,
                remoteEnabled = !controlsVisible,
                remoteFocusRequester = remoteSurfaceFocusRequester,
                onToggleControls = { controlsVisible = !controlsVisible },
                onSeekBack = {
                    if (!watchTogether.loading) {
                        session.seekBack()
                        hint = seekBackHint
                    }
                },
                onSeekForward = {
                    if (!watchTogether.loading) {
                        session.seekForward()
                        hint = seekForwardHint
                    }
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
                    showGuide = guideAvailable,
                    showWatchTogether = watchTogether.available,
                    watchTogetherPending = watchTogether.hasPending,
                    pipSupported = systemController.pipSupported,
                ),
                actions = PlayerChromeActions(
                    onBack = ::closePlayerAndGoBack,
                    onInteraction = ::markInteraction,
                    onChromeFocusChanged = { controlsFocused = it },
                    onTogglePlayback = {
                        if (!watchTogether.loading) session.togglePlayback()
                        markInteraction()
                    },
                    onSeekBack = {
                        if (!watchTogether.loading) {
                            session.seekBack()
                            hint = seekBackHint
                        }
                        markInteraction()
                    },
                    onSeekForward = {
                        if (!watchTogether.loading) {
                            session.seekForward()
                            hint = seekForwardHint
                        }
                        markInteraction()
                    },
                    onScrub = { scrubFraction = it },
                    onScrubFinished = {
                        if (!watchTogether.loading) {
                            scrubFraction?.let { fraction ->
                                session.seekTo((fraction * playback.durationMs).toLong())
                            }
                        }
                        scrubFraction = null
                        markInteraction()
                    },
                    onOpenGuide = {
                        viewModel.loadGuide()
                        showGuide = true
                        markInteraction()
                    },
                    onOpenWatchTogether = {
                        showWatchTogether = true
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
                val action = playerErrorAction(problem)
                PlayerErrorOverlay(
                    message = message,
                    onClose = ::closePlayerAndGoBack,
                    actionLabel = when (action) {
                        PlayerErrorAction.SIGN_IN -> stringResource(R.string.hub_sign_in_again)
                        PlayerErrorAction.RETRY_SESSION,
                        PlayerErrorAction.RETRY_HUB,
                        -> stringResource(R.string.common_retry)
                        PlayerErrorAction.NONE -> null
                    },
                    onAction = when (action) {
                        PlayerErrorAction.SIGN_IN -> onSignIn
                        PlayerErrorAction.RETRY_SESSION -> session::retry
                        PlayerErrorAction.RETRY_HUB -> viewModel::retryHubPlayback
                        PlayerErrorAction.NONE -> null
                    },
                )
            }
            if (watchTogether.loading) {
                WatchTogetherLoadingOverlay()
            }
            watchTogether.notice?.let { notice ->
                Box(
                    Modifier.fillMaxSize().padding(top = 84.dp),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    WatchTogetherNoticeOverlay(notice)
                }
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
                    val catchupTarget = viewModel.catchupTargetFor(entry)
                    if (catchupTarget == null) {
                        hint = catchupUnavailableHint
                    } else if (catchupTarget is PlayerTarget.LocalUrl) {
                        showGuide = false
                        currentTitle = catchupTarget.title
                        session.playCatchup(catchupTarget.url)
                    } else {
                        showGuide = false
                        session.pause()
                        // NavHost cross-fades destinations, so the outgoing ViewModel otherwise
                        // retains its provider lease while the replacement asks for one.
                        viewModel.closePlayerAndAwait()
                        onPlayTarget(catchupTarget)
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
        val serverTracks = hubAudioTracks
        if (serverTracks == null) {
            TrackSheet(
                player = session.player,
                trackType = C.TRACK_TYPE_AUDIO,
                heading = stringResource(R.string.player_audio),
                emptyText = stringResource(R.string.player_no_audio_tracks),
                allowOff = false,
                onDismiss = { showAudioTracks = false },
            )
        } else {
            HubAudioTrackSheet(
                tracks = serverTracks,
                heading = stringResource(R.string.player_audio),
                emptyText = stringResource(R.string.player_no_audio_tracks),
                onSelect = { index ->
                    showAudioTracks = false
                    viewModel.selectHubAudioTrack(index, session.currentPositionMs())
                },
                onDismiss = { showAudioTracks = false },
            )
        }
    }
    if (showSpeed) {
        SpeedSheet(player = session.player, onDismiss = { showSpeed = false })
    }
    if (showWatchTogether) {
        WatchTogetherSheet(
            state = watchTogether,
            actions = watchTogetherActions(
                closeSheet = { showWatchTogether = false },
                closePlayer = ::closePlayerAndGoBack,
            ),
            onDismiss = {
                if (watchTogether.duplicateRefused ||
                    (watchTogether.choosing && watchTogether.requiresJoin)
                ) {
                    closePlayerAndGoBack()
                } else {
                    if (watchTogether.choosing) viewModel.watchAlone()
                    showWatchTogether = false
                }
            },
        )
    }
}

@Composable
private fun problemMessage(problem: PlayerProblem): String = stringResource(
    when (problem) {
        PlayerProblem.PLAYBACK_ENDED -> R.string.player_playback_ended
        PlayerProblem.SIGNED_OUT -> R.string.player_signed_out
        PlayerProblem.AT_CAPACITY -> R.string.player_at_capacity
        PlayerProblem.FAILED -> R.string.player_stream_failed
    }
)

@Composable
private fun PlayerGestureSurface(
    seekSeconds: Int,
    remoteEnabled: Boolean,
    remoteFocusRequester: FocusRequester,
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
            }
            .focusRequester(remoteFocusRequester)
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                when (playerRemoteAction(remoteEnabled, native.keyCode, native.action)) {
                    PlayerRemoteAction.SHOW_CONTROLS -> {
                        onToggleControls()
                        true
                    }
                    PlayerRemoteAction.SEEK_BACK -> {
                        onSeekBack()
                        true
                    }
                    PlayerRemoteAction.SEEK_FORWARD -> {
                        onSeekForward()
                        true
                    }
                    PlayerRemoteAction.NONE -> false
                }
            }
            .focusable(enabled = remoteEnabled),
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
