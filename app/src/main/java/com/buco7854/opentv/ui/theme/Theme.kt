package com.buco7854.opentv.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// A deep "midnight cinema" palette: near-black navy surfaces with an electric
// periwinkle primary and a mint accent for live/positive states.
val Night = Color(0xFF0A0D18)
val Surface1 = Color(0xFF111526)
val Surface2 = Color(0xFF1A2036)
val Surface3 = Color(0xFF232B47)
val Periwinkle = Color(0xFF8FA8FF)
val PeriwinkleDim = Color(0xFF2A3760)
val Mint = Color(0xFF5BE3B5)
val Coral = Color(0xFFFF7B72)
val TextPrimary = Color(0xFFE9ECF8)
val TextSecondary = Color(0xFF9AA3C0)

private val OpenTvColorScheme = darkColorScheme(
    primary = Periwinkle,
    onPrimary = Color(0xFF0B1230),
    primaryContainer = PeriwinkleDim,
    onPrimaryContainer = Color(0xFFDDE4FF),
    secondary = Mint,
    onSecondary = Color(0xFF00382A),
    secondaryContainer = Color(0xFF12453A),
    onSecondaryContainer = Color(0xFFB8F5DF),
    tertiary = Color(0xFFFFB4A7),
    background = Night,
    onBackground = TextPrimary,
    surface = Night,
    onSurface = TextPrimary,
    surfaceVariant = Surface2,
    onSurfaceVariant = TextSecondary,
    surfaceContainer = Surface1,
    surfaceContainerHigh = Surface2,
    surfaceContainerHighest = Surface3,
    error = Coral,
    outline = Color(0xFF3A4366),
)

private val OpenTvTypography = Typography().let { base ->
    base.copy(
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun OpenTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = OpenTvColorScheme,
        typography = OpenTvTypography,
        content = content,
    )
}
