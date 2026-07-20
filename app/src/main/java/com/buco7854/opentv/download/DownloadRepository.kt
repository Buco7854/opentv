package com.buco7854.opentv.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.buco7854.opentv.core.model.Channel
import com.buco7854.opentv.core.model.Download
import com.buco7854.opentv.core.model.DownloadStatus
import com.buco7854.opentv.core.storage.Storage
import com.buco7854.opentv.data.prefs.PlayerPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import com.buco7854.opentv.R

class DownloadRepository(
    private val context: Context,
    private val storage: Storage,
    private val prefs: PlayerPrefs,
) {

    private val store = storage.downloads

    val downloads = store.observeAll()

    private suspend fun targetPath(channel: Channel, downloadId: Long): String {
        // Extension from the last segment only, sanitized, so it can't escape the downloads dir.
        val lastSegment = channel.url.substringBefore('?').substringBefore('#').substringAfterLast('/')
        val extension = lastSegment.substringAfterLast('.', "")
            .filter { it.isLetterOrDigit() }.take(5).ifEmpty { "mp4" }
        val safeName = channel.name.map { if (it.isLetterOrDigit() || it in " ._-()[]") it else '_' }
            .joinToString("").trim().take(120).ifEmpty { "video" }
        // Row id keeps identically-named VODs from sharing a file.
        return DownloadStorage.createTarget(
            context = context,
            treeUri = prefs.settings.first().downloadDirUri,
            baseName = "$safeName-$downloadId",
            extension = extension,
        )
    }

    /** Queue a VOD download. Returns null on success, or a reason if the URL already exists. */
    suspend fun enqueue(channel: Channel): String? {
        val existing = store.findByUrlWithStatus(
            channel.url,
            listOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING, DownloadStatus.DONE, DownloadStatus.PAUSED),
        )
        if (existing != null) {
            return when (existing.status) {
                DownloadStatus.DONE -> context.getString(R.string.downloads_already_downloaded)
                DownloadStatus.PAUSED -> context.getString(R.string.downloads_paused_resume_hint)
                else -> context.getString(R.string.downloads_already_downloading)
            }
        }
        val id = store.insert(
            Download(title = channel.name, url = channel.url, filePath = "")
        )
        store.get(id)?.let {
            store.update(it.copy(filePath = targetPath(channel, id)))
        }
        enqueueWork(id)
        return null
    }

    /** Pause keeps the partial file (resume uses a Range request). Written from a fresh row so progress isn't rolled back. */
    suspend fun pause(item: Download) {
        store.get(item.id)?.let { store.update(it.copy(status = DownloadStatus.PAUSED)) }
        WorkManager.getInstance(context).cancelUniqueWork(workName(item.id))
    }

    suspend fun resume(item: Download) = retry(item)

    suspend fun retry(item: Download) {
        store.update(item.copy(status = DownloadStatus.QUEUED, error = null))
        enqueueWork(item.id)
    }

    suspend fun delete(item: Download) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(item.id))
        DownloadStorage.delete(context, item.filePath)
        store.delete(item.id)
    }

    data class MoveResult(val moved: Int, val alreadyThere: Int, val failed: Int)

    /** How many completed downloads aren't already in the current folder. */
    suspend fun completedElsewhereCount(): Int = withContext(Dispatchers.IO) {
        val treeUri = prefs.settings.first().downloadDirUri
        store.getByStatus(DownloadStatus.DONE).count {
            it.filePath.isNotEmpty() &&
                DownloadStorage.relocateNeeded(context, treeUri, it.filePath)
        }
    }

    /** Moves all completed downloads into the current folder. Uncancellable so navigating away can't corrupt a file. */
    suspend fun moveCompletedToCurrentFolder(): MoveResult = withContext(Dispatchers.IO + NonCancellable) {
        val treeUri = prefs.settings.first().downloadDirUri
        var moved = 0
        var already = 0
        var failed = 0
        for (item in store.getByStatus(DownloadStatus.DONE)) {
            if (item.filePath.isEmpty()) {
                failed++
                continue
            }
            when (val r = DownloadStorage.relocate(context, treeUri, item.filePath)) {
                is DownloadStorage.Relocation.Moved -> {
                    store.update(item.copy(filePath = r.newPath))
                    moved++
                }
                DownloadStorage.Relocation.AlreadyThere -> already++
                is DownloadStorage.Relocation.Failed -> failed++
            }
        }
        MoveResult(moved, already, failed)
    }

    /** Unique work keyed by id so a double-tap or retry can't spawn two workers on one file. */
    private fun enqueueWork(id: Long) {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.KEY_DOWNLOAD_ID to id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(workName(id), ExistingWorkPolicy.KEEP, request)
    }

    private fun workName(id: Long) = "download-$id"
}
