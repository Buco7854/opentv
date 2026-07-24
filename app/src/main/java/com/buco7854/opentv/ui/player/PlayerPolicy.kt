@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.buco7854.opentv.ui.player

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

internal fun pipAspectRatio(width: Int, height: Int): Float? {
    if (width <= 0 || height <= 0) return null
    return (width.toFloat() / height).coerceIn(0.42f, 2.39f)
}
