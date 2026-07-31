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
import com.buco7854.opentv.data.net.executeCancellable
import com.buco7854.opentv.diag.ErrorLog
import com.buco7854.opentv.hub.HubCapacityException
import com.buco7854.opentv.hub.HubException
import com.buco7854.opentv.hub.HubGoneException
import com.buco7854.opentv.hub.HubUnauthorizedException
import com.buco7854.opentv.hub.HubUnreachableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.ResponseBody
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
        private val SATISFIED_RANGE =
            Regex("""bytes (\d+)-(\d+)/(\d+)""", RegexOption.IGNORE_CASE)
        private val UNSATISFIED_RANGE =
            Regex("""bytes \*/(\d+)""", RegexOption.IGNORE_CASE)
        private const val MAX_ERROR_BODY_BYTES = 8 * 1024

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

    private class ServerDownloadException(message: String) : IOException(message)
    private class InvalidRangeException(message: String) : IOException(message)
    private class IncompleteTransferException : IOException()

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

    override suspend fun doWork(): Result {
        val downloadId = inputData.getLong(KEY_DOWNLOAD_ID, -1)
        return dependencies.withDownloadLock(downloadId) { doWorkLocked(downloadId) }
    }

    private suspend fun doWorkLocked(downloadId: Long): Result = withContext(Dispatchers.IO) {
        var item = dao.get(downloadId) ?: return@withContext Result.failure()
        // A replacement may have waited for the old worker to settle this row.
        if (item.status in listOf(
                DownloadStatus.PAUSED,
                DownloadStatus.CANCELLED,
                DownloadStatus.DONE,
                DownloadStatus.FAILED,
                DownloadStatus.PREPARING,
            )
        ) {
            return@withContext Result.success()
        }
        val isHubPull = item.hubSourceId != null && item.serverDownloadId != null
        if (isHubPull && item.status in listOf(
                DownloadStatus.HUB_UNREACHABLE,
                DownloadStatus.HUB_CAPACITY,
                DownloadStatus.HUB_SIGNED_OUT,
                DownloadStatus.HUB_GONE,
            )
        ) {
            item = item.copy(status = DownloadStatus.QUEUED, error = null)
            dao.update(item)
        }
        val host = item.url.toHttpUrlOrNull()?.host

        try {
            // POST_NOTIFICATIONS denial does not prevent foreground-service launch on
            // Android 13+. A real promotion failure must stop the transfer so it does
            // not silently run as ordinary background work under stricter limits.
            setForeground(foregroundInfo(item.title, 0, 0))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return@withContext handleFailure(
                item.id,
                item.title,
                item.filePath,
                ForegroundPromotionException(error),
            )
        }

        val gate = if (isHubPull) null else resolveGate(host)

        // Without a reserved slot, don't download from a provider the player is streaming from.
        if (gate?.yieldToPlayback == true && host != null && dependencies.activePlaybackHost.value == host) {
            updateForegroundSafely(waitingInfo(item.title))
            dependencies.activePlaybackHost.first { it != host }
        }

        try {
            suspend fun pullProvider() {
                // Providers drop long transfers mid-stream; retry (resuming via Range) while the file grows,
                // giving up only after consecutive zero-progress attempts.
                var stalledAttempts = 0
                while (true) {
                    val existing = DownloadStorage.length(applicationContext, item.filePath)
                    try {
                        transfer(item, existing, gate, host)
                        break
                    } catch (e: Exception) {
                        if (e is CancellationException || e is YieldToPlaybackException || e is HttpStatusException) {
                            throw e
                        }
                        if (e is IncompleteTransferException) {
                            stalledAttempts = 0
                            continue
                        }
                        val nowBytes = DownloadStorage.length(applicationContext, item.filePath)
                        stalledAttempts = if (nowBytes > existing) 0 else stalledAttempts + 1
                        if (stalledAttempts >= 3) throw e
                        delay(2_000)
                    }
                }
            }
            if (isHubPull) {
                // The hub already budgets its provider fetch; this device-to-hub pull is unrelated.
                pullHub(item)
            } else {
                dependencies.withDownloadSlot(requireNotNull(gate).limit) { pullProvider() }
            }
            if (isHubPull && dao.get(item.id)?.status == DownloadStatus.DONE) {
                try {
                    dependencies.hubDownloads.localPullCompleted(
                        requireNotNull(item.hubSourceId),
                        requireNotNull(item.serverDownloadId),
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    ErrorLog.log("Remove hub download after local pull", error)
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
                    DownloadStatus.QUEUED,
                )
            }
            throw e
        } catch (e: HubException) {
            handleHubFailure(item.id, e)
        } catch (e: ServerDownloadException) {
            dao.updateStatusIfStatus(
                item.id,
                listOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING),
                DownloadStatus.FAILED,
                e.message,
            )
            Result.failure()
        } catch (e: Exception) {
            handleFailure(item.id, item.title, item.filePath, e)
        }
    }

    /** One HTTP request copying from [existing] to EOF; throws on any break. */
    private suspend fun transfer(
        item: com.buco7854.opentv.core.model.Download,
        existing: Long,
        gate: GateConfig?,
        host: String?,
        completeAtEof: Boolean = true,
    ): Long {
        val requestBuilder = Request.Builder()
            .url(item.url)
            .header("User-Agent", dependencies.userAgent())
        if (existing > 0) requestBuilder.header("Range", "bytes=$existing-")

        return dependencies.httpClient.newCall(requestBuilder.build()).executeCancellable { response ->
            if (!response.isSuccessful) {
                throw HttpStatusException(
                    response.code,
                    errorCode = if (response.code == 410) {
                        parseErrorCode(response.body)
                    } else {
                        null
                    },
                    resourceLength = if (response.code == 416) {
                        UNSATISFIED_RANGE.matchEntire(
                            response.header("Content-Range").orEmpty(),
                        )?.groupValues?.get(1)?.toLongOrNull()
                    } else {
                        null
                    },
                )
            }
            val body = response.body

            val resuming = response.code == 206
            val contentRange = if (resuming) {
                parseContentRange(response.header("Content-Range"), existing)
            } else {
                null
            }
            var downloaded = if (resuming) existing else 0L
            // contentLength() is -1 on chunked responses; 0 = "unknown" in the UI.
            val bodyLength = body.contentLength()
            val expectedResponseBytes = contentRange?.let { it.end - it.start + 1 }
            if (bodyLength >= 0 && expectedResponseBytes != null &&
                bodyLength != expectedResponseBytes
            ) {
                throw InvalidRangeException("HTTP range length does not match Content-Range")
            }
            val total = when {
                !completeAtEof -> item.totalBytes
                contentRange != null -> contentRange.total
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
            ) return@executeCancellable downloaded

            DownloadStorage.openSink(
                applicationContext,
                item.filePath,
                resumeAt = if (resuming) existing else 0L,
            ).use { sink ->
                val buffer = ByteArray(256 * 1024)
                var lastUpdate = 0L
                var responseBytes = 0L
                body.byteStream().use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        if (expectedResponseBytes != null &&
                            responseBytes + read > expectedResponseBytes
                        ) {
                            throw InvalidRangeException(
                                "HTTP range body exceeds Content-Range",
                            )
                        }
                        sink.write(buffer, 0, read)
                        downloaded += read
                        responseBytes += read
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 750) {
                            lastUpdate = now
                            // Player started streaming from this provider: yield, resume later via Range.
                            if (gate?.yieldToPlayback == true && host != null &&
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
                            ) return@executeCancellable downloaded
                            updateForegroundSafely(
                                foregroundInfo(item.title, downloaded, total),
                            )
                        }
                    }
                }
                if (expectedResponseBytes != null && responseBytes != expectedResponseBytes) {
                    throw InvalidRangeException("HTTP range body is shorter than Content-Range")
                }
            }
            if (completeAtEof && downloaded <= 0) {
                throw IOException("Provider returned an empty media file")
            }
            if (completeAtEof && contentRange != null && downloaded < contentRange.total) {
                dao.updateProgressIfStatus(
                    item.id,
                    downloaded,
                    contentRange.total,
                    listOf(DownloadStatus.RUNNING),
                    DownloadStatus.RUNNING,
                )
                throw IncompleteTransferException()
            }
            if (completeAtEof && contentRange != null && downloaded > contentRange.total) {
                throw InvalidRangeException("Downloaded bytes exceed the ranged resource size")
            }
            dao.updateProgressIfStatus(
                item.id,
                downloaded,
                if (completeAtEof) contentRange?.total ?: downloaded else total,
                listOf(DownloadStatus.RUNNING),
                if (completeAtEof) DownloadStatus.DONE else DownloadStatus.RUNNING,
            )
            downloaded
        }
    }

    private suspend fun pullHub(
        initial: com.buco7854.opentv.core.model.Download,
    ) {
        var item = initial
        var tokenRefreshAttempts = 0
        var lastServerBytes = initial.downloadedBytes
        var lastProgressAtMs = dependencies.nowMs()
        while (true) {
            val before = DownloadStorage.length(applicationContext, item.filePath)
            var requestFailure: Exception? = null
            try {
                transfer(item, before, gate = null, host = null, completeAtEof = false)
                tokenRefreshAttempts = 0
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                requestFailure = error
            }

            if ((requestFailure as? HttpStatusException)?.let {
                    it.code == 410 && it.errorCode == "download_access_revoked"
                } == true
            ) {
                throw HubGoneException(
                    "download_access_revoked",
                    "The session that granted file access has ended",
                )
            }
            val localBytes = DownloadStorage.length(applicationContext, item.filePath)
            val state = dependencies.hubDownloads.refreshFile(
                requireNotNull(item.hubSourceId),
                requireNotNull(item.serverDownloadId),
            )
            val current = dao.get(item.id) ?: return
            if (current.status !in listOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING)) return
            if (!dao.updateProgressIfStatus(
                    item.id,
                    localBytes,
                    state.totalBytes,
                    listOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING),
                    DownloadStatus.RUNNING,
                )
            ) return
            item = requireNotNull(dao.get(item.id))
            state.url?.takeIf { it != item.url }?.let { freshUrl ->
                if (!dao.updateUrlIfStatus(
                        item.id,
                        freshUrl,
                        listOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING),
                    )
                ) return
                item = requireNotNull(dao.get(item.id))
            }

            if (state.totalBytes < 0 || state.downloadedBytes < 0 ||
                (state.totalBytes > 0 && state.downloadedBytes > state.totalBytes)
            ) {
                throw ServerDownloadException("The server reported an invalid download size")
            }

            when (state.status) {
                "DONE" -> {
                    if (state.totalBytes <= 0) {
                        throw ServerDownloadException("The server completed an empty download")
                    }
                    if (state.downloadedBytes != state.totalBytes) {
                        throw ServerDownloadException(
                            "The server completed before all declared bytes were downloaded",
                        )
                    }
                    // EOF is only a growing-file snapshot boundary; both server state and size
                    // must agree before a partial movie can become a completed local download.
                    if (localBytes == state.totalBytes &&
                        state.downloadedBytes == state.totalBytes
                    ) {
                        dao.updateProgressIfStatus(
                            item.id,
                            localBytes,
                            state.totalBytes,
                            listOf(DownloadStatus.RUNNING),
                            DownloadStatus.DONE,
                        )
                        return
                    }
                    if (localBytes > state.totalBytes ||
                        state.downloadedBytes > state.totalBytes
                    ) {
                        throw IOException("Server download size changed")
                    }
                }
                "QUEUED", "RUNNING" -> Unit
                "FAILED", "CANCELLED" -> throw ServerDownloadException(
                    state.error ?: "The server download failed",
                )
                "PAUSED" -> throw ServerDownloadException(
                    state.error ?: "The server download was paused",
                )
                else -> throw IOException("Unknown server download status: ${state.status}")
            }

            val httpFailure = requestFailure as? HttpStatusException
            if (requestFailure is InvalidRangeException) throw requestFailure
            if (httpFailure?.code == 401) {
                if (++tokenRefreshAttempts > 3 || state.url == null) throw httpFailure
                continue
            }
            if (httpFailure != null &&
                httpFailure.code in 400..499 &&
                httpFailure.code != 404 &&
                httpFailure.code != 416
            ) {
                throw httpFailure
            }

            val nowMs = dependencies.nowMs()
            val madeProgress = localBytes > before || state.downloadedBytes > lastServerBytes
            if (madeProgress) {
                lastProgressAtMs = nowMs
            }
            lastServerBytes = state.downloadedBytes
            if (nowMs - lastProgressAtMs >= dependencies.hubStallTimeoutMs) {
                throw requestFailure ?: IOException("Server download stopped making progress")
            }
            // Back off when there was nothing new to read -- and always after a failed
            // request. A transfer that keeps failing while the server keeps fetching would
            // otherwise satisfy "progress" on the server's byte count alone and retry with
            // no pause at all, turning a transient 5xx into a tight loop against the server.
            if (requestFailure != null || !madeProgress) {
                dependencies.hubPollDelay(dependencies.hubPollIntervalMs)
            }
        }
    }

    private suspend fun handleHubFailure(downloadId: Long, error: HubException): Result {
        val status = when (error) {
            is HubUnauthorizedException -> DownloadStatus.HUB_SIGNED_OUT
            is HubUnreachableException -> DownloadStatus.HUB_UNREACHABLE
            is HubCapacityException -> DownloadStatus.HUB_CAPACITY
            is HubGoneException -> DownloadStatus.HUB_GONE
            else -> DownloadStatus.FAILED
        }
        dao.updateStatusIfStatus(
            downloadId,
            listOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING),
            status,
            error.message,
        )
        return when (error) {
            is HubUnreachableException -> Result.retry()
            is HubCapacityException -> {
                delay(error.retryAfterMs ?: 10_000)
                Result.retry()
            }
            else -> Result.success()
        }
    }

    private suspend fun handleFailure(downloadId: Long, title: String, path: String, e: Exception): Result {
        ErrorLog.log("Download: $title", e)
        val code = (e as? HttpStatusException)?.code
        val resourceLength = (e as? HttpStatusException)?.resourceLength
        val savedBytes = DownloadStorage.length(applicationContext, path)
        val recordedTotal = dao.get(downloadId)?.totalBytes ?: 0
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
            code == 416 &&
                savedBytes > 0 &&
                resourceLength == savedBytes &&
                (recordedTotal <= 0 || recordedTotal == savedBytes) &&
                itemIsProviderDownload(downloadId) -> {
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

    private suspend fun itemIsProviderDownload(downloadId: Long): Boolean =
        dao.get(downloadId)?.hubSourceId == null

    private suspend fun updateForegroundSafely(info: ForegroundInfo) {
        try {
            setForeground(info)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ErrorLog.log("Update download notification", error)
            // Initial promotion succeeded before any bytes moved. A later content-only
            // notification refresh failure does not demote that foreground worker.
        }
    }

    private class ForegroundPromotionException(cause: Exception) :
        IOException("Could not promote download to foreground execution", cause)

    private class HttpStatusException(
        val code: Int,
        val errorCode: String? = null,
        val resourceLength: Long? = null,
    ) : IOException("HTTP $code")

    private fun parseErrorCode(body: ResponseBody): String? {
        val input = body.byteStream()
        val bytes = ByteArray(MAX_ERROR_BODY_BYTES + 1)
        var size = 0
        while (size < bytes.size) {
            val read = input.read(bytes, size, bytes.size - size)
            if (read < 0) break
            size += read
        }
        if (size > MAX_ERROR_BODY_BYTES) return null
        return try {
            Json.parseToJsonElement(String(bytes, 0, size, Charsets.UTF_8))
                .jsonObject["code"]
                ?.jsonPrimitive
                ?.content
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private data class ContentRange(val start: Long, val end: Long, val total: Long)

    private fun parseContentRange(raw: String?, expectedStart: Long): ContentRange {
        val match = SATISFIED_RANGE.matchEntire(raw.orEmpty())
            ?: throw InvalidRangeException("Missing or invalid Content-Range")
        val start = match.groupValues[1].toLongOrNull()
            ?: throw InvalidRangeException("Invalid range start")
        val end = match.groupValues[2].toLongOrNull()
            ?: throw InvalidRangeException("Invalid range end")
        val total = match.groupValues[3].toLongOrNull()
            ?: throw InvalidRangeException("Unknown ranged resource size")
        if (start != expectedStart || end < start || total <= end) {
            throw InvalidRangeException("Inconsistent Content-Range")
        }
        return ContentRange(start, end, total)
    }

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
