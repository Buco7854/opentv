package com.buco7854.opentv.ui.downloads

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.buco7854.opentv.OpenTvApp
import com.buco7854.opentv.data.db.DownloadEntity
import com.buco7854.opentv.data.db.DownloadStatus
import com.buco7854.opentv.download.DownloadStorage
import com.buco7854.opentv.ui.components.EmptyState
import com.buco7854.opentv.ui.theme.Mint
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale

class DownloadsViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = OpenTvApp.graph
    val downloads = graph.downloads.downloads
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun cancel(item: DownloadEntity) = viewModelScope.launch { graph.downloads.cancel(item) }
    fun retry(item: DownloadEntity) = viewModelScope.launch { graph.downloads.retry(item) }
    fun delete(item: DownloadEntity) = viewModelScope.launch { graph.downloads.delete(item) }
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
    val downloads by viewModel.downloads.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Downloads") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        if (downloads.isEmpty()) {
            Column(Modifier.padding(padding).fillMaxSize()) {
                EmptyState(
                    "No downloads",
                    "Use the download button on movies and episodes to save them for offline viewing.",
                )
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(downloads, key = { it.id }) { item ->
                DownloadCard(
                    item = item,
                    onPlay = { onPlay(DownloadStorage.playableUri(item.filePath), item.title) },
                    onCancel = { viewModel.cancel(item) },
                    onRetry = { viewModel.retry(item) },
                    onDelete = { viewModel.delete(item) },
                )
            }
        }
    }
}

@Composable
private fun DownloadCard(
    item: DownloadEntity,
    onPlay: () -> Unit,
    onCancel: () -> Unit,
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
                    val statusText = when (item.status) {
                        DownloadStatus.QUEUED -> "Queued"
                        DownloadStatus.RUNNING ->
                            "${formatBytes(item.downloadedBytes)}" +
                                (if (item.totalBytes > 0) " of ${formatBytes(item.totalBytes)}" else "")
                        DownloadStatus.DONE -> "Saved · ${formatBytes(item.totalBytes)}"
                        DownloadStatus.FAILED -> "Failed${item.error?.let { ": $it" } ?: ""}"
                        else -> "Cancelled"
                    }
                    Text(
                        statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = when (item.status) {
                            DownloadStatus.DONE -> Mint
                            DownloadStatus.FAILED -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                when (item.status) {
                    DownloadStatus.DONE -> IconButton(onClick = onPlay) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = "Play", tint = Mint)
                    }
                    DownloadStatus.QUEUED, DownloadStatus.RUNNING -> IconButton(onClick = onCancel) {
                        Icon(Icons.Rounded.Cancel, contentDescription = "Cancel")
                    }
                    else -> IconButton(onClick = onRetry) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Retry")
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Rounded.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (item.status == DownloadStatus.RUNNING && item.totalBytes > 0) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { (item.downloadedBytes.toFloat() / item.totalBytes).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
