package com.buco7854.opentv.server

import com.buco7854.opentv.core.model.Channel
import com.buco7854.opentv.core.model.Download
import com.buco7854.opentv.core.model.DownloadStatus
import com.buco7854.opentv.core.storage.Storage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/**
 * Server-side VOD download queue. Pause keeps the partial file; resume continues
 * from the same byte via Range. Concurrency limited to respect provider caps.
 */
class DownloadManager(
    private val storage: Storage,
    private val http: ServerHttp,
    private val settings: ServerSettings,
    dataDir: Path,
    private val connections: ProviderConnections,
    // How many concurrent reads the provider behind a URL permits (its max_connections).
    private val connectionLimit: suspend (String) -> Int,
) {
    private val log = LoggerFactory.getLogger("opentv")
    private val dir: Path = dataDir.resolve("downloads")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val pumpMutex = Mutex()

    /** Re-queue transfers interrupted by a restart and resume the queue. */
    fun start() {
        // A freed provider slot (a stream ending, or another download finishing) may let a
        // waiting download in.
        connections.onSlotFreed { pump() }
        scope.launch {
            for (item in storage.downloads.getByStatus(DownloadStatus.RUNNING)) {
                storage.downloads.update(item.copy(status = DownloadStatus.QUEUED))
            }
            pump()
        }
    }

    private fun targetPath(channel: Channel, downloadId: Long): String {
        // Extension from the last path segment only, so "host/vod/123" yields no slashes.
        val lastSegment = channel.url.substringBefore('?').substringBefore('#').substringAfterLast('/')
        val extension = lastSegment.substringAfterLast('.', "")
            .filter { it.isLetterOrDigit() }.take(5).ifEmpty { "mp4" }
        val safeName = channel.name.map { if (it.isLetterOrDigit() || it in " ._-()[]") it else '_' }
            .joinToString("").trim().take(120).ifEmpty { "video" }
        // Row id keeps identically-named VODs in separate files.
        return dir.resolve("$safeName-$downloadId.$extension").toString()
    }

    /**
     * Queue a VOD download. Returns null when queued, or a user-facing reason
     * when the same URL is already queued, running, or finished.
     */
    suspend fun enqueue(channel: Channel): String? {
        val existing = storage.downloads.findByUrlWithStatus(
            channel.url,
            listOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING, DownloadStatus.DONE, DownloadStatus.PAUSED),
        )
        if (existing != null) {
            return when (existing.status) {
                DownloadStatus.DONE -> "Already downloaded"
                DownloadStatus.PAUSED -> "Paused. Resume it from Downloads"
                else -> "Already downloading"
            }
        }
        val id = storage.downloads.insert(Download(title = channel.name, url = channel.url, filePath = ""))
        storage.downloads.get(id)?.let {
            storage.downloads.update(it.copy(filePath = targetPath(channel, id)))
        }
        pump()
        return null
    }

    /** Write PAUSED before cancelling so the worker doesn't mark the row FAILED. */
    suspend fun pause(id: Long) {
        val item = storage.downloads.get(id) ?: return
        if (item.status == DownloadStatus.QUEUED || item.status == DownloadStatus.RUNNING) {
            storage.downloads.update(item.copy(status = DownloadStatus.PAUSED))
            jobs.remove(id)?.cancel()
        }
    }

    suspend fun resume(id: Long) = retry(id)

    suspend fun retry(id: Long) {
        val item = storage.downloads.get(id) ?: return
        storage.downloads.update(item.copy(status = DownloadStatus.QUEUED, error = null))
        pump()
    }

    suspend fun cancel(id: Long) {
        val item = storage.downloads.get(id) ?: return
        storage.downloads.update(item.copy(status = DownloadStatus.CANCELLED))
        jobs.remove(id)?.cancel()
    }

    suspend fun delete(id: Long) {
        jobs.remove(id)?.cancel()
        storage.downloads.get(id)?.let { item ->
            if (item.filePath.isNotEmpty()) runCatching { Files.deleteIfExists(Path.of(item.filePath)) }
        }
        storage.downloads.delete(id)
    }

    /** The finished file for serving/playback, or null when not ready. */
    suspend fun fileFor(id: Long): Pair<Download, Path>? {
        val item = storage.downloads.get(id) ?: return null
        if (item.status != DownloadStatus.DONE || item.filePath.isEmpty()) return null
        val path = Path.of(item.filePath)
        return if (Files.exists(path)) item to path else null
    }

    private fun pump() {
        scope.launch {
            pumpMutex.withLock {
                val limit = settings.downloadLimit.coerceIn(1, 3)
                var active = jobs.values.count { it.isActive }
                if (active >= limit) return@withLock
                for (item in storage.downloads.getByStatus(DownloadStatus.QUEUED).sortedBy { it.id }) {
                    if (active >= limit) break
                    if (jobs[item.id]?.isActive == true) continue
                    jobs[item.id] = scope.launch { run(item.id) }
                    active++
                }
            }
        }
    }

    /** The provider answered with a non-2xx status: not worth retrying. */
    private class HttpStatusException(code: Int) : IOException("HTTP $code")

    private suspend fun run(id: Long) {
        val item = storage.downloads.get(id) ?: return
        if (item.status != DownloadStatus.QUEUED || item.filePath.isEmpty()) return
        // Take a provider connection within its cap (shared with playback). If the provider
        // is full, stay queued - a freed slot pumps the queue again. A stream that needs the
        // slot evicts this download back to queued (see onSlotFreed).
        val slot = "dl:$id"
        val evict = { requeueIfRunning(id); jobs[id]?.cancel(); Unit }
        if (!connections.tryOpenDownload(slot, providerKeyOf(item.url), connectionLimit(item.url), evict)) {
            jobs.remove(id)
            return
        }
        storage.downloads.update(item.copy(status = DownloadStatus.RUNNING, error = null))
        val target = Path.of(item.filePath)
        try {
            Files.createDirectories(target.parent)
            // Providers drop long transfers; retry (Range-resumed) while the file grows,
            // giving up only after consecutive zero-progress attempts or an HTTP error.
            var stalledAttempts = 0
            while (true) {
                val before = if (Files.exists(target)) Files.size(target) else 0L
                try {
                    transfer(id, item.url, target, before)
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: HttpStatusException) {
                    throw e
                } catch (e: Exception) {
                    val after = if (Files.exists(target)) Files.size(target) else 0L
                    stalledAttempts = if (after > before) 0 else stalledAttempts + 1
                    if (stalledAttempts >= 3) throw e
                    kotlinx.coroutines.delay(2_000)
                }
            }
            log.info("Download finished: {}", item.title)
        } catch (_: CancellationException) {
            // Paused or deleted: the row's status was already set by the caller.
        } catch (e: Exception) {
            log.warn("Download failed ({}): {}", item.title, e.message)
            val current = storage.downloads.get(id)
            if (current?.status == DownloadStatus.RUNNING) {
                storage.downloads.update(current.copy(status = DownloadStatus.FAILED, error = (e.message ?: e::class.simpleName)?.take(200)))
            }
        } finally {
            connections.close(slot)
            jobs.remove(id)
            pump()
        }
    }

    /** An evicting stream bumps a running download back to the queue to await a free slot. */
    private fun requeueIfRunning(id: Long) {
        scope.launch {
            storage.downloads.get(id)?.let {
                if (it.status == DownloadStatus.RUNNING) storage.downloads.update(it.copy(status = DownloadStatus.QUEUED))
            }
        }
    }

    /** One HTTP request copying from [existing] to EOF; throws on any break. */
    private suspend fun transfer(id: Long, url: String, target: Path, existing: Long) {
        var from = existing
        val builder = HttpRequest.newBuilder(URI(url))
            .timeout(Duration.ofHours(6))
            .header("User-Agent", http.userAgent)
        if (from > 0) builder.header("Range", "bytes=$from-")

        val response = http.client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
        val code = response.statusCode()
        if (code !in 200..299) {
            response.body().close()
            throw HttpStatusException(code)
        }
        val resumed = code == 206
        if (!resumed) from = 0 // server ignored the Range: start over
        val contentLength = response.headers().firstValue("Content-Length").orElse(null)?.toLongOrNull()
        val total = if (resumed) {
            response.headers().firstValue("Content-Range").orElse(null)
                ?.substringAfter('/')?.toLongOrNull()
                ?: contentLength?.plus(from) ?: 0
        } else contentLength ?: 0

        var downloaded = from
        storage.downloads.updateProgress(id, downloaded, total, DownloadStatus.RUNNING)
        response.body().use { input ->
            FileOutputStream(target.toFile(), resumed).use { out ->
                val buffer = ByteArray(256 * 1024)
                var lastWrite = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    downloaded += n
                    val now = System.currentTimeMillis()
                    if (now - lastWrite > 500) {
                        lastWrite = now
                        connections.touch("dl:$id")
                        storage.downloads.updateProgress(id, downloaded, total, DownloadStatus.RUNNING)
                    }
                }
            }
        }
        storage.downloads.updateProgress(id, downloaded, if (total > 0) total else downloaded, DownloadStatus.DONE)
    }
}
