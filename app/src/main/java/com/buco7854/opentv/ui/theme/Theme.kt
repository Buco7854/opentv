package com.buco7854.opentv.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.buco7854.opentv.OpenTvApp
import com.buco7854.opentv.R
import com.buco7854.opentv.data.prefs.PlayerSettings

/*
 * Cockpit design system: charcoal/white canvas, one blue accent, green for live, red for errors.
 * Dark canvas is neutral #161616, not pitch black.
 */

/** True when the current theme renders on the dark canvas. */
val LocalOtvDark = staticCompositionLocalOf { true }

/** Royal blue, the one accent on both canvases. */
private val Accent = Color(0xFF3E6AE1)
private val GoodDark = Color(0xFF34C759)
private val GoodLight = Color(0xFF12823B)
private val BadDark = Color(0xFFF1544B)
private val BadLight = Color(0xFFCC3B33)

/** Positive/live accent, tuned per canvas for contrast. */
val Mint: Color
    @Composable @ReadOnlyComposable get() = if (LocalOtvDark.current) GoodDark else GoodLight

/** Error/attention accent, tuned per canvas for contrast. */
val Coral: Color
    @Composable @ReadOnlyComposable get() = if (LocalOtvDark.current) BadDark else BadLight

private val DarkScheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF282828),
    onPrimaryContainer = Color(0xFFE8E8E8),
    secondary = GoodDark,
    onSecondary = Color(0xFF04240F),
    // Selected pills (FilterChip) and tonal buttons: solid white on charcoal.
    secondaryContainer = Color(0xFFFFFFFF),
    onSecondaryContainer = Color(0xFF161616),
    tertiary = BadDark,
    background = Color(0xFF161616),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF161616),
    onSurface = Color(0xFFFFFFFF),
    // Must differ from every surfaceContainer* value, or contentColorFor mis-resolves them.
    surfaceVariant = Color(0xFF272727),
    onSurfaceVariant = Color(0xFF9B9B9B),
    surfaceContainerLowest = Color(0xFF111111),
    surfaceContainerLow = Color(0xFF1A1A1A),
    surfaceContainer = Color(0xFF1F1F1F),
    surfaceContainerHigh = Color(0xFF262626),
    surfaceContainerHighest = Color(0xFF323232),
    error = BadDark,
    outline = Color(0xFF393939),
    outlineVariant = Color(0xFF2B2B2B),
)

private val LightScheme = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFECECEC),
    onPrimaryContainer = Color(0xFF171A20),
    secondary = GoodLight,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFFFFF),
    onSecondaryContainer = Color(0xFF171A20),
    tertiary = BadLight,
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF171A20),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF171A20),
    surfaceVariant = Color(0xFFEDEDED),
    onSurfaceVariant = Color(0xFF5C5E62),
    surfaceContainerLowest = Color(0xFFFAFAFA),
    surfaceContainerLow = Color(0xFFF7F7F7),
    surfaceContainer = Color(0xFFF4F4F4),
    surfaceContainerHigh = Color(0xFFECECEC),
    surfaceContainerHighest = Color(0xFFDDDDDD),
    error = BadLight,
    outline = Color(0xFFD0D0D0),
    outlineVariant = Color(0xFFE4E4E4),
)

private val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

private fun TextStyle.inter(
    weight: FontWeight? = null,
    tracking: Float? = null,
) = copy(
    fontFamily = Inter,
    fontWeight = weight ?: fontWeight,
    letterSpacing = tracking?.sp ?: letterSpacing,
)

private val OpenTvTypography = Typography().let { base ->
    Typography(
        displayLarge = base.displayLarge.inter(FontWeight.Light),
        displayMedium = base.displayMedium.inter(FontWeight.Light),
        displaySmall = base.displaySmall.inter(FontWeight.Light),
        headlineLarge = base.headlineLarge.inter(FontWeight.SemiBold, -0.4f),
        headlineMedium = base.headlineMedium.inter(FontWeight.SemiBold, -0.4f),
        headlineSmall = base.headlineSmall.inter(FontWeight.SemiBold, -0.2f),
        titleLarge = base.titleLarge.inter(FontWeight.SemiBold, -0.2f),
        titleMedium = base.titleMedium.inter(FontWeight.SemiBold, -0.1f),
        titleSmall = base.titleSmall.inter(FontWeight.Medium, 0f),
        bodyLarge = base.bodyLarge.inter(tracking = 0f),
        bodyMedium = base.bodyMedium.inter(tracking = 0f),
        bodySmall = base.bodySmall.inter(tracking = 0f),
        labelLarge = base.labelLarge.inter(FontWeight.Medium, 0f),
        labelMedium = base.labelMedium.inter(FontWeight.Medium, 0f),
        labelSmall = base.labelSmall.inter(FontWeight.Medium, 0f),
    )
}

private val OpenTvShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

@Composable
fun OpenTvTheme(content: @Composable () -> Unit) {
    val settings by OpenTvApp.graph.playerPrefs.settings.collectAsStateWithLifecycle(initialValue = null)
    val dark = when (settings?.themeMode ?: PlayerSettings.THEME_SYSTEM) {
        PlayerSettings.THEME_DARK -> true
        PlayerSettings.THEME_LIGHT -> false
        else -> isSystemInDarkTheme()
    }
    // In-app theme can diverge from system: sync system-bar icon contrast to the drawn canvas.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(LocalOtvDark provides dark) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            typography = OpenTvTypography,
            shapes = OpenTvShapes,
            content = content,
        )
    }
}
