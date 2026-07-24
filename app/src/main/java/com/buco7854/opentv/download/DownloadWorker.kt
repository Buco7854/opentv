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
import com.buco7854.opentv.core.model.DownloadStatus
import com.buco7854.opentv.diag.ErrorLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.IOException
import com.buco7854.opentv.R

/** Streams a VOD file to storage, resuming via Range headers instead of restarting. */
class DownloadWorker(
    context: Context,
    params: WorkerParameters,
    private val dependencies: DownloadWorkerDependencies,
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_DOWNLOAD_ID = "download_id"
        const val CHANNEL_ID = "downloads"

        fun ensureNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= 26) {
                val manager = context.getSystemService(NotificationManager::class.java)
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, context.getString(R.string.downloads_channel_name), NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    private val dao = dependencies.downloads

    /** Thrown when playback starts on the host we're downloading from. */
    private class YieldToPlaybackException : IOException("Paused while streaming from this provider")

    /** Transfer slots this download may share, and whether it must yield to playback (no slot reserved). */
    private class GateConfig(val limit: Int, val yieldToPlayback: Boolean)

    /** Connection budget: auto mode reads the provider's max_connections and reserves one slot for playback. */
    private suspend fun resolveGate(host: String?): GateConfig {
        val preference = dependencies.settings.first().downloadLimit
        if (preference > 0) return GateConfig(limit = preference, yieldToPlayback = true)
        if (host != null) {
            val playlist = dependencies.playlists.getAll().firstOrNull {
                it.xtreamBase?.toHttpUrlOrNull()?.host == host
            }
            if (playlist != null) {
                // Served from the 60s cache when fresh.
                val info = dependencies.accountInfo(playlist)
                if (info != null && info.maxConnections > 0) {
                    // Single-connection account: yield to playback, else the panel kills one stream.
                    return GateConfig(
                        limit = maxOf(1, info.maxConnections - 1),
                        yieldToPlayback = info.maxConnections <= 1,
                    )
                }
            }
        }
        // Unknown provider: be conservative.
        return GateConfig(limit = 1, yieldToPlayback = true)
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val downloadId = inputData.getLong(KEY_DOWNLOAD_ID, -1)
        val item = dao.get(downloadId) ?: return@withContext Result.failure()
        // Paused/cancelled before scheduling.
        if (item.status == DownloadStatus.PAUSED || item.status == DownloadStatus.CANCELLED) {
            return@withContext Result.success()
        }
        val host = item.url.toHttpUrlOrNull()?.host

        try {
            setForeground(foregroundInfo(item.title, 0, 0))
        } catch (_: Exception) {
            // Foreground may be unavailable; keep downloading in the background.
        }

        val gate = resolveGate(host)

        // Without a reserved slot, don't download from a provider the player is streaming from.
        if (gate.yieldToPlayback && host != null && dependencies.activePlaybackHost.value == host) {
            runCatching { setForeground(waitingInfo(item.title)) }
            dependencies.activePlaybackHost.first { it != host }
        }

        try {
            DownloadGate.withSlot(gate.limit) {
                // Providers drop long transfers mid-stream; retry (resuming via Range) while the file grows,
                // giving up only after consecutive zero-progress attempts.
                var stalledAttempts = 0
                while (true) {
                    val existing = DownloadStorage.length(applicationContext, item.filePath)
                    try {
                        transfer(item, existing, gate, host)
                        break
                    } catch (e: Exception) {
                        if (e is CancellationException || e is YieldToPlaybackException ||
                            e is HttpStatusException
                        ) {
                            throw e
                        }
                        val nowBytes = DownloadStorage.length(applicationContext, item.filePath)
                        stalledAttempts = if (nowBytes > existing) 0 else stalledAttempts + 1
                        if (stalledAttempts >= 3) throw e
                        delay(2_000)
                    }
                }
            }
            Result.success()
        } catch (e: YieldToPlaybackException) {
            dao.updateStatusIfStatus(
                downloadId,
                listOf(DownloadStatus.RUNNING),
                DownloadStatus.QUEUED,
            )
            Result.retry()
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                dao.updateStatusIfStatus(
                    downloadId,
                    listOf(DownloadStatus.RUNNING),
                    DownloadStatus.PAUSED,
                )
            }
            throw e
        } catch (e: Exception) {
            handleFailure(item.id, item.title, item.filePath, e)
        }
    }

    /** One HTTP request copying from [existing] to EOF; throws on any break. */
    private suspend fun transfer(
        item: com.buco7854.opentv.core.model.Download,
        existing: Long,
        gate: GateConfig,
        host: String?,
    ) {
        val requestBuilder = Request.Builder()
            .url(item.url)
            .header("User-Agent", dependencies.userAgent())
        if (existing > 0) requestBuilder.header("Range", "bytes=$existing-")

        dependencies.httpClient.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) throw HttpStatusException(response.code)
            val body = response.body ?: throw IOException("Empty body")

            val resuming = response.code == 206
            var downloaded = if (resuming) existing else 0L
            // contentLength() is -1 on chunked responses; 0 = "unknown" in the UI.
            val bodyLength = body.contentLength()
            val total = when {
                bodyLength < 0 -> 0L
                resuming -> existing + bodyLength
                else -> bodyLength
            }

            if (!dao.updateProgressIfStatus(
                    item.id,
                    downloaded,
                    total,
                    listOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING),
                    DownloadStatus.RUNNING,
                )
            ) return

            DownloadStorage.openSink(
                applicationContext,
                item.filePath,
                resumeAt = if (resuming) existing else 0L,
            ).use { sink ->
                val buffer = ByteArray(256 * 1024)
                var lastUpdate = 0L
                body.byteStream().use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        sink.write(buffer, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 750) {
                            lastUpdate = now
                            // Player started streaming from this provider: yield, resume later via Range.
                            if (gate.yieldToPlayback && host != null &&
                                dependencies.activePlaybackHost.value == host
                            ) {
                                throw YieldToPlaybackException()
                            }
                            if (!dao.updateProgressIfStatus(
                                    item.id,
                                    downloaded,
                                    total,
                                    listOf(DownloadStatus.RUNNING),
                                    DownloadStatus.RUNNING,
                                )
                            ) return
                            runCatching {
                                setForeground(foregroundInfo(item.title, downloaded, total))
                            }
                        }
                    }
                }
            }
            dao.updateProgressIfStatus(
                item.id,
                downloaded,
                downloaded,
                listOf(DownloadStatus.RUNNING),
                DownloadStatus.DONE,
            )
        }
    }

    private suspend fun handleFailure(downloadId: Long, title: String, path: String, e: Exception): Result {
        ErrorLog.log("Download: $title", e)
        val code = (e as? HttpStatusException)?.code
        val savedBytes = DownloadStorage.length(applicationContext, path)
        suspend fun markFailed() {
            dao.updateStatusIfStatus(
                downloadId,
                listOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING),
                DownloadStatus.FAILED,
                ErrorLog.describe(e),
            )
        }
        return when {
            // Range beyond EOF: file was already complete (crash between last write and DONE).
            code == 416 && savedBytes > 0 -> {
                dao.updateProgressIfStatus(
                    downloadId,
                    savedBytes,
                    savedBytes,
                    listOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING),
                    DownloadStatus.DONE,
                )
                Result.success()
            }
            // Permanent client errors don't retry; 408/429 are transient.
            code != null && code in 400..499 && code != 408 && code != 429 -> {
                markFailed()
                Result.failure()
            }
            runAttemptCount < 3 -> {
                // Keep QUEUED while WorkManager retries; FAILED is only the final give-up.
                dao.updateStatusIfStatus(
                    downloadId,
                    listOf(DownloadStatus.RUNNING),
                    DownloadStatus.QUEUED,
                )
                Result.retry()
            }
            else -> {
                markFailed()
                Result.failure()
            }
        }
    }

    private class HttpStatusException(val code: Int) : IOException("HTTP $code")

    private fun waitingInfo(title: String): ForegroundInfo {
        val notification: Notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(applicationContext.getString(R.string.downloads_waiting_title, title))
            .setContentText(applicationContext.getString(R.string.downloads_waiting_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(id.hashCode(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id.hashCode(), notification)
        }
    }

    private fun foregroundInfo(title: String, downloaded: Long, total: Long): ForegroundInfo {
        val notification: Notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(applicationContext.getString(R.string.downloads_downloading_title, title))
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
