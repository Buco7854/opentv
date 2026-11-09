package com.buco7854.opentv.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.buco7854.opentv.R
import com.buco7854.opentv.core.model.ChannelKind
import com.buco7854.opentv.ui.shell.libraryControl
import com.buco7854.opentv.ui.shell.libraryControlStyle
import java.text.NumberFormat

/** Shared web/Android tabs: content-sized labels, real gaps, a plain underline and honest counts. */
@Composable
internal fun BrowseTabs(kind: Int, counts: Map<Int, Int>, onSelect: (Int) -> Unit) {
    val horizontal = if (LocalConfiguration.current.screenWidthDp < 640) 16.dp else 24.dp
    Column(Modifier.padding(horizontal = horizontal)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            listOf(ChannelKind.LIVE, ChannelKind.MOVIE, ChannelKind.SERIES).forEach { value ->
                val active = kind == value
                val interaction = remember { MutableInteractionSource() }
                val style = libraryControlStyle(false, true, interaction)
                Column(Modifier.width(IntrinsicSize.Max)) {
                    Row(
                        Modifier.heightIn(min = 48.dp).libraryControl(style, interaction, active = active, role = Role.Tab, onClick = { onSelect(value) })
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(stringResource(when (value) {
                            ChannelKind.MOVIE -> R.string.common_movies
                            ChannelKind.SERIES -> R.string.common_series
                            else -> R.string.common_live
                        }), style = MaterialTheme.typography.labelLarge,
                            color = if (active) MaterialTheme.colorScheme.onSurface else style.foreground)
                        Text(counts[value]?.let { NumberFormat.getIntegerInstance().format(it) } ?: "—",
                            style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box(Modifier.fillMaxWidth().height(2.dp).background(if (active) MaterialTheme.colorScheme.onSurface else androidx.compose.ui.graphics.Color.Transparent))
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
