package com.buco7854.opentv.download

import android.content.Context
import android.os.Environment
import androidx.work.BackoffPolicy
import androidx.work.Constraints
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

    private fun targetFile(channel: ChannelEntity): File {
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
        return File(dir, "$safeName.$extension")
    }

    /** Queue a VOD download; duplicates of an in-flight/finished URL are ignored. */
    suspend fun enqueue(channel: ChannelEntity): Boolean {
        if (db.downloadDao().findActiveByUrl(channel.url) != null) return false
        val file = targetFile(channel)
        val id = db.downloadDao().insert(
            DownloadEntity(title = channel.name, url = channel.url, filePath = file.absolutePath)
        )
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.KEY_DOWNLOAD_ID to id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .addTag(workTag(id))
            .build()
        WorkManager.getInstance(context).enqueue(request)
        return true
    }

    suspend fun cancel(item: DownloadEntity) {
        WorkManager.getInstance(context).cancelAllWorkByTag(workTag(item.id))
        db.downloadDao().update(item.copy(status = DownloadStatus.CANCELLED))
    }

    suspend fun retry(item: DownloadEntity) {
        db.downloadDao().update(item.copy(status = DownloadStatus.QUEUED, error = null))
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.KEY_DOWNLOAD_ID to item.id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(workTag(item.id))
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }

    suspend fun delete(item: DownloadEntity) {
        WorkManager.getInstance(context).cancelAllWorkByTag(workTag(item.id))
        File(item.filePath).delete()
        db.downloadDao().delete(item.id)
    }

    private fun workTag(id: Long) = "download-$id"
}
