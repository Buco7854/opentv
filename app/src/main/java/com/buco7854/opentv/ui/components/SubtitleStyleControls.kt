package com.buco7854.opentv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.buco7854.opentv.data.prefs.SubtitleStyle
import androidx.compose.ui.res.stringResource
import com.buco7854.opentv.R

/** Subtitle appearance controls, shared by the in-player sheet and the settings screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubtitleStyleControls(style: SubtitleStyle, onChange: (SubtitleStyle) -> Unit) {
    Column {
        // Live preview; backdrop is video-like (not pure black) so the translucent-background style is visible.
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(
                    Brush.linearGradient(listOf(Color(0xFF35506B), Color(0xFF4A4A4A), Color(0xFF6B5535)))
                )
                .padding(vertical = 22.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(R.string.settings_subtitle_preview),
                color = Color.White,
                fontSize = (16 * style.scale).sp,
                fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Normal,
                modifier = if (style.background) {
                    Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black.copy(alpha = 0.7f))
                        .padding(horizontal = 6.dp, vertical = 1.dp)
                } else Modifier,
            )
        }

        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.settings_subtitle_size, (style.scale * 100).toInt()), style = MaterialTheme.typography.labelLarge)
        // Thin track + round thumb, no ticks/stop indicator, to match the web client.
        val sliderColors = SliderDefaults.colors(
            thumbColor = MaterialTheme.colorScheme.onSurface,
            activeTrackColor = MaterialTheme.colorScheme.onSurface,
            inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        Slider(
            value = style.scale,
            onValueChange = { onChange(style.copy(scale = it)) },
            valueRange = 0.5f..2f,
            colors = sliderColors,
            thumb = { Box(Modifier.size(16.dp).background(MaterialTheme.colorScheme.onSurface, CircleShape)) },
            track = { state ->
                SliderDefaults.Track(
                    sliderState = state,
                    modifier = Modifier.height(4.dp),
                    colors = sliderColors,
                    thumbTrackGapSize = 0.dp,
                    drawStopIndicator = null,
                )
            },
        )

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = !style.background,
                onClick = { onChange(style.copy(background = false)) },
                label = { Text(stringResource(R.string.settings_subtitle_outline)) },
                modifier = Modifier.padding(end = 8.dp),
            )
            FilterChip(
                selected = style.background,
                onClick = { onChange(style.copy(background = true)) },
                label = { Text(stringResource(R.string.settings_subtitle_background)) },
            )
        }

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_subtitle_bold), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(
                checked = style.bold,
                onCheckedChange = { onChange(style.copy(bold = it)) },
            )
        }
    }
}
