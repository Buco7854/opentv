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
): Boolean = targetMs in 1 until (durationMs - endGuardMs).coerceAtLeast(1)

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

/**
 * A navigation disposal ends the lease. An Activity recreation does not: the
 * retained ViewModel still owns that same lease and resumes it after recreation.
 */
internal fun shouldClosePlayerOnDispose(isChangingConfigurations: Boolean): Boolean =
    !isChangingConfigurations

internal enum class PlayerBackAction {
    DISMISS_NOTICE,
    HIDE_CONTROLS,
    EXIT,
}

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
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        -> PlayerRemoteAction.SHOW_CONTROLS
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_MEDIA_REWIND,
        -> PlayerRemoteAction.SEEK_BACK
        KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
        -> PlayerRemoteAction.SEEK_FORWARD
        else -> PlayerRemoteAction.NONE
    }
}

internal fun pipAspectRatio(width: Int, height: Int): Float? {
    if (width <= 0 || height <= 0) return null
    return (width.toFloat() / height).coerceIn(0.42f, 2.39f)
}
