@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.buco7854.opentv.ui.player

import android.view.KeyEvent
import androidx.media3.ui.AspectRatioFrameLayout

internal fun formatPlaybackClock(milliseconds: Long): String {
    val totalSeconds = (milliseconds / 1_000).coerceAtLeast(0)
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

internal fun nextResizeMode(current: Int): Int = when (current) {
    AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
}

internal fun shouldApplyResume(
    targetMs: Long,
    durationMs: Long,
    endGuardMs: Long = 15_000,
): Boolean =
    targetMs > 0 &&
        durationMs > endGuardMs &&
        targetMs <= durationMs - endGuardMs

/**
 * Whether a reported position is worth persisting.
 *
 * Until the timeline arrives ExoPlayer reports [androidx.media3.common.C.TIME_UNSET] for the
 * duration, and the resume store reads a non-positive duration as "finished" and drops the
 * saved position. Closing a file that had not loaded yet would therefore erase progress the
 * viewer never actually replaced.
 */
internal fun shouldPersistProgress(durationMs: Long, live: Boolean): Boolean =
    !live && durationMs > 0

/** Only on-demand content with a stable identity participates in resume storage. */
internal fun shouldTrackProgress(target: PlayerTarget): Boolean =
    !target.live && target !is PlayerTarget.HubCatchUp

/**
 * A navigation disposal ends the lease. An Activity recreation does not: the
 * retained ViewModel still owns that same lease and resumes it after recreation.
 */
internal fun shouldClosePlayerOnDispose(isChangingConfigurations: Boolean): Boolean =
    !isChangingConfigurations

/** Auto-PiP follows active playback intent, not a paused or failed player. */
internal fun shouldAutoEnterPip(playWhenReady: Boolean, hasError: Boolean): Boolean =
    playWhenReady && !hasError

internal enum class PlayerErrorAction {
    RETRY_SESSION,
    RETRY_HUB,
    SIGN_IN,
    NONE,
}

/** The recovery control shown for a terminal player surface. */
internal fun playerErrorAction(problem: PlayerProblem?): PlayerErrorAction = when (problem) {
    null -> PlayerErrorAction.RETRY_SESSION
    PlayerProblem.AT_CAPACITY,
    PlayerProblem.FAILED,
    -> PlayerErrorAction.RETRY_HUB
    PlayerProblem.SIGNED_OUT -> PlayerErrorAction.SIGN_IN
    PlayerProblem.PLAYBACK_ENDED -> PlayerErrorAction.NONE
}

/** Media is deliberately absent while the initial alone/together decision is unresolved. */
internal fun shouldShowWatchTogetherBeforeMedia(state: WatchTogetherState): Boolean =
    state.choosing || state.duplicateRefused

internal enum class PlayerBackAction {
    DISMISS_NOTICE,
    HIDE_CONTROLS,
    EXIT,
}

/**
 * How long a notice shows before dismissing itself. Every other kind is copy we
 * wrote and can read in a beat, but an admin message is up to 1000 characters of
 * someone else's words: at ~200 words per minute that is nearer a minute than the
 * six seconds it used to get. Scale the dwell to the length so the message can
 * actually be finished, and keep a ceiling so a wall of text cannot sit over the
 * video indefinitely. Back dismisses any notice early.
 */
internal fun noticeDwellMs(kind: WatchTogetherNoticeKind, textLength: Int): Long = when (kind) {
    WatchTogetherNoticeKind.ADMIN_MESSAGE ->
        (ADMIN_MESSAGE_BASE_MS + textLength * ADMIN_MESSAGE_PER_CHAR_MS)
            .coerceIn(ADMIN_MESSAGE_MIN_MS, ADMIN_MESSAGE_MAX_MS)
    WatchTogetherNoticeKind.ROOM_ENDED -> ROOM_ENDED_MS
    else -> DEFAULT_NOTICE_MS
}

/** A beat to read the opening lines before an overlong notice starts drifting. */
internal const val READING_LEAD_IN_MS = 1_200L

/** And a beat on the last line before the notice disappears. */
internal const val READING_TAIL_MS = 900L

private const val DEFAULT_NOTICE_MS = 3_500L
private const val ROOM_ENDED_MS = 6_000L
private const val ADMIN_MESSAGE_BASE_MS = 4_000L
private const val ADMIN_MESSAGE_PER_CHAR_MS = 55L
private const val ADMIN_MESSAGE_MIN_MS = 6_000L
private const val ADMIN_MESSAGE_MAX_MS = 60_000L

/** Back peels transient player layers before it is allowed to leave playback. */
internal fun playerBackAction(hasNotice: Boolean, controlsVisible: Boolean): PlayerBackAction =
    when {
        hasNotice -> PlayerBackAction.DISMISS_NOTICE
        controlsVisible -> PlayerBackAction.HIDE_CONTROLS
        else -> PlayerBackAction.EXIT
    }

internal enum class PlayerRemoteAction {
    SHOW_CONTROLS,
    SEEK_BACK,
    SEEK_FORWARD,
    NONE,
}

internal enum class PlayerMediaAction {
    TOGGLE_PLAYBACK,
    PLAY,
    PAUSE,
    SEEK_BACK,
    SEEK_FORWARD,
    NONE,
}

internal enum class PlayerToggleAction {
    PAUSE,
    PLAY,
    RESTART,
}

/** An ended timeline needs an explicit seek; changing playWhenReady alone cannot replay it. */
internal fun playerToggleAction(playWhenReady: Boolean, ended: Boolean): PlayerToggleAction =
    when {
        ended -> PlayerToggleAction.RESTART
        playWhenReady -> PlayerToggleAction.PAUSE
        else -> PlayerToggleAction.PLAY
    }

/** Hardware media keys work independently of which chrome control owns focus. */
internal fun playerMediaAction(keyCode: Int, keyAction: Int): PlayerMediaAction {
    if (keyAction != KeyEvent.ACTION_DOWN) return PlayerMediaAction.NONE
    return when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> PlayerMediaAction.TOGGLE_PLAYBACK
        KeyEvent.KEYCODE_MEDIA_PLAY -> PlayerMediaAction.PLAY
        KeyEvent.KEYCODE_MEDIA_PAUSE -> PlayerMediaAction.PAUSE
        KeyEvent.KEYCODE_MEDIA_REWIND -> PlayerMediaAction.SEEK_BACK
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> PlayerMediaAction.SEEK_FORWARD
        else -> PlayerMediaAction.NONE
    }
}

/** Key policy for the otherwise touch-only surface while player chrome is hidden. */
internal fun playerRemoteAction(
    enabled: Boolean,
    keyCode: Int,
    keyAction: Int,
): PlayerRemoteAction {
    if (!enabled || keyAction != KeyEvent.ACTION_DOWN) return PlayerRemoteAction.NONE
    return when (keyCode) {
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER,
        -> PlayerRemoteAction.SHOW_CONTROLS
        KeyEvent.KEYCODE_DPAD_LEFT -> PlayerRemoteAction.SEEK_BACK
        KeyEvent.KEYCODE_DPAD_RIGHT -> PlayerRemoteAction.SEEK_FORWARD
        else -> PlayerRemoteAction.NONE
    }
}

internal fun pipAspectRatio(width: Int, height: Int): Float? {
    if (width <= 0 || height <= 0) return null
    return (width.toFloat() / height).coerceIn(0.42f, 2.39f)
}
