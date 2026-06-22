package com.buco7854.opentv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Thin "continue watching" bar showing how far through a movie or episode the
 * viewer is. [fraction] is 0..1; the caller supplies the width via [modifier].
 */
@Composable
fun WatchProgressBar(fraction: Float, modifier: Modifier = Modifier, height: Dp = 4.dp) {
    val f = fraction.coerceIn(0f, 1f)
    Box(
        modifier
            .height(height)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(f)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.primary)
        )
    }
}
