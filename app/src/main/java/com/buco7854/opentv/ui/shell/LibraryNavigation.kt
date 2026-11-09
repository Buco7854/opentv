package com.buco7854.opentv.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.buco7854.opentv.R
import com.buco7854.opentv.ui.theme.LocalOtvDark
import com.buco7854.opentv.ui.theme.ViewingAccent

internal val LibraryControlShape = RoundedCornerShape(8.dp)

/** The same antenna-and-screen outline used by the web masthead. */
private val OpenTvMark = ImageVector.Builder("OpenTV", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.6f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(17f, 2f); lineTo(12f, 7f); lineTo(7f, 2f)
        moveTo(4f, 7f); horizontalLineTo(20f)
        curveTo(21.1f, 7f, 22f, 7.9f, 22f, 9f)
        verticalLineTo(20f); curveTo(22f, 21.1f, 21.1f, 22f, 20f, 22f)
        horizontalLineTo(4f); curveTo(2.9f, 22f, 2f, 21.1f, 2f, 20f)
        verticalLineTo(9f); curveTo(2f, 7.9f, 2.9f, 7f, 4f, 7f)
        close()
    }
}.build()

/** Shared with the web dock: subdued press tone, a contained focus ring, no growing targets. */
internal data class LibraryControlStyle(
    val background: Color,
    val foreground: Color,
    val focus: Color,
    val onFocusChanged: (Boolean) -> Unit,
)

@Composable
internal fun libraryControlStyle(
    active: Boolean,
    enabled: Boolean,
    interaction: MutableInteractionSource,
    base: Color = Color.Transparent,
): LibraryControlStyle {
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    var focused by remember { mutableStateOf(false) }
    val press = (if (LocalOtvDark.current) Color.White.copy(alpha = .08f) else Color.Black.copy(alpha = .06f))
        .compositeOver(MaterialTheme.colorScheme.background)
    return LibraryControlStyle(
        background = if (enabled && (active || hovered || pressed)) press else base,
        foreground = when {
            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .4f)
            active || hovered || focused -> MaterialTheme.colorScheme.onSurface
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        focus = if (enabled && focused) ViewingAccent else Color.Transparent,
        onFocusChanged = { focused = it },
    )
}

internal fun Modifier.libraryControl(
    style: LibraryControlStyle,
    interaction: MutableInteractionSource,
    enabled: Boolean = true,
    active: Boolean = false,
    role: Role = Role.Button,
    onClick: () -> Unit,
): Modifier = clip(LibraryControlShape)
    .background(style.background)
    .border(2.dp, style.focus, LibraryControlShape)
    .onFocusChanged { style.onFocusChanged(it.isFocused) }
    .then(
        if (role == Role.Tab) Modifier.selectable(active, interaction, indication = null, enabled = enabled, role = role, onClick = onClick)
        else Modifier.clickable(interaction, indication = null, enabled = enabled, role = role, onClick = onClick),
    )
    .semantics(mergeDescendants = true) {}

enum class DockSection(val labelRes: Int) {
    HOME(R.string.common_home), BROWSE(R.string.common_browse), FAVORITES(R.string.common_favorites),
    SEARCH(R.string.common_search), MORE(R.string.common_more);

    val icon get() = when (this) {
        HOME -> Icons.Outlined.Home
        BROWSE -> Icons.Outlined.GridView
        FAVORITES -> Icons.Outlined.FavoriteBorder
        SEARCH -> Icons.Outlined.Search
        MORE -> Icons.Outlined.MoreVert
    }
}

/** Five separated targets: 320px - 16px outer padding - 4×8px gaps = 5×54.4px. */
@Composable
fun OpenTvDock(
    hasActivePlaylist: Boolean,
    activeSection: DockSection?,
    onOpenPanel: () -> Unit,
    onSection: (DockSection) -> Unit,
    wide: Boolean = false,
) {
    Column(Modifier.background(MaterialTheme.colorScheme.background)) {
        if (!wide) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier.then(if (wide) Modifier else Modifier.fillMaxWidth().navigationBarsPadding())
                .padding(horizontal = if (wide) 0.dp else 8.dp, vertical = if (wide) 0.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (wide) 12.dp else 8.dp),
        ) {
            DockSection.entries.forEach { section ->
                NavigationDestination(
                    section = section,
                    active = activeSection == section,
                    enabled = hasActivePlaylist || section !in setOf(DockSection.BROWSE, DockSection.SEARCH),
                    wide = wide,
                    onClick = { if (section == DockSection.MORE) onOpenPanel() else onSection(section) },
                    modifier = if (wide) Modifier else Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
internal fun NavigationDestination(
    section: DockSection,
    active: Boolean,
    enabled: Boolean,
    wide: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val style = libraryControlStyle(active, enabled, interaction)
    val control = modifier.heightIn(min = if (wide) 48.dp else 56.dp)
        .libraryControl(style, interaction, enabled, active, if (section == DockSection.MORE) Role.Button else Role.Tab, onClick)
    if (wide) {
        Row(control.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(section.icon, null, Modifier.size(18.dp), tint = style.foreground)
            Text(stringResource(section.labelRes), style = MaterialTheme.typography.labelLarge, color = style.foreground)
        }
    } else {
        Column(
            control.padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically),
        ) {
            Icon(section.icon, null, Modifier.size(22.dp), tint = style.foreground)
            Text(stringResource(section.labelRes), style = MaterialTheme.typography.labelSmall,
                fontSize = if (LocalConfiguration.current.screenWidthDp < 360) 10.sp else 11.sp, lineHeight = 16.sp,
                color = style.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Same global brand / navigation / source regions as the browser masthead. */
@Composable
fun OpenTvMasthead(
    hasActivePlaylist: Boolean,
    activeSection: DockSection?,
    playlistTitle: String?,
    canChoosePlaylist: Boolean,
    onHome: () -> Unit,
    onChoosePlaylist: () -> Unit,
    onOpenPanel: () -> Unit,
    onSection: (DockSection) -> Unit,
    wide: Boolean,
) {
    Column(Modifier.background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Row(
                Modifier.widthIn(max = 1180.dp).fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = if (wide) 24.dp else 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (wide) 24.dp else 16.dp),
            ) {
                val brandInteraction = remember { MutableInteractionSource() }
                val style = libraryControlStyle(false, true, brandInteraction)
                val description = "OpenTV · ${stringResource(R.string.common_home)}"
                Row(
                    Modifier.heightIn(min = 48.dp).libraryControl(style, brandInteraction, onClick = onHome)
                        .semantics { contentDescription = description }.padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(OpenTvMark, null, Modifier.size(25.dp))
                    Text("OpenTV", style = MaterialTheme.typography.titleMedium, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-.6).sp)
                }
                if (wide) OpenTvDock(hasActivePlaylist, activeSection, onOpenPanel, onSection, wide = true)
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    PlaylistSelector(
                        title = playlistTitle ?: "—",
                        onChoose = onChoosePlaylist,
                        enabled = canChoosePlaylist,
                        modifier = Modifier.widthIn(max = if (wide) 180.dp else 156.dp),
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
