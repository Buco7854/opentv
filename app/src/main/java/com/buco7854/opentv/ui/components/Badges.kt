package com.buco7854.opentv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.buco7854.opentv.ui.theme.Mint
import java.util.Locale

private val TAG_REGEX = Regex(
    """(?i)\b(4K|UHD|2160p|1080p|FHD|720p|HEVC|H\.?26[45]|HDR(?:10)?|10bit|HD|SD)\b"""
)

/** Quality/codec tags mined from a provider title, normalized and deduplicated. */
fun mediaTags(name: String, max: Int = 2): List<String> =
    TAG_REGEX.findAll(name)
        .map { it.value.uppercase(Locale.ROOT).replace(".", "") }
        .map { if (it == "2160P") "4K" else it }
        .distinct()
        .take(max)
        .toList()

private val PREMIUM_TAGS = setOf("4K", "UHD", "HDR", "HDR10", "DOLBY")

/**
 * Compact quality badge. Premium tags (4K/UHD/HDR) get the gradient treatment;
 * the rest are quiet tonal chips.
 */
@Composable
fun QualityBadge(text: String) {
    val premium = text in PREMIUM_TAGS
    val background = if (premium) {
        Modifier.background(MaterialTheme.colorScheme.secondaryContainer)
    } else {
        Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest)
    }
    Text(
        text,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.5.sp,
        color = if (premium) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .then(background)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
fun BadgeRow(tags: List<String>, modifier: Modifier = Modifier) {
    if (tags.isEmpty()) return
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        tags.forEach { QualityBadge(it) }
    }
}
