package com.buco7854.opentv.ui.components

import android.app.Application
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.buco7854.opentv.R
import com.buco7854.opentv.core.model.Download
import com.buco7854.opentv.core.model.DownloadStatus
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.buco7854.opentv.OpenTvApp
import kotlinx.coroutines.launch

class DownloadActionsViewModel(app: Application) : AndroidViewModel(app) {
    private val downloads = OpenTvApp.graph.downloads

    fun pause(download: Download) {
        viewModelScope.launch { downloads.pause(download) }
    }

    fun resume(download: Download) {
        viewModelScope.launch { downloads.resume(download) }
    }

    fun delete(download: Download) {
        viewModelScope.launch { downloads.delete(download) }
    }
}

/**
 * Stateful download control: tap toggles download/pause/resume, long-press deletes.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun DownloadStateIcon(
    state: Download?,
    onDownload: () -> Unit,
    viewModel: DownloadActionsViewModel = viewModel(),
) {
    var confirmDelete by remember { mutableStateOf(false) }

    fun longPress(): (() -> Unit)? = state?.let { { confirmDelete = true } }

    when (state?.status) {
        DownloadStatus.RUNNING, DownloadStatus.QUEUED -> Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .combinedClickable(
                    onClick = { viewModel.pause(state) },
                    onLongClick = { confirmDelete = true },
                ),
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
                    color = MaterialTheme.colorScheme.onSurface,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(26.dp),
                    strokeWidth = 2.5.dp,
                    color = MaterialTheme.colorScheme.onSurface,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
            }
            Icon(
                Icons.Rounded.Pause,
                contentDescription = stringResource(R.string.downloads_pause_download),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
        }

        DownloadStatus.PAUSED -> Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .combinedClickable(
                    onClick = { viewModel.resume(state) },
                    onLongClick = { confirmDelete = true },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.PlayArrow,
                contentDescription = stringResource(R.string.downloads_resume_download),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }

        DownloadStatus.DONE -> Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .combinedClickable(onClick = { confirmDelete = true }, onLongClick = { confirmDelete = true }),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.DownloadDone, contentDescription = stringResource(R.string.downloads_downloaded), tint = MaterialTheme.colorScheme.onSurface)
        }

        else -> IconButton(onClick = onDownload) {
            Icon(Icons.Outlined.Download, contentDescription = stringResource(R.string.downloads_download), tint = MaterialTheme.colorScheme.onSurface)
        }
    }

    if (confirmDelete && state != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.downloads_remove_title)) },
            text = {
                Text(
                    if (state.status == DownloadStatus.DONE) {
                        stringResource(R.string.downloads_delete_file_text, state.title)
                    } else {
                        stringResource(R.string.downloads_delete_partial_text, state.title)
                    }
                )
            },
            confirmButton = {
                OtvTextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete(state)
                }, danger = true) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = { OtvTextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}
