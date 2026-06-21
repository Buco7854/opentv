package com.buco7854.opentv.ui.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.buco7854.opentv.OpenTvApp
import com.buco7854.opentv.data.db.DownloadEntity
import com.buco7854.opentv.data.db.DownloadStatus
import com.buco7854.opentv.ui.theme.Mint
import kotlinx.coroutines.launch

/**
 * Download affordance reflecting live state, self-contained for pause/resume/
 * delete:
 *  - idle: tap downloads;
 *  - running/queued: progress ring, tap pauses, long-press deletes;
 *  - paused: tap resumes, long-press deletes;
 *  - done: checkmark, long-press deletes.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun DownloadStateIcon(state: DownloadEntity?, onDownload: () -> Unit) {
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    val downloads = OpenTvApp.graph.downloads

    fun longPress(): (() -> Unit)? = state?.let { { confirmDelete = true } }

    when (state?.status) {
        DownloadStatus.RUNNING, DownloadStatus.QUEUED -> Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .combinedClickable(
                    onClick = { scope.launch { downloads.pause(state) } },
                    onLongClick = { confirmDelete = true },
                ),
            contentAlignment = Alignment.Center,
        ) {
            val fraction = if (state.totalBytes > 0) {
                (state.downloadedBytes.toFloat() / state.totalBytes).coerceIn(0f, 1f)
            } else null
            if (fraction != null && state.status == DownloadStatus.RUNNING) {
                CircularProgressIndicator(progress = { fraction }, modifier = Modifier.size(26.dp), strokeWidth = 2.5.dp)
            } else {
                CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 2.5.dp)
            }
            // Pause glyph hints the ring is tappable.
            Icon(
                Icons.Rounded.Pause,
                contentDescription = "Pause download",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
        }

        DownloadStatus.PAUSED -> Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .combinedClickable(
                    onClick = { scope.launch { downloads.resume(state) } },
                    onLongClick = { confirmDelete = true },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = "Resume download", tint = MaterialTheme.colorScheme.primary)
        }

        DownloadStatus.DONE -> Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .combinedClickable(onClick = { confirmDelete = true }, onLongClick = { confirmDelete = true }),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.DownloadDone, contentDescription = "Downloaded", tint = Mint)
        }

        else -> IconButton(onClick = onDownload) {
            Icon(Icons.Rounded.Download, contentDescription = "Download", tint = MaterialTheme.colorScheme.primary)
        }
    }

    if (confirmDelete && state != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Remove download?") },
            text = {
                Text(
                    if (state.status == DownloadStatus.DONE) {
                        "Delete the downloaded file for \"${state.title}\"?"
                    } else {
                        "Cancel and delete the partial download for \"${state.title}\"?"
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch { downloads.delete(state) }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
