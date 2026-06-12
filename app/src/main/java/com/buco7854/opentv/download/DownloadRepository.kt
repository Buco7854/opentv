package com.buco7854.opentv.download

import android.content.Context
import android.os.Environment
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.buco7854.opentv.data.db.AppDatabase
import com.buco7854.opentv.data.db.ChannelEntity
import com.buco7854.opentv.data.db.DownloadEntity
import com.buco7854.opentv.data.db.DownloadStatus
import java.io.File
import java.util.concurrent.TimeUnit

class DownloadRepository(private val context: Context, private val db: AppDatabase) {

    val downloads = db.downloadDao().observeAll()

    private fun targetFile(channel: ChannelEntity, downloadId: Long): File {
        val dir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir,
            "OpenTV"
        )
        // Extension must come from the last path segment only, and be sanitized -
        // otherwise a URL like "http://host/vod/123" would yield slashes in the
        // "extension" and the file would land outside the downloads directory.
        val lastSegment = channel.url.substringBefore('?').substringBefore('#').substringAfterLast('/')
        val extension = lastSegment.substringAfterLast('.', "")
            .filter { it.isLetterOrDigit() }.take(5).ifEmpty { "mp4" }
        val safeName = channel.name.map { if (it.isLetterOrDigit() || it in " ._-()[]") it else '_' }
            .joinToString("").trim().take(120).ifEmpty { "video" }
        // The row id makes the path unique: identically-named VODs (quality
        // variants, duplicates across groups) must never share a file.
        return File(dir, "$safeName-$downloadId.$extension")
    }

    /**
     * Queue a VOD download. Returns null when queued, or a user-facing reason
     * when the same URL is already queued, running, or finished.
     */
    suspend fun enqueue(channel: ChannelEntity): String? {
        val existing = db.downloadDao().findByUrlWithStatus(
            channel.url,
            listOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING, DownloadStatus.DONE),
        )
        if (existing != null) {
            return if (existing.status == DownloadStatus.DONE) "Already downloaded" else "Already downloading"
        }
        val id = db.downloadDao().insert(
            DownloadEntity(title = channel.name, url = channel.url, filePath = "")
        )
        db.downloadDao().get(id)?.let {
            db.downloadDao().update(it.copy(filePath = targetFile(channel, id).absolutePath))
        }
        enqueueWork(id)
        return null
    }

    suspend fun cancel(item: DownloadEntity) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(item.id))
        db.downloadDao().update(item.copy(status = DownloadStatus.CANCELLED))
    }

    suspend fun retry(item: DownloadEntity) {
        db.downloadDao().update(item.copy(status = DownloadStatus.QUEUED, error = null))
        enqueueWork(item.id)
    }

    suspend fun delete(item: DownloadEntity) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(item.id))
        File(item.filePath).takeIf { item.filePath.isNotEmpty() }?.delete()
        db.downloadDao().delete(item.id)
    }

    /**
     * Unique work keyed by download id: a double-tap or a retry while the old
     * worker still runs can never produce two workers writing the same file.
     */
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
