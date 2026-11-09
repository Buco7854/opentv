package com.buco7854.opentv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.buco7854.opentv.R
import com.buco7854.opentv.ui.shell.libraryControl
import com.buco7854.opentv.ui.shell.libraryControlStyle

/** A task heading may grow with text; its controls never compete with a narrow title. */
@Composable
internal fun ScreenHeader(
    title: String,
    onBack: (() -> Unit)?,
    supportingText: List<String> = emptyList(),
    backModifier: Modifier = Modifier,
    titleStyle: TextStyle = utilityTitleStyle(),
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    BoxWithConstraints(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
        val narrow = maxWidth < 640.dp
        Column(Modifier.padding(horizontal = if (narrow) 16.dp else 24.dp, vertical = 4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onBack != null) HeaderIconButton(Icons.AutoMirrored.Outlined.ArrowBack,
                    stringResource(R.string.common_back), onBack, backModifier)
                Column(Modifier.weight(1f).heightIn(min = 48.dp).padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    ScreenTitle(title, style = titleStyle)
                    supportingText.forEach { text ->
                        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (!narrow && actions != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions)
            }
            if (narrow && actions != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), content = actions)
        }
    }
}

@Composable
private fun utilityTitleStyle() = MaterialTheme.typography.titleMedium.copy(
    fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold,
)

/** Shared compact title for utility pages, sheets and task dialogs. */
@Composable
internal fun ScreenTitle(title: String, modifier: Modifier = Modifier, style: TextStyle = utilityTitleStyle()) {
    Text(title, modifier = modifier.semantics { heading() }, style = style, color = MaterialTheme.colorScheme.onSurface,
        maxLines = 6, overflow = TextOverflow.Ellipsis)
}

/** Shared neutral hover and inset focus treatment, with a real 48dp target. */
@Composable
internal fun HeaderIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val style = libraryControlStyle(false, enabled, interaction)
    Box(modifier.size(48.dp).libraryControl(style, interaction, enabled, onClick = onClick)
        .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(20.dp), tint = style.foreground)
    }
}
