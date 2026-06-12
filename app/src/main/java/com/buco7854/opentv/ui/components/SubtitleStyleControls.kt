package com.buco7854.opentv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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

/** Subtitle appearance controls, shared by the in-player sheet and the settings screen. */
@Composable
fun SubtitleStyleControls(style: SubtitleStyle, onChange: (SubtitleStyle) -> Unit) {
    Column {
        // Live preview approximating how subtitles will render.
        Box(
            Modifier
                .fillMaxWidth()
                .background(Color.Black)
                .padding(vertical = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Preview subtitle",
                color = Color.White,
                fontSize = (16 * style.scale).sp,
                fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Normal,
                modifier = if (style.background) {
                    Modifier.background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 6.dp)
                } else Modifier,
            )
        }

        Spacer(Modifier.height(16.dp))
        Text("Size · ${(style.scale * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
        Slider(
            value = style.scale,
            onValueChange = { onChange(style.copy(scale = it)) },
            valueRange = 0.5f..2f,
            steps = 5,
        )

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = !style.background,
                onClick = { onChange(style.copy(background = false)) },
                label = { Text("Outline") },
                modifier = Modifier.padding(end = 8.dp),
            )
            FilterChip(
                selected = style.background,
                onClick = { onChange(style.copy(background = true)) },
                label = { Text("Background") },
            )
        }

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Bold text", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(
                checked = style.bold,
                onCheckedChange = { onChange(style.copy(bold = it)) },
            )
        }
    }
}
