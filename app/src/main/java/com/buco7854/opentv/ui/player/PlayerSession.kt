package com.buco7854.opentv.ui.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.buco7854.opentv.data.net.Http
import com.buco7854.opentv.data.prefs.PlayerSettings
import com.buco7854.opentv.diag.ErrorLog
import com.buco7854.opentv.playback.PlaybackMonitor
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class PlaybackUiState(
    val playing: Boolean = true,
    val buffering: Boolean = true,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val isLive: Boolean,
    val error: String? = null,
)

/**
 * Owns one ExoPlayer and all polling/persistence work attached to its lifetime.
 *
 * Compose only renders [state] and sends player commands. Closing the session
 * is idempotent and releases every listener, coroutine, and provider-budget
 * signal owned by playback.
 */
@OptIn(UnstableApi::class)
internal class PlayerSession(
    context: Context,
    url: String,
    title: String,
    settings: PlayerSettings,
    initialLive: Boolean,
    private val resumeTargetMs: Long,
    private val saveProgress: (positionMs: Long, durationMs: Long) -> Unit,
    private val clearProgress: () -> Unit,
    dataSourceFactory: DataSource.Factory? = null,
    private val onMediaRequestFailed: (statusCode: Int) -> Boolean = { false },
) : AutoCloseable, WatchTogetherPlayer {

    private val closed = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var resumeApplied = false
    private var resumeAfterStart = false
    private var currentUrl = url

    private val _state = MutableStateFlow(PlaybackUiState(isLive = initialLive))
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()
    private val playbackEvents = MutableSharedFlow<WatchTogetherPlaybackEvent>(
        extraBufferCapacity = 16,
    )
    override val events: Flow<WatchTogetherPlaybackEvent> = playbackEvents.asSharedFlow()

    val player: ExoPlayer = createPlayer(context, url, settings, dataSourceFactory)
    override val positionMs: Long get() = currentPositionMs()
    override val paused: Boolean get() = !player.playWhenReady
    override val playbackRate: Double get() = player.playbackParameters.speed.toDouble()
    override val isLive: Boolean
        get() = player.isCurrentMediaItemLive || player.isCurrentMediaItemDynamic

    private val listener = object : Player.Listener {
        override fun onPlayerError(playbackError: PlaybackException) {
            val responseCode = playbackError.responseCode()
            if (responseCode != null && onMediaRequestFailed(responseCode)) {
                update { copy(error = null, buffering = true) }
                player.prepare()
                player.play()
                return
            }
            ErrorLog.log("Playback: $title", playbackError)
            val cause = playbackError.cause?.message?.let { ": ${ErrorLog.redact(it)}" } ?: ""
            update { copy(error = playbackError.errorCodeName + cause) }
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            if (reason == Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE) {
                update {
                    copy(isLive = player.isCurrentMediaItemDynamic || player.isCurrentMediaItemLive)
                }
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            update { copy(playing = playWhenReady) }
            playbackEvents.tryEmit(WatchTogetherPlaybackEvent.Changed(seek = false))
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            playbackEvents.tryEmit(WatchTogetherPlaybackEvent.Changed(seek = false))
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                playbackEvents.tryEmit(WatchTogetherPlaybackEvent.Changed(seek = true))
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            update { copy(buffering = playbackState == Player.STATE_BUFFERING) }
            val live = player.isCurrentMediaItemLive || player.isCurrentMediaItemDynamic
            if (playbackState == Player.STATE_READY) {
                playbackEvents.tryEmit(WatchTogetherPlaybackEvent.Ready)
                if (!resumeApplied && !live) {
                    resumeApplied = true
                    if (shouldApplyResume(resumeTargetMs, player.duration)) {
                        player.seekTo(resumeTargetMs)
                    }
                }
            } else if (playbackState == Player.STATE_ENDED) {
                clearProgress()
            }
        }
    }

    init {
        player.addListener(listener)
        PlaybackMonitor.playbackStarted(url)
        scope.launch {
            while (true) {
                update {
                    copy(
                        positionMs = player.currentPosition.coerceAtLeast(0),
                        durationMs = player.duration.takeIf { it > 0 } ?: 0,
                    )
                }
                delay(500)
            }
        }
        scope.launch {
            while (true) {
                delay(10_000)
                persistProgressIfNeeded(onlyWhilePlaying = true)
            }
        }
    }

    fun togglePlayback() {
        if (player.playWhenReady) player.pause() else player.play()
    }

    fun seekBack() {
        resumeApplied = true
        player.seekBack()
    }

    fun seekForward() {
        resumeApplied = true
        player.seekForward()
    }

    override fun seekTo(positionMs: Long) {
        // A room sync can arrive while the initial source is still preparing.
        // That explicit seek supersedes the stored solo resume point.
        resumeApplied = true
        player.seekTo(positionMs)
    }

    fun currentPositionMs(): Long = player.currentPosition.coerceAtLeast(0)

    override fun play() {
        player.play()
    }

    override fun pause() {
        player.pause()
    }

    override fun setPlaybackRate(rate: Double) {
        player.setPlaybackSpeed(rate.toFloat())
    }

    fun playCatchup(url: String) {
        resumeApplied = true
        currentUrl = url
        update { copy(error = null, isLive = false, buffering = true) }
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.playWhenReady = true
    }

    fun replaceMediaItem(url: String, positionMs: Long?) {
        if (url == currentUrl) return
        val resumePlayback = player.playWhenReady
        currentUrl = url
        resumeApplied = true
        update { copy(error = null, buffering = true) }
        val mediaItem = MediaItem.fromUri(url)
        if (positionMs == C.TIME_UNSET) {
            player.setMediaItem(mediaItem)
        } else {
            val restorePositionMs = (positionMs ?: player.currentPosition).coerceAtLeast(0)
            player.setMediaItem(mediaItem, restorePositionMs)
        }
        player.prepare()
        player.playWhenReady = resumePlayback
    }

    fun stopWithError(message: String) {
        player.stop()
        update { copy(playing = false, buffering = false, error = message) }
    }

    fun onHostStopped() {
        resumeAfterStart = player.playWhenReady
        player.pause()
    }

    fun onHostStarted() {
        if (resumeAfterStart) player.play()
        resumeAfterStart = false
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        persistProgressIfNeeded(onlyWhilePlaying = false)
        PlaybackMonitor.playbackStopped()
        player.removeListener(listener)
        player.release()
        scope.cancel()
    }

    private fun persistProgressIfNeeded(onlyWhilePlaying: Boolean) {
        if (onlyWhilePlaying && !player.isPlaying) return
        val live = player.isCurrentMediaItemLive || player.isCurrentMediaItemDynamic
        val durationMs = player.duration
        if (!shouldPersistProgress(durationMs, live)) return
        saveProgress(player.currentPosition, durationMs)
    }

    private inline fun update(transform: PlaybackUiState.() -> PlaybackUiState) {
        _state.value = _state.value.transform()
    }
}

@OptIn(UnstableApi::class)
private fun createPlayer(
    context: Context,
    url: String,
    settings: PlayerSettings,
    dataSourceFactoryOverride: DataSource.Factory?,
): ExoPlayer {
    val dataSourceFactory = dataSourceFactoryOverride ?: defaultPlayerDataSourceFactory(context)
    val renderersFactory = DefaultRenderersFactory(context)
        .setEnableDecoderFallback(settings.decoderFallback)
    val loadControl = when (settings.bufferPreset) {
        PlayerSettings.BUFFER_FAST_START ->
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(10_000, 30_000, 1_000, 2_000)
                .build()

        PlayerSettings.BUFFER_STABLE ->
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(30_000, 120_000, 2_500, 5_000)
                .build()

        else -> DefaultLoadControl()
    }
    return ExoPlayer.Builder(context, renderersFactory)
        .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
        .setLoadControl(loadControl)
        .setSeekBackIncrementMs(settings.seekSeconds * 1_000L)
        .setSeekForwardIncrementMs(settings.seekSeconds * 1_000L)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true,
        )
        .setHandleAudioBecomingNoisy(true)
        .build()
        .apply {
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setPreferredAudioLanguage(settings.preferredAudioLang.ifEmpty { null })
                .setPreferredTextLanguage(settings.preferredTextLang.ifEmpty { null })
                .build()
            setMediaItem(MediaItem.fromUri(url))
            playWhenReady = true
            prepare()
        }
}

@OptIn(UnstableApi::class)
internal fun defaultPlayerDataSourceFactory(context: Context): DataSource.Factory {
    val httpFactory = OkHttpDataSource.Factory(Http.ok).setUserAgent(Http.userAgent)
    return DefaultDataSource.Factory(context, httpFactory)
}

private fun Throwable.responseCode(): Int? {
    var current: Throwable? = this
    while (current != null) {
        if (current is HttpDataSource.InvalidResponseCodeException) {
            return current.responseCode
        }
        current = current.cause
    }
    return null
}
