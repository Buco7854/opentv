package com.buco7854.opentv.download

import android.content.Context
import com.buco7854.opentv.core.model.Channel
import com.buco7854.opentv.core.download.DownloadFileName
import com.buco7854.opentv.core.model.Download
import com.buco7854.opentv.core.model.DownloadStatus
import com.buco7854.opentv.core.storage.DownloadStore
import com.buco7854.opentv.data.prefs.PlayerPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import com.buco7854.opentv.R

class DownloadRepository(
    private val context: Context,
    private val store: DownloadStore,
    private val prefs: PlayerPrefs,
    private val scheduler: DownloadScheduler,
) {

    val downloads = store.observeAll()

    private suspend fun targetPath(channel: Channel, downloadId: Long): String {
        val target = DownloadFileName.from(channel.name, channel.url, downloadId)
        return DownloadStorage.createTarget(
            context = context,
            treeUri = prefs.settings.first().downloadDirUri,
            baseName = target.baseName,
            extension = target.extension,
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
        scheduler.enqueue(id)
        return null
    }

    /** Pause keeps the partial file (resume uses a Range request). Written from a fresh row so progress isn't rolled back. */
    suspend fun pause(item: Download) {
        store.get(item.id)?.let { store.update(it.copy(status = DownloadStatus.PAUSED)) }
        scheduler.cancel(item.id)
    }

    suspend fun resume(item: Download) = retry(item)

    suspend fun retry(item: Download) {
        val current = store.get(item.id) ?: return
        if (current.status !in listOf(
                DownloadStatus.PAUSED,
                DownloadStatus.FAILED,
                DownloadStatus.CANCELLED,
            )
        ) return
        store.update(current.copy(status = DownloadStatus.QUEUED, error = null))
        scheduler.enqueue(item.id)
    }

    suspend fun delete(item: Download) {
        scheduler.cancel(item.id)
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

}
