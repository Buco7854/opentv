package com.buco7854.opentv.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.buco7854.opentv.R
import com.buco7854.opentv.ui.components.HeaderIconButton
import com.buco7854.opentv.ui.components.ScreenHeader

@Composable
internal fun BrowseHeading(title: String, onBack: (() -> Unit)?) {
    BoxWithConstraints {
        val narrow = maxWidth < 640.dp
        ScreenHeader(title, onBack, titleStyle = MaterialTheme.typography.titleLarge.copy(
            fontSize = if (narrow) 20.sp else 22.sp, lineHeight = if (narrow) 28.sp else 30.sp,
        ))
    }
}

/** Category identity stays above the tabs. Counts and actions form an independent toolbar. */
@Composable
internal fun BrowseToolbar(
    summary: String,
    connections: String?,
    connectionLimitReached: Boolean,
    gridView: Boolean,
    onToggleGridView: (() -> Unit)?,
    onOpenAccount: (() -> Unit)?,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val narrow = maxWidth < 640.dp
        val stack = LocalDensity.current.fontScale >= 1.3f
        val metadata: @Composable () -> Unit = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (connections != null) Text(connections, style = MaterialTheme.typography.bodySmall,
                    color = if (connectionLimitReached) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val actions: @Composable () -> Unit = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onOpenAccount != null) HeaderIconButton(Icons.Outlined.Person, stringResource(R.string.account_title), onOpenAccount)
                if (onToggleGridView != null) HeaderIconButton(
                    if (gridView) Icons.AutoMirrored.Outlined.ViewList else Icons.Outlined.GridView,
                    stringResource(if (gridView) R.string.common_list_view else R.string.common_grid_view), onToggleGridView,
                )
            }
        }
        Column(Modifier.padding(horizontal = if (narrow) 16.dp else 24.dp, vertical = 8.dp)) {
            if (stack) {
                metadata()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { actions() }
            } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f)) { metadata() }
                actions()
            }
        }
    }
}
