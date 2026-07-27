package com.buco7854.opentv.ui.downloads

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.buco7854.opentv.OpenTvApp
import com.buco7854.opentv.R
import com.buco7854.opentv.core.model.Download
import com.buco7854.opentv.core.model.DownloadStatus
import com.buco7854.opentv.download.DownloadStorage
import com.buco7854.opentv.ui.components.EmptyState
import com.buco7854.opentv.ui.components.OtvProgressBar
import com.buco7854.opentv.ui.components.combinedPadding
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale

class DownloadsViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = OpenTvApp.graph
    val downloads = graph.downloads.downloads
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun pause(item: Download) = viewModelScope.launch { graph.downloads.pause(item) }
    fun resume(item: Download) = viewModelScope.launch { graph.downloads.resume(item) }
    fun retry(item: Download) = viewModelScope.launch { graph.downloads.retry(item) }
    fun delete(item: Download) = viewModelScope.launch { graph.downloads.delete(item) }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format(Locale.US, "%.1f GB", bytes / 1e9)
    bytes >= 1_000_000 -> String.format(Locale.US, "%.0f MB", bytes / 1e6)
    bytes >= 1_000 -> String.format(Locale.US, "%.0f kB", bytes / 1e3)
    else -> "$bytes B"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    onBack: () -> Unit,
    onPlay: (url: String, title: String) -> Unit,
    viewModel: DownloadsViewModel = viewModel(),
) {
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.common_downloads)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        // Shell already reserves dock space; zero insets avoid a double gap.
        contentWindowInsets = WindowInsets(0.dp),
    ) { padding ->
        if (downloads.isEmpty()) {
            Column(Modifier.padding(padding).fillMaxSize()) {
                EmptyState(
                    stringResource(R.string.downloads_empty_title),
                    stringResource(R.string.downloads_empty_subtitle),
                )
            }
            return@Scaffold
        }
        LazyColumn(
            contentPadding = combinedPadding(padding, PaddingValues(16.dp)),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(downloads, key = { it.id }) { item ->
                DownloadCard(
                    item = item,
                    onPlay = { onPlay(DownloadStorage.playableUri(item.filePath), item.title) },
                    onPause = { viewModel.pause(item) },
                    onResume = { viewModel.resume(item) },
                    onRetry = { viewModel.retry(item) },
                    onDelete = { viewModel.delete(item) },
                )
            }
        }
    }
}

@Composable
private fun DownloadCard(
    item: Download,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        item.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val progressText = if (item.totalBytes > 0) {
                        stringResource(
                            R.string.downloads_bytes_of,
                            formatBytes(item.downloadedBytes),
                            formatBytes(item.totalBytes),
                        )
                    } else {
                        formatBytes(item.downloadedBytes)
                    }
                    val statusText = when (item.status) {
                        DownloadStatus.QUEUED -> stringResource(R.string.downloads_queued)
                        DownloadStatus.RUNNING -> progressText
                        DownloadStatus.PAUSED -> stringResource(R.string.downloads_paused, progressText)
                        DownloadStatus.DONE -> stringResource(R.string.downloads_saved, formatBytes(item.totalBytes))
                        DownloadStatus.FAILED -> item.error
                            ?.let { stringResource(R.string.downloads_failed_reason, it) }
                            ?: stringResource(R.string.downloads_failed)
                        else -> stringResource(R.string.downloads_cancelled)
                    }
                    Text(
                        statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = when (item.status) {
                            DownloadStatus.DONE -> MaterialTheme.colorScheme.onSurfaceVariant
                            DownloadStatus.FAILED -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                when (item.status) {
                    DownloadStatus.DONE -> IconButton(onClick = onPlay) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = stringResource(R.string.common_play), tint = MaterialTheme.colorScheme.onSurface)
                    }
                    DownloadStatus.QUEUED, DownloadStatus.RUNNING -> IconButton(onClick = onPause) {
                        Icon(Icons.Rounded.Pause, contentDescription = stringResource(R.string.common_pause))
                    }
                    DownloadStatus.PAUSED -> IconButton(onClick = onResume) {
                        Icon(
                            Icons.Rounded.PlayArrow,
                            contentDescription = stringResource(R.string.common_resume),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    else -> IconButton(onClick = onRetry) {
                        Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.common_retry))
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.common_delete),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if ((item.status == DownloadStatus.RUNNING || item.status == DownloadStatus.PAUSED) &&
                item.totalBytes > 0
            ) {
                Spacer(Modifier.height(10.dp))
                OtvProgressBar(
                    progress = { (item.downloadedBytes.toFloat() / item.totalBytes).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
