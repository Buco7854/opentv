package com.buco7854.opentv.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.buco7854.opentv.OpenTvApp
import com.buco7854.opentv.data.db.DownloadStatus
import com.buco7854.opentv.data.net.Http
import com.buco7854.opentv.diag.ErrorLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Streams a VOD file to local storage with a single HTTP request, resuming
 * partially-downloaded files via Range headers instead of starting over -
 * important both for big movie files and for not re-requesting gigabytes from
 * a provider that counts your traffic.
 */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_DOWNLOAD_ID = "download_id"
        const val CHANNEL_ID = "downloads"

        fun ensureNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= 26) {
                val manager = context.getSystemService(NotificationManager::class.java)
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    private val dao = OpenTvApp.graph.db.downloadDao()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val downloadId = inputData.getLong(KEY_DOWNLOAD_ID, -1)
        val item = dao.get(downloadId) ?: return@withContext Result.failure()

        try {
            setForeground(foregroundInfo(item.title, 0, 0))
        } catch (_: Exception) {
            // Expedited/foreground may be unavailable; keep downloading in the background.
        }

        val file = File(item.filePath)
        file.parentFile?.mkdirs()
        val existing = if (file.exists()) file.length() else 0L

        try {
            val requestBuilder = Request.Builder()
                .url(item.url)
                .header("User-Agent", Http.USER_AGENT)
            if (existing > 0) requestBuilder.header("Range", "bytes=$existing-")

            Http.ok.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body ?: throw IOException("Empty body")

                val resuming = response.code == 206
                var downloaded = if (resuming) existing else 0L
                val total = if (resuming) existing + body.contentLength() else body.contentLength()

                dao.updateProgress(item.id, downloaded, total.coerceAtLeast(0), DownloadStatus.RUNNING)

                RandomAccessFile(file, "rw").use { out ->
                    if (resuming) out.seek(existing) else out.setLength(0)
                    val buffer = ByteArray(256 * 1024)
                    var lastUpdate = 0L
                    body.byteStream().use { input ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            out.write(buffer, 0, read)
                            downloaded += read
                            val now = System.currentTimeMillis()
                            if (now - lastUpdate > 750) {
                                lastUpdate = now
                                dao.updateProgress(item.id, downloaded, total.coerceAtLeast(0), DownloadStatus.RUNNING)
                                runCatching {
                                    setForeground(foregroundInfo(item.title, downloaded, total))
                                }
                            }
                        }
                    }
                }
                dao.updateProgress(item.id, downloaded, downloaded, DownloadStatus.DONE)
            }
            Result.success()
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                dao.get(downloadId)?.let { dao.update(it.copy(status = DownloadStatus.CANCELLED)) }
            }
            throw e
        } catch (e: Exception) {
            ErrorLog.log("Download: ${item.title}", e)
            dao.get(downloadId)?.let {
                dao.update(it.copy(status = DownloadStatus.FAILED, error = ErrorLog.describe(e)))
            }
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private fun foregroundInfo(title: String, downloaded: Long, total: Long): ForegroundInfo {
        val notification: Notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading $title")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply {
                if (total > 0) setProgress(100, ((downloaded * 100) / total).toInt(), false)
                else setProgress(0, 0, true)
            }
            .build()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(id.hashCode(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id.hashCode(), notification)
        }
    }
}
