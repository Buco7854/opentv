package com.buco7854.opentv.ui.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
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
import kotlinx.coroutines.flow.StateFlow
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
) : AutoCloseable {

    private val closed = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var resumeApplied = false
    private var resumeAfterStart = false

    private val _state = MutableStateFlow(PlaybackUiState(isLive = initialLive))
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    val player: ExoPlayer = createPlayer(context, url, settings)

    private val listener = object : Player.Listener {
        override fun onPlayerError(playbackError: PlaybackException) {
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
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            update { copy(buffering = playbackState == Player.STATE_BUFFERING) }
            val live = player.isCurrentMediaItemLive || player.isCurrentMediaItemDynamic
            if (playbackState == Player.STATE_READY && !resumeApplied && !live) {
                resumeApplied = true
                if (shouldApplyResume(resumeTargetMs, player.duration)) {
                    player.seekTo(resumeTargetMs)
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
        player.seekBack()
    }

    fun seekForward() {
        player.seekForward()
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
    }

    fun playCatchup(url: String) {
        resumeApplied = true
        update { copy(error = null, isLive = false, buffering = true) }
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.playWhenReady = true
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
        if ((!onlyWhilePlaying || player.isPlaying) &&
            !player.isCurrentMediaItemLive &&
            !player.isCurrentMediaItemDynamic
        ) {
            saveProgress(player.currentPosition, player.duration)
        }
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
): ExoPlayer {
    val httpFactory = OkHttpDataSource.Factory(Http.ok).setUserAgent(Http.userAgent)
    val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)
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
