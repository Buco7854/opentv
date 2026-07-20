package com.buco7854.opentv.ui.components

import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Thin "continue watching" bar; [fraction] is 0..1. */
@Composable
fun WatchProgressBar(fraction: Float, modifier: Modifier = Modifier, height: Dp = 4.dp) {
    val f = fraction.coerceIn(0f, 1f)
    OtvProgressBar(progress = { f }, modifier = modifier.height(height))
}
