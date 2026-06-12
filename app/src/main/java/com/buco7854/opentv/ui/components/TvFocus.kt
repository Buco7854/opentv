package com.buco7854.opentv.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.buco7854.opentv.ui.theme.Periwinkle

/**
 * D-pad focus treatment for Android TV (and keyboards): focused cards scale up
 * slightly and get a periwinkle ring. Touch interaction is unaffected.
 */
fun Modifier.focusHighlight(shape: Shape = RoundedCornerShape(16.dp)): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.03f else 1f, label = "focusScale")
    this
        .onFocusChanged { focused = it.hasFocus }
        .scale(scale)
        .border(
            width = 2.dp,
            color = if (focused) Periwinkle else Color.Transparent,
            shape = shape,
        )
}
