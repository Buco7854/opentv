package com.buco7854.opentv.ui.components

import android.content.res.Configuration
import android.view.KeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp

/** D-pad focus treatment: focused cards scale up and gain a ring. */
fun Modifier.focusHighlight(shape: Shape = RoundedCornerShape(16.dp)): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.03f else 1f, label = "focusScale")
    this
        .onFocusChanged { focused = it.hasFocus }
        .scale(scale)
        .border(
            width = 2.dp,
            color = if (focused) MaterialTheme.colorScheme.onSurface else Color.Transparent,
            shape = shape,
        )
}

internal fun isTelevisionUiMode(uiMode: Int): Boolean =
    uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION

/**
 * Requests focus only on a television. Phone fields therefore do not summon
 * the IME merely because a screen or modal was opened.
 */
@Composable
fun RequestInitialFocusOnTv(
    focusRequester: FocusRequester,
    key: Any? = Unit,
) {
    val television = isTelevisionUiMode(LocalConfiguration.current.uiMode)
    LaunchedEffect(television, key) {
        if (television) focusRequester.requestFocus()
    }
}

internal fun isRemoteConfirmKey(keyCode: Int): Boolean = keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
    keyCode == KeyEvent.KEYCODE_ENTER ||
    keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

internal fun shouldTriggerRemoteLongClick(
    keyCode: Int,
    action: Int,
    repeatCount: Int,
    flags: Int,
    alreadyHandled: Boolean,
): Boolean = !alreadyHandled &&
    isRemoteConfirmKey(keyCode) &&
    action == KeyEvent.ACTION_DOWN &&
    (repeatCount > 0 || flags and KeyEvent.FLAG_LONG_PRESS != 0)

/**
 * Compose's pointer long-click path is not consistently reached by a held
 * remote centre key. Recognize the key repeat before [combinedClickable] and
 * consume its eventual key-up so the hold cannot also fire the short click.
 */
fun Modifier.dpadLongClick(onLongClick: (() -> Unit)?): Modifier = if (onLongClick == null) {
    this
} else {
    composed {
        var handled by remember { mutableStateOf(false) }
        onPreviewKeyEvent { event ->
            val native = event.nativeKeyEvent
            when {
                shouldTriggerRemoteLongClick(
                    keyCode = native.keyCode,
                    action = native.action,
                    repeatCount = native.repeatCount,
                    flags = native.flags,
                    alreadyHandled = handled,
                ) -> {
                    handled = true
                    onLongClick()
                    true
                }
                isRemoteConfirmKey(native.keyCode) &&
                    native.action == KeyEvent.ACTION_UP &&
                    handled -> {
                    handled = false
                    true
                }
                else -> handled
            }
        }
    }
}
