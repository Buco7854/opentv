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
    private val hubDownloads: HubDownloadCoordinator,
) {
    val downloads = store.observeAll()

    private suspend fun targetPath(channel: Channel, downloadId: Long): String {
        val target = DownloadFileName.from(channel.name, channel.url, downloadId)
        return targetPath(target)
    }

    private suspend fun hubTargetPath(title: String, contentId: String, downloadId: Long): String {
        val target = DownloadFileName.from(title, "$contentId.mp4", downloadId)
        return targetPath(target)
    }

    private suspend fun targetPath(target: DownloadFileName): String {
        val treeUri = prefs.settings.first().downloadDirUri
        return withContext(Dispatchers.IO) {
            val path = DownloadStorage.createTarget(
                context = context,
                treeUri = treeUri,
                baseName = target.baseName,
                extension = target.extension,
            )
            try {
                // The catalog database is destructively recreated, but media files are not.
                // Its row ids can therefore be reused for an old deterministic filename.
                DownloadStorage.truncate(context, path)
            } catch (error: Throwable) {
                DownloadStorage.delete(context, path)
                throw error
            }
            path
        }
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
        try {
            val row = checkNotNull(store.get(id))
            store.update(row.copy(filePath = targetPath(channel, id)))
        } catch (error: Exception) {
            withContext(NonCancellable) {
                store.delete(id)
            }
            throw error
        }
        scheduler.enqueue(id)
        return null
    }

    suspend fun enqueueHub(hubSourceId: Long, contentId: String, title: String): String? {
        return when (
            hubDownloads.enqueue(hubSourceId, contentId, title) { id ->
                hubTargetPath(title, contentId, id)
            }
        ) {
            "downloaded" -> context.getString(R.string.downloads_already_downloaded)
            "paused" -> context.getString(R.string.downloads_paused_resume_hint)
            "downloading" -> context.getString(R.string.downloads_already_downloading)
            else -> null
        }
    }

    fun setForeground(foreground: Boolean) = hubDownloads.setForeground(foreground)

    /** Pause keeps the partial file (resume uses a Range request). Written from a fresh row so progress isn't rolled back. */
    suspend fun pause(item: Download) {
        if (item.hubSourceId != null) {
            hubDownloads.pausePreparation(item)
            return
        }
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
                DownloadStatus.HUB_SIGNED_OUT,
                DownloadStatus.HUB_UNREACHABLE,
                DownloadStatus.HUB_CAPACITY,
                DownloadStatus.HUB_GONE,
            )
        ) return
        if (current.hubSourceId != null) {
            if (current.status == DownloadStatus.HUB_GONE &&
                current.filePath.isNotEmpty()
            ) {
                withContext(Dispatchers.IO) {
                    DownloadStorage.truncate(context, current.filePath)
                }
            }
            hubDownloads.retryPreparation(current)
            return
        }
        store.update(current.copy(status = DownloadStatus.QUEUED, error = null))
        scheduler.enqueue(item.id)
    }

    suspend fun delete(item: Download): String? {
        scheduler.cancel(item.id)
        val deleted = withContext(Dispatchers.IO) {
            DownloadStorage.delete(context, item.filePath)
        }
        if (!deleted) return context.getString(R.string.downloads_delete_failed)
        store.delete(item.id)
        return null
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

    /** Moves completed downloads with copy-then-delete so cancellation leaves every source intact. */
    suspend fun moveCompletedToCurrentFolder(): MoveResult = withContext(Dispatchers.IO) {
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
