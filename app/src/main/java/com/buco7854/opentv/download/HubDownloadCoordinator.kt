package com.buco7854.opentv.download

import android.content.SharedPreferences
import com.buco7854.opentv.contract.DownloadDto
import com.buco7854.opentv.core.model.Download
import com.buco7854.opentv.core.model.DownloadStatus
import com.buco7854.opentv.core.storage.DownloadStore
import com.buco7854.opentv.diag.ErrorLog
import com.buco7854.opentv.hub.HubCapacityException
import com.buco7854.opentv.hub.HubEndpoints
import com.buco7854.opentv.hub.HubException
import com.buco7854.opentv.hub.HubGoneException
import com.buco7854.opentv.hub.HubNotFoundException
import com.buco7854.opentv.hub.HubRegistry
import com.buco7854.opentv.hub.HubUnauthorizedException
import com.buco7854.opentv.hub.HubUnreachableException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class HubDownloadPreferences(
    private val preferences: SharedPreferences,
) {
    @Synchronized
    fun removeFromServerAfterDownload(hubSourceId: Long): Boolean =
        preferences.getBoolean(key(hubSourceId), false)

    @Synchronized
    fun setRemoveFromServerAfterDownload(hubSourceId: Long, remove: Boolean) {
        preferences.edit().putBoolean(key(hubSourceId), remove).apply()
    }

    @Synchronized
    fun pruneMissingHubs(existingHubIds: Set<Long>) {
        val staleKeys = preferences.all.keys.filter { storedKey ->
            hubId(storedKey)?.let { it !in existingHubIds } == true
        }
        if (staleKeys.isNotEmpty()) {
            preferences.edit().apply {
                staleKeys.forEach(::remove)
            }.apply()
        }
    }

    @Synchronized
    internal fun enqueueServerDelete(hubSourceId: Long, serverDownloadId: String) {
        preferences.edit()
            .putBoolean(pendingDeleteKey(hubSourceId, serverDownloadId), true)
            .commit()
    }

    @Synchronized
    internal fun pendingServerDeletes(): List<PendingHubDownloadDelete> =
        preferences.all.keys.mapNotNull(::pendingDelete)

    @Synchronized
    internal fun completeServerDelete(delete: PendingHubDownloadDelete) {
        preferences.edit()
            .remove(pendingDeleteKey(delete.hubSourceId, delete.serverDownloadId))
            .apply()
    }

    private fun key(hubSourceId: Long) = "remove-after-download-$hubSourceId"

    private fun pendingDeleteKey(hubSourceId: Long, serverDownloadId: String) =
        "$PENDING_DELETE_PREFIX$hubSourceId-$serverDownloadId"

    private fun hubId(storedKey: String): Long? = when {
        storedKey.startsWith(KEY_PREFIX) ->
            storedKey.removePrefix(KEY_PREFIX).toLongOrNull()
        storedKey.startsWith(PENDING_DELETE_PREFIX) ->
            storedKey.removePrefix(PENDING_DELETE_PREFIX).substringBefore('-').toLongOrNull()
        else -> null
    }

    private fun pendingDelete(storedKey: String): PendingHubDownloadDelete? {
        if (!storedKey.startsWith(PENDING_DELETE_PREFIX)) return null
        val value = storedKey.removePrefix(PENDING_DELETE_PREFIX)
        val separator = value.indexOf('-')
        if (separator <= 0 || separator == value.lastIndex) return null
        val hubSourceId = value.substring(0, separator).toLongOrNull() ?: return null
        return PendingHubDownloadDelete(hubSourceId, value.substring(separator + 1))
    }

    companion object {
        const val PREFS_NAME = "hub_downloads"
        private const val KEY_PREFIX = "remove-after-download-"
        private const val PENDING_DELETE_PREFIX = "pending-server-delete-"
    }
}

internal data class PendingHubDownloadDelete(
    val hubSourceId: Long,
    val serverDownloadId: String,
)

internal data class HubDownloadSnapshot(
    val baseUrl: String,
    val downloads: List<DownloadDto>,
)

internal interface HubDownloadRemote {
    suspend fun downloads(hubSourceId: Long): HubDownloadSnapshot
    suspend fun enqueue(hubSourceId: Long, contentId: String)
    suspend fun action(hubSourceId: Long, serverDownloadId: String, action: String)
    suspend fun delete(hubSourceId: Long, serverDownloadId: String)
}

internal class RegistryHubDownloadRemote(
    private val hubs: HubRegistry,
) : HubDownloadRemote {
    private suspend fun client(hubSourceId: Long) =
        hubs.clientFor(hubSourceId)
            ?: throw HubGoneException("hub_missing", "Hub connection is no longer available")

    override suspend fun downloads(hubSourceId: Long): HubDownloadSnapshot {
        val client = client(hubSourceId)
        return HubDownloadSnapshot(
            baseUrl = client.baseUrl,
            downloads = client.call { downloads(it) },
        )
    }

    override suspend fun enqueue(hubSourceId: Long, contentId: String) {
        client(hubSourceId).call { enqueueDownload(it, contentId) }
    }

    override suspend fun action(hubSourceId: Long, serverDownloadId: String, action: String) {
        client(hubSourceId).call { downloadAction(it, serverDownloadId, action) }
    }

    override suspend fun delete(hubSourceId: Long, serverDownloadId: String) {
        client(hubSourceId).call { deleteDownload(it, serverDownloadId) }
    }
}

sealed interface HubPreparationResult {
    data object Preparing : HubPreparationResult
    data object HandedOff : HubPreparationResult
    data object Complete : HubPreparationResult
    data class RetryAfter(val delayMs: Long) : HubPreparationResult
    data object Blocked : HubPreparationResult
}

interface HubDownloadWorkerAccess {
    suspend fun prepare(downloadId: Long): HubPreparationResult
    suspend fun refreshFile(
        hubSourceId: Long,
        serverDownloadId: String,
    ): HubDownloadFileState
    suspend fun localPullCompleted(hubSourceId: Long, serverDownloadId: String)
}

data class HubDownloadFileState(
    val url: String?,
    val status: String,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val error: String?,
)

/**
 * Owns the server side of a hub download and hands the same local row to [DownloadWorker]
 * as soon as the growing blob has bytes the device can pull.
 */
class HubDownloadCoordinator internal constructor(
    private val store: DownloadStore,
    private val remote: HubDownloadRemote,
    private val scheduler: DownloadScheduler,
    private val preferences: HubDownloadPreferences,
    private val scope: CoroutineScope,
    private val pollIntervalMs: Long = 2_000,
) : HubDownloadWorkerAccess {
    constructor(
        store: DownloadStore,
        hubs: HubRegistry,
        scheduler: DownloadScheduler,
        preferences: HubDownloadPreferences,
        scope: CoroutineScope,
    ) : this(store, RegistryHubDownloadRemote(hubs), scheduler, preferences, scope)

    private val rowLocks = KeyedMutexPool<Long>()
    private val enqueueLocks = KeyedMutexPool<String>()
    private val serverDeleteLock = Mutex()
    private var foregroundJob: Job? = null

    suspend fun enqueue(
        hubSourceId: Long,
        contentId: String,
        title: String,
        targetPath: suspend (Long) -> String,
    ): String? = enqueueLocks.withKeyLock(enqueueKey(hubSourceId, contentId)) {
        val existing = store.findByHubContentWithStatus(
            hubSourceId,
            contentId,
            ACTIVE_STATUSES,
        )
        if (existing != null) return@withKeyLock duplicate(existing)

        val id = store.insert(
            Download(
                title = title,
                url = "",
                filePath = "",
                status = DownloadStatus.PREPARING,
                hubSourceId = hubSourceId,
                contentId = contentId,
            ),
        )
        try {
            val row = checkNotNull(store.get(id))
            store.update(row.copy(filePath = targetPath(id)))
        } catch (error: Exception) {
            withContext(NonCancellable) {
                store.delete(id)
            }
            throw error
        }
        scheduler.enqueuePreparation(id)
        prepare(id)
        null
    }

    fun setForeground(foreground: Boolean) {
        foregroundJob?.cancel()
        foregroundJob = null
        if (!foreground) return
        foregroundJob = scope.launch {
            while (isActive) {
                val pending = store.getByStatuses(FOREGROUND_POLL_STATUSES)
                var nextPoll = pollIntervalMs
                if (!retryPendingServerDeletes()) {
                    nextPoll = maxOf(nextPoll, DEFAULT_RETRY_MS)
                }
                pending.forEach { row ->
                    when (val result = prepare(row.id)) {
                        is HubPreparationResult.RetryAfter ->
                            nextPoll = maxOf(nextPoll, result.delayMs)
                        else -> Unit
                    }
                }
                delay(nextPoll)
            }
        }
    }

    override suspend fun prepare(downloadId: Long): HubPreparationResult =
        rowLocks.withKeyLock(downloadId) {
            val row = store.get(downloadId) ?: return@withKeyLock HubPreparationResult.Complete
            if (row.hubSourceId == null || row.contentId == null ||
                row.status !in PREPARATION_STATUSES
            ) {
                return@withKeyLock HubPreparationResult.Complete
            }
            val hubSourceId = requireNotNull(row.hubSourceId)
            val contentId = requireNotNull(row.contentId)
            try {
                var snapshot = remote.downloads(hubSourceId)
                var server = snapshot.find(row)
                if (server == null && row.serverDownloadId == null) {
                    remote.enqueue(hubSourceId, contentId)
                    snapshot = remote.downloads(hubSourceId)
                    server = snapshot.find(row)
                }
                if (server != null &&
                    row.serverDownloadId == null &&
                    server.status in listOf("FAILED", "CANCELLED")
                ) {
                    remote.action(hubSourceId, server.id, "retry")
                    snapshot = remote.downloads(hubSourceId)
                    server = snapshot.find(row)
                }
                if (server == null) {
                    throw HubGoneException("download_missing", "The server download no longer exists")
                }
                sync(row, snapshot.baseUrl, server)
            } catch (error: HubException) {
                classifyFailure(row, error)
            }
        }

    suspend fun retryPreparation(row: Download) {
        var prepareAfterUnlock = false
        rowLocks.withKeyLock(row.id) {
            var current = store.get(row.id) ?: return@withKeyLock
            val hubSourceId = current.hubSourceId ?: return@withKeyLock
            if (current.status == DownloadStatus.HUB_GONE) {
                current = current.copy(
                    url = "",
                    status = DownloadStatus.PREPARING,
                    totalBytes = 0,
                    downloadedBytes = 0,
                    error = null,
                    serverDownloadId = null,
                )
                store.update(current)
                scheduler.enqueuePreparation(current.id)
                prepareAfterUnlock = true
                return@withKeyLock
            }
            val action = when (current.status) {
                DownloadStatus.PAUSED -> "resume"
                DownloadStatus.FAILED -> "retry"
                else -> null
            }
            val serverDownloadId = current.serverDownloadId
            if (action != null && serverDownloadId != null) {
                try {
                    remote.action(hubSourceId, serverDownloadId, action)
                } catch (error: HubException) {
                    classifyFailure(current, error)
                    return@withKeyLock
                }
            }
            if (current.url.isNotEmpty()) {
                store.update(current.copy(status = DownloadStatus.QUEUED, error = null))
                scheduler.enqueue(current.id)
                return@withKeyLock
            }
            store.update(current.copy(status = DownloadStatus.PREPARING, error = null))
            scheduler.enqueuePreparation(current.id)
            prepareAfterUnlock = true
        }
        if (prepareAfterUnlock) prepare(row.id)
    }

    suspend fun pausePreparation(row: Download) =
        rowLocks.withKeyLock(row.id) {
            val current = store.get(row.id) ?: return@withKeyLock
            store.update(current.copy(status = DownloadStatus.PAUSED))
            scheduler.cancel(current.id)
            val hubSourceId = current.hubSourceId ?: return@withKeyLock
            current.serverDownloadId?.let {
                try {
                    remote.action(hubSourceId, it, "pause")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    Unit
                }
            }
        }

    override suspend fun refreshFile(
        hubSourceId: Long,
        serverDownloadId: String,
    ): HubDownloadFileState {
        val snapshot = remote.downloads(hubSourceId)
        val server = snapshot.downloads.firstOrNull { it.id == serverDownloadId }
            ?: throw HubGoneException("download_missing", "The server download no longer exists")
        return HubDownloadFileState(
            url = server.fileToken?.let {
                HubEndpoints.downloadFile(snapshot.baseUrl, server.id, it)
            },
            status = server.status,
            totalBytes = server.totalBytes,
            downloadedBytes = server.downloadedBytes,
            error = server.error,
        )
    }

    override suspend fun localPullCompleted(hubSourceId: Long, serverDownloadId: String) {
        if (preferences.removeFromServerAfterDownload(hubSourceId)) {
            preferences.enqueueServerDelete(hubSourceId, serverDownloadId)
            retryPendingServerDeletes()
        }
    }

    fun localDownloadDeleted(hubSourceId: Long, serverDownloadId: String) {
        preferences.enqueueServerDelete(hubSourceId, serverDownloadId)
        scope.launch { retryPendingServerDeletes() }
    }

    internal suspend fun retryPendingServerDeletes(): Boolean = serverDeleteLock.withLock {
        preferences.pendingServerDeletes().forEach { pending ->
            try {
                remote.delete(pending.hubSourceId, pending.serverDownloadId)
                preferences.completeServerDelete(pending)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: HubNotFoundException) {
                // The association was already removed, including by remove-after-download.
                preferences.completeServerDelete(pending)
            } catch (error: Throwable) {
                ErrorLog.log("Remove hub download from server", error)
            }
        }
        preferences.pendingServerDeletes().isEmpty()
    }

    private suspend fun sync(
        row: Download,
        baseUrl: String,
        server: DownloadDto,
    ): HubPreparationResult {
        val current = store.get(row.id) ?: return HubPreparationResult.Complete
        return when (server.status) {
            "DONE" -> {
                val token = server.fileToken
                    ?: throw HubGoneException("download_token_missing", "The completed download has no file access")
                store.update(
                    current.copy(
                        url = HubEndpoints.downloadFile(baseUrl, server.id, token),
                        status = DownloadStatus.QUEUED,
                        totalBytes = server.totalBytes,
                        downloadedBytes = current.downloadedBytes,
                        error = null,
                        serverDownloadId = server.id,
                    ),
                )
                scheduler.enqueue(row.id)
                HubPreparationResult.HandedOff
            }
            "RUNNING" -> {
                val token = server.fileToken
                if (server.downloadedBytes > 0 && token != null) {
                    store.update(
                        current.copy(
                            url = HubEndpoints.downloadFile(baseUrl, server.id, token),
                            status = DownloadStatus.QUEUED,
                            totalBytes = server.totalBytes,
                            error = null,
                            serverDownloadId = server.id,
                        ),
                    )
                    scheduler.enqueue(row.id)
                    HubPreparationResult.HandedOff
                } else {
                    syncPreparing(current, server)
                    HubPreparationResult.Preparing
                }
            }
            "QUEUED" -> {
                syncPreparing(current, server)
                HubPreparationResult.Preparing
            }
            "PAUSED" -> {
                store.update(
                    current.copy(
                        status = DownloadStatus.PAUSED,
                        totalBytes = server.totalBytes,
                        error = null,
                        serverDownloadId = server.id,
                    ),
                )
                HubPreparationResult.Blocked
            }
            else -> {
                store.update(
                    current.copy(
                        status = DownloadStatus.FAILED,
                        totalBytes = server.totalBytes,
                        error = server.error,
                        serverDownloadId = server.id,
                    ),
                )
                HubPreparationResult.Blocked
            }
        }
    }

    private suspend fun syncPreparing(current: Download, server: DownloadDto) {
        store.update(
            current.copy(
                status = DownloadStatus.PREPARING,
                totalBytes = server.totalBytes,
                error = null,
                serverDownloadId = server.id,
            ),
        )
    }

    private suspend fun classifyFailure(
        row: Download,
        error: HubException,
    ): HubPreparationResult {
        val current = store.get(row.id) ?: return HubPreparationResult.Complete
        val status = when (error) {
            is HubUnauthorizedException -> DownloadStatus.HUB_SIGNED_OUT
            is HubUnreachableException -> DownloadStatus.HUB_UNREACHABLE
            is HubCapacityException -> DownloadStatus.HUB_CAPACITY
            is HubGoneException -> DownloadStatus.HUB_GONE
            else -> DownloadStatus.FAILED
        }
        store.update(current.copy(status = status, error = error.message))
        return when (error) {
            is HubUnreachableException -> HubPreparationResult.RetryAfter(DEFAULT_RETRY_MS)
            is HubCapacityException ->
                HubPreparationResult.RetryAfter(error.retryAfterMs ?: DEFAULT_RETRY_MS)
            else -> HubPreparationResult.Blocked
        }
    }

    private fun HubDownloadSnapshot.find(row: Download): DownloadDto? =
        row.serverDownloadId?.let { id -> downloads.firstOrNull { it.id == id } }
            ?: downloads.firstOrNull { it.contentId == row.contentId }

    private fun duplicate(existing: Download): String = when (existing.status) {
        DownloadStatus.DONE -> "downloaded"
        DownloadStatus.PAUSED -> "paused"
        else -> "downloading"
    }

    private fun enqueueKey(hubSourceId: Long, contentId: String) =
        "$hubSourceId\u0000$contentId"

    private companion object {
        const val DEFAULT_RETRY_MS = 10_000L
        val ACTIVE_STATUSES = listOf(
            DownloadStatus.QUEUED,
            DownloadStatus.RUNNING,
            DownloadStatus.DONE,
            DownloadStatus.PAUSED,
            DownloadStatus.PREPARING,
            DownloadStatus.HUB_SIGNED_OUT,
            DownloadStatus.HUB_UNREACHABLE,
            DownloadStatus.HUB_CAPACITY,
            DownloadStatus.HUB_GONE,
        )
        val PREPARATION_STATUSES = listOf(
            DownloadStatus.PREPARING,
            DownloadStatus.HUB_UNREACHABLE,
            DownloadStatus.HUB_CAPACITY,
        )
        val FOREGROUND_POLL_STATUSES = PREPARATION_STATUSES
    }
}

/**
 * A keyed lock whose entry lives exactly as long as an owner or waiter.
 *
 * Waiters increment [Entry.users] before suspending on the mutex, so the
 * handoff cannot remove the entry and let a third caller create a second lock.
 */
internal class KeyedMutexPool<K> {
    private class Entry(
        val mutex: Mutex = Mutex(),
        var users: Int = 0,
    )

    private val guard = Any()
    private val entries = mutableMapOf<K, Entry>()

    internal val retainedKeyCount: Int
        get() = synchronized(guard) { entries.size }

    suspend fun <T> withKeyLock(key: K, block: suspend () -> T): T {
        val entry = synchronized(guard) {
            entries.getOrPut(key) { Entry() }.also { it.users++ }
        }
        try {
            return entry.mutex.withLock { block() }
        } finally {
            synchronized(guard) {
                check(entry.users > 0)
                entry.users--
                if (entry.users == 0 && entries[key] === entry) {
                    entries.remove(key)
                }
            }
        }
    }
}
