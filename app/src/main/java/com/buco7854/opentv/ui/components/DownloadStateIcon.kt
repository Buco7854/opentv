package com.buco7854.opentv.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.buco7854.opentv.data.db.DownloadEntity
import com.buco7854.opentv.data.db.DownloadStatus
import com.buco7854.opentv.ui.theme.Mint

/**
 * Download affordance that reflects live state: idle arrow, circular progress
 * while queued/running, and a checkmark when the file is saved.
 */
@Composable
fun DownloadStateIcon(state: DownloadEntity?, onDownload: () -> Unit) {
    when (state?.status) {
        DownloadStatus.RUNNING, DownloadStatus.QUEUED -> Box(
            Modifier.size(48.dp),
            contentAlignment = Alignment.Center,
        ) {
            val fraction = if (state.totalBytes > 0) {
                (state.downloadedBytes.toFloat() / state.totalBytes).coerceIn(0f, 1f)
            } else null
            if (fraction != null && state.status == DownloadStatus.RUNNING) {
                CircularProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.size(26.dp),
                    strokeWidth = 2.5.dp,
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(26.dp),
                    strokeWidth = 2.5.dp,
                )
            }
        }

        DownloadStatus.PAUSED -> Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Icon(
                Icons.Rounded.Pause,
                contentDescription = "Paused",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        DownloadStatus.DONE -> Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.DownloadDone, contentDescription = "Downloaded", tint = Mint)
        }

        else -> IconButton(onClick = onDownload) {
            Icon(
                Icons.Rounded.Download,
                contentDescription = "Download",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
