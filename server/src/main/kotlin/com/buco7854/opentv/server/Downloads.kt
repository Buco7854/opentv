package com.buco7854.opentv.server

import com.buco7854.opentv.core.download.DownloadFileName
import com.buco7854.opentv.core.model.Channel
import com.buco7854.opentv.serverdata.DownloadBlobStatus
import com.buco7854.opentv.serverdata.db.ContentIdentityRow
import com.buco7854.opentv.serverdata.db.DownloadBlobRow
import com.buco7854.opentv.serverdata.db.ServerUserDatabase
import com.buco7854.opentv.serverdata.db.UserDownloadRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlin.math.absoluteValue

/** Shared physical transfers with private per-user references. */
class DownloadManager(
    private val db: ServerUserDatabase,
    private val http: ServerHttp,
    private val settings: ServerSettings,
    dataDir: Path,
    private val connections: ProviderConnections,
    private val connectionLimit: suspend (String) -> Int,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val log = LoggerFactory.getLogger("opentv")
    private val dir = dataDir.resolve("user-downloads")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val blobLocks = ConcurrentHashMap<String, Mutex>()
    private val pumpMutex = Mutex()

    fun close() {
        scope.cancel()
        jobs.clear()
    }

    fun start() {
        connections.onSlotFreed { pump() }
        scope.launch {
            db.downloads().blobsByStatus(DownloadBlobStatus.RUNNING).forEach {
                db.downloads().upsertBlob(it.copy(status = DownloadBlobStatus.QUEUED, updatedAtMs = clock()))
            }
            pump()
        }
    }

    suspend fun enqueue(
        userId: String,
        identity: ContentIdentityRow,
        channel: Channel,
    ): Pair<UserDownloadRow, String?> = pumpMutex.withLock {
        var blob = db.downloads().blobForContent(identity.contentId)
        val now = clock()
        if (blob == null) {
            val id = UUID.randomUUID().toString()
            val numeric = id.hashCode().toLong().absoluteValue
            blob = DownloadBlobRow(
                id,
                identity.contentId,
                channel.name,
                channel.url,
                dir.resolve(DownloadFileName.from(channel.name, channel.url, numeric).fileName).toString(),
                DownloadBlobStatus.QUEUED,
                0,
                0,
                null,
                now,
                now,
            )
            db.downloads().upsertBlob(blob)
        }
        val (association, existing) = withBlobLock(blob.id) {
            val prior = db.downloads().forUser(userId).firstOrNull { it.blobId == blob.id }
            val linked = prior?.copy(active = true, suspended = false, updatedAtMs = now)
                ?: UserDownloadRow(UUID.randomUUID().toString(), userId, blob.id, true, false, now, now)
            db.downloads().upsertUserDownload(linked)
            if (blob.status == DownloadBlobStatus.PAUSED || blob.status == DownloadBlobStatus.FAILED) {
                db.downloads().upsertBlob(
                    blob.copy(status = DownloadBlobStatus.QUEUED, error = null, updatedAtMs = now)
                )
            }
            linked to prior
        }
        val waiting = channel.url.startsWith("http") &&
            !connections.downloadFits(providerKeyOf(channel.url), connectionLimit(channel.url))
        pump()
        association to when {
            blob.status == DownloadBlobStatus.DONE -> "Already downloaded"
            existing != null -> "Already in your downloads"
            waiting -> "Queued — it will start when a connection is free"
            else -> null
        }
    }

    suspend fun list(userId: String): List<Pair<UserDownloadRow, DownloadBlobRow>> =
        db.downloads().forUser(userId).mapNotNull { user ->
            db.downloads().blob(user.blobId)?.let { user to it }
        }

    suspend fun adminList(): List<Pair<UserDownloadRow, DownloadBlobRow>> =
        db.downloads().allUserDownloads().mapNotNull { user ->
            db.downloads().blob(user.blobId)?.let { user to it }
        }

    suspend fun get(userId: String, userDownloadId: String): Pair<UserDownloadRow, DownloadBlobRow> {
        val user = owned(userId, userDownloadId)
        val blob = db.downloads().blob(user.blobId) ?: throw ResourceNotFound("download")
        return user to blob
    }

    suspend fun adminCancelBlob(blobId: String): List<String> {
        val affected = db.downloads().forBlob(blobId).map { it.userId }.distinct()
        deleteBlob(blobId)
        return affected
    }

    suspend fun pause(userId: String, userDownloadId: String) {
        val blobId = owned(userId, userDownloadId).blobId
        withBlobLock(blobId) {
            val row = owned(userId, userDownloadId)
            db.downloads().upsertUserDownload(row.copy(active = false, updatedAtMs = clock()))
            if (db.downloads().activeReferenceCount(row.blobId) == 0) {
                db.downloads().blob(row.blobId)?.let {
                    if (it.status == DownloadBlobStatus.QUEUED || it.status == DownloadBlobStatus.RUNNING) {
                        db.downloads().upsertBlob(it.copy(status = DownloadBlobStatus.PAUSED, updatedAtMs = clock()))
                        jobs.remove(it.id)?.cancel()
                    }
                }
            }
        }
    }

    suspend fun resume(userId: String, userDownloadId: String) {
        val blobId = owned(userId, userDownloadId).blobId
        withBlobLock(blobId) {
            val row = owned(userId, userDownloadId)
            db.downloads().upsertUserDownload(
                row.copy(active = true, suspended = false, updatedAtMs = clock())
            )
            db.downloads().blob(row.blobId)?.let {
                if (it.status in setOf(DownloadBlobStatus.PAUSED, DownloadBlobStatus.FAILED, DownloadBlobStatus.CANCELLED)) {
                    db.downloads().upsertBlob(
                        it.copy(status = DownloadBlobStatus.QUEUED, error = null, updatedAtMs = clock())
                    )
                }
            }
        }
        pump()
    }

    suspend fun delete(userId: String, userDownloadId: String) {
        val blobId = owned(userId, userDownloadId).blobId
        withBlobLock(blobId) {
            val row = owned(userId, userDownloadId)
            db.downloads().deleteUserDownload(row.id)
            if (db.downloads().referenceCount(row.blobId) == 0) deleteBlobUnlocked(row.blobId)
            else if (db.downloads().activeReferenceCount(row.blobId) == 0) {
                db.downloads().blob(row.blobId)?.let {
                    db.downloads().upsertBlob(it.copy(status = DownloadBlobStatus.PAUSED, updatedAtMs = clock()))
                    jobs.remove(it.id)?.cancel()
                }
            }
        }
    }

    suspend fun fileFor(userId: String, userDownloadId: String): Pair<DownloadBlobRow, Path>? {
        val row = owned(userId, userDownloadId)
        if (row.suspended) return null
        val blob = db.downloads().blob(row.blobId) ?: return null
        if (blob.status != DownloadBlobStatus.DONE) return null
        val path = safePath(blob.filePath) ?: return null
        return if (Files.exists(path)) blob to path else null
    }

    suspend fun blobFile(blobId: String): Pair<DownloadBlobRow, Path>? {
        val blob = db.downloads().blob(blobId) ?: return null
        if (blob.status != DownloadBlobStatus.DONE) return null
        val path = safePath(blob.filePath) ?: return null
        return if (Files.exists(path)) blob to path else null
    }

    suspend fun deletePlaylist(playlistId: Long) {
        db.downloads().blobsForPlaylist(playlistId).forEach { deleteBlob(it.id) }
    }

    fun scheduleOrphanCleanup() {
        scope.launch {
            db.downloads().orphanBlobs().forEach { deleteBlob(it.id) }
        }
    }

    fun scheduleGrantRevocation(playlistId: Long) {
        scope.launch {
            db.downloads().blobsForPlaylist(playlistId).forEach { blob ->
                withBlobLock(blob.id) {
                    val current = db.downloads().blob(blob.id) ?: return@withBlobLock
                    if (db.downloads().activeReferenceCount(blob.id) == 0 &&
                        current.status in setOf(DownloadBlobStatus.QUEUED, DownloadBlobStatus.RUNNING)
                    ) {
                        db.downloads().upsertBlob(
                            current.copy(status = DownloadBlobStatus.PAUSED, updatedAtMs = clock()),
                        )
                        jobs.remove(blob.id)?.cancel()
                    }
                }
            }
        }
    }

    private suspend fun owned(userId: String, id: String): UserDownloadRow =
        db.downloads().userDownload(id)?.takeIf { it.userId == userId }
            ?: throw ResourceNotFound("download")

    private suspend fun <T> withBlobLock(blobId: String, block: suspend () -> T): T =
        blobLocks.computeIfAbsent(blobId) { Mutex() }.withLock { block() }

    private suspend fun deleteBlob(id: String) {
        withBlobLock(id) { deleteBlobUnlocked(id) }
    }

    private suspend fun deleteBlobUnlocked(id: String) {
        jobs.remove(id)?.cancel()
        db.downloads().blob(id)?.let { safePath(it.filePath)?.let { path -> Files.deleteIfExists(path) } }
        db.downloads().deleteBlob(id)
    }

    private fun safePath(raw: String): Path? {
        if (raw.isBlank()) return null
        val root = dir.toAbsolutePath().normalize()
        return runCatching { Path.of(raw).toAbsolutePath().normalize() }.getOrNull()
            ?.takeIf { it.startsWith(root) }
    }

    private fun pump() {
        scope.launch {
            pumpMutex.withLock {
                var active = jobs.values.count(Job::isActive)
                val limit = settings.downloadLimit.coerceIn(1, 3)
                for (blob in db.downloads().blobsByStatus(DownloadBlobStatus.QUEUED)) {
                    if (active >= limit) break
                    if (jobs[blob.id]?.isActive == true || db.downloads().activeReferenceCount(blob.id) == 0) continue
                    jobs[blob.id] = scope.launch { run(blob.id) }
                    active++
                }
            }
        }
    }

    private class HttpStatusException(code: Int) : IOException("HTTP $code")

    private suspend fun run(id: String) {
        val ownerJob = requireNotNull(coroutineContext[Job])
        val blob = db.downloads().blob(id) ?: return
        if (blob.status != DownloadBlobStatus.QUEUED) return
        val slot = "dl:$id"
        val evict = {
            scope.launch {
                db.downloads().updateBlobStatus(
                    id, DownloadBlobStatus.QUEUED, null, clock(), listOf(DownloadBlobStatus.RUNNING),
                )
            }
            if (jobs.remove(id, ownerJob)) ownerJob.cancel()
            Unit
        }
        if (!connections.tryOpenDownload(
                slot, providerKeyOf(blob.sourceUrl), blob.contentId,
                connectionLimit(blob.sourceUrl), evict,
            )
        ) {
            jobs.remove(id, ownerJob)
            return
        }
        try {
            if (db.downloads().updateBlobStatus(
                    id,
                    DownloadBlobStatus.RUNNING,
                    null,
                    clock(),
                    listOf(DownloadBlobStatus.QUEUED),
                ) != 1
            ) return
            val target = Path.of(blob.filePath)
            Files.createDirectories(target.parent)
            var stalled = 0
            while (true) {
                val before = if (Files.exists(target)) Files.size(target) else 0
                try {
                    transfer(id, blob.sourceUrl, target, before)
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: HttpStatusException) {
                    throw e
                } catch (e: Exception) {
                    val after = if (Files.exists(target)) Files.size(target) else 0
                    stalled = if (after > before) 0 else stalled + 1
                    if (stalled >= 3) throw e
                    kotlinx.coroutines.delay(2_000)
                }
            }
        } catch (_: CancellationException) {
        } catch (error: Exception) {
            log.warn("Download failed ({}): {}", blob.title, error.message)
            db.downloads().updateBlobStatus(
                id,
                DownloadBlobStatus.FAILED,
                (error.message ?: error::class.simpleName)?.take(200),
                clock(),
                listOf(DownloadBlobStatus.RUNNING),
            )
        } finally {
            connections.close(slot)
            jobs.remove(id, ownerJob)
            pump()
        }
    }

    private suspend fun transfer(id: String, url: String, target: Path, existing: Long) {
        var from = existing
        val request = HttpRequest.newBuilder(URI(url))
            .timeout(Duration.ofHours(6))
            .header("User-Agent", http.userAgent)
            .apply { if (from > 0) header("Range", "bytes=$from-") }
            .build()
        val response = http.client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() !in 200..299) {
            response.body().close()
            throw HttpStatusException(response.statusCode())
        }
        val resumed = response.statusCode() == 206
        if (!resumed) from = 0
        val length = response.headers().firstValue("Content-Length").orElse(null)?.toLongOrNull()
        val total = if (resumed) response.headers().firstValue("Content-Range").orElse(null)
            ?.substringAfter('/')?.toLongOrNull() ?: length?.plus(from) ?: 0 else length ?: 0
        var downloaded = from
        updateProgress(id, downloaded, total, DownloadBlobStatus.RUNNING)
        response.body().use { input ->
            FileOutputStream(target.toFile(), resumed).use { out ->
                val buffer = ByteArray(256 * 1024)
                var lastWrite = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    out.write(buffer, 0, count)
                    downloaded += count
                    val now = clock()
                    if (now - lastWrite > 500) {
                        lastWrite = now
                        connections.touch("dl:$id")
                        updateProgress(id, downloaded, total, DownloadBlobStatus.RUNNING)
                    }
                }
            }
        }
        updateProgress(id, downloaded, total.takeIf { it > 0 } ?: downloaded, DownloadBlobStatus.DONE)
    }

    private suspend fun updateProgress(id: String, downloaded: Long, total: Long, status: String) {
        if (db.downloads().updateBlobProgress(
                id,
                downloaded,
                total,
                status,
                clock(),
                listOf(DownloadBlobStatus.QUEUED, DownloadBlobStatus.RUNNING),
            ) != 1
        ) throw CancellationException("Download is no longer running")
    }
}
