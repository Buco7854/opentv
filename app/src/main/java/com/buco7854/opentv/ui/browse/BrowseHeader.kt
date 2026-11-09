package com.buco7854.opentv.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

/** The catalog summary and its display control stay below the category identity and tabs. */
@Composable
internal fun BrowseToolbar(
    summary: String,
    gridView: Boolean,
    onToggleGridView: (() -> Unit)?,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val narrow = maxWidth < 640.dp
        Row(
            Modifier.fillMaxWidth().padding(horizontal = if (narrow) 16.dp else 24.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(summary, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (onToggleGridView != null) HeaderIconButton(
                if (gridView) Icons.AutoMirrored.Outlined.ViewList else Icons.Outlined.GridView,
                stringResource(if (gridView) R.string.common_list_view else R.string.common_grid_view), onToggleGridView,
            )
        }
    }
}
