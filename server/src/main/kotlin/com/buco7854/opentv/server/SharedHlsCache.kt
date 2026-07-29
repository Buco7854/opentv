package com.buco7854.opentv.server

import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Raw, untouched HLS resources shared by one watch-together room.
 *
 * Playlist bytes stay raw in this cache: [StreamProxy.rewriteHls] is still the sole rewriter and
 * runs for each viewer, minting lease-specific child capabilities. Media/key bytes are returned
 * exactly as received. A resource is single-flighted by room, upstream URL and Range request.
 */
internal class SharedHlsCache(
    private val http: ServerHttp,
    private val gate: StreamGate,
    private val connectionLimit: suspend (String) -> Int,
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private data class Key(
        val group: String,
        val url: String,
        val range: String?,
    )

    private data class Cached(
        val response: SharedHlsResponse,
        val cachedAtMs: Long,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loads = ConcurrentHashMap<Key, Deferred<SharedHlsResponse>>()
    private val lock = Any()
    private val cache = LinkedHashMap<Key, Cached>(16, 0.75f, true)
    private val activeGroups = HashSet<String>()
    private val groupLastAccess = HashMap<String, Long>()
    private val bodies = HashMap<String, MutableSet<InputStream>>()
    private var cacheBytes = 0L

    private val reaper = scope.launch {
        while (isActive) {
            delay(REAP_INTERVAL_MS)
            val cutoff = clock() - IDLE_EVICT_MS
            val idle = synchronized(lock) {
                groupLastAccess
                    .filter { (group, lastAccess) ->
                        lastAccess < cutoff &&
                            bodies[group].isNullOrEmpty() &&
                            loads.keys.none { it.group == group }
                    }
                    .keys
                    .toList()
            }
            idle.forEach(::drop)
        }
    }

    suspend fun read(
        group: String,
        uri: URI,
        range: String?,
        groupStillActive: () -> Boolean,
    ): SharedHlsResponse {
        require(group.isNotBlank()) { "Shared HLS group is required" }
        ensureActiveGroup(group, groupStillActive)
        val key = Key(group, uri.toString(), range)
        val now = clock()
        synchronized(lock) {
            groupLastAccess[group] = now
            cache[key]?.let { entry ->
                if (!entry.response.playlist || now - entry.cachedAtMs < PLAYLIST_TTL_MS) {
                    gate.touch(group)
                    return entry.response
                }
                removeLocked(key)
            }
        }

        val candidate = scope.async(start = CoroutineStart.LAZY) {
            fetch(key, uri).also { response ->
                if (response.statusCode in 200..299 && isGroupActive(group)) cache(key, response)
            }
        }
        val existing = loads.putIfAbsent(key, candidate)
        val load = existing ?: candidate.also { mine ->
            mine.invokeOnCompletion { loads.remove(key, mine) }
            mine.start()
        }
        if (existing != null) candidate.cancel()
        return load.await().also {
            touchGroup(group)
        }
    }

    private fun ensureActiveGroup(group: String, groupStillActive: () -> Boolean) {
        if (!groupStillActive()) throw PlaybackRevokedException()
        synchronized(lock) {
            activeGroups.add(group)
            groupLastAccess[group] = clock()
        }
        // Covers a last-member leave racing between the registry check and activation. If the
        // leave happened afterwards, its cleanup sees this active group and drops it itself.
        if (!groupStillActive()) {
            drop(group)
            throw PlaybackRevokedException()
        }
    }

    private suspend fun fetch(key: Key, uri: URI): SharedHlsResponse {
        val limit = connectionLimit(key.url)
        if (!isGroupActive(key.group)) throw PlaybackRevokedException()
        if (!gate.admit(key.group, providerKeyOf(key.url), limit)) {
            throw SharedHlsCapacityException()
        }
        if (!isGroupActive(key.group)) {
            gate.release(key.group)
            throw PlaybackRevokedException()
        }

        // The load belongs to the room rather than its first waiter. Keep the room's logical
        // provider seat alive even if a panel parks a blocking manifest request or that waiter
        // disconnects while another member can still consume the result.
        val heartbeat = scope.launch {
            while (isActive) {
                delay(FETCH_TOUCH_INTERVAL_MS)
                if (!isGroupActive(key.group)) return@launch
                touchGroup(key.group)
            }
        }
        try {
            val builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", http.userAgent)
            key.range?.let { builder.header(HttpHeaders.Range, it) }
            val upstream = try {
                http.sendStreaming(builder.build())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                throw SharedHlsUpstreamException(error)
            }
            val body = upstream.body()
            if (!retainBody(key.group, body)) {
                body.close()
                throw PlaybackRevokedException()
            }
            val cancellation = currentCoroutineContext()[Job]?.invokeOnCompletion { failure ->
                if (failure != null) runCatching(body::close)
            }
            try {
                val headers = upstream.headers()
                val contentType = headers.firstValue(HttpHeaders.ContentType).orElse(null)
                val declaredPlaylist = looksLikeHls(key.url, contentType)
                val maxBytes = if (declaredPlaylist) MAX_PLAYLIST_BYTES else MAX_RESOURCE_BYTES
                val declared = headers.firstValue(HttpHeaders.ContentLength).orElse(null)?.toLongOrNull()
                if (declared != null && declared > maxBytes) {
                    throw SharedHlsTooLargeException(declaredPlaylist)
                }
                val bytes = readBounded(body, maxBytes, key.group)
                // Some panels return extensionless variant manifests as octet-stream. Sniffing
                // after the bounded read keeps those URLs behind the existing HLS rewriter too.
                val playlist = declaredPlaylist || looksLikePlaylist(bytes)
                if (playlist && bytes.size > MAX_PLAYLIST_BYTES) {
                    throw SharedHlsTooLargeException(true)
                }
                return SharedHlsResponse(
                    statusCode = upstream.statusCode(),
                    uri = upstream.uri(),
                    contentType = contentType,
                    contentRange = headers.firstValue(HttpHeaders.ContentRange).orElse(null),
                    acceptRanges = headers.firstValue(HttpHeaders.AcceptRanges).orElse(null),
                    etag = headers.firstValue(HttpHeaders.ETag).orElse(null),
                    lastModified = headers.firstValue(HttpHeaders.LastModified).orElse(null),
                    bytes = bytes,
                    playlist = playlist,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: PlaybackRevokedException) {
                throw error
            } catch (error: SharedHlsTooLargeException) {
                throw error
            } catch (error: SharedHlsUpstreamException) {
                throw error
            } catch (error: Exception) {
                throw SharedHlsUpstreamException(error)
            } finally {
                cancellation?.dispose()
                runCatching(body::close)
                releaseBody(key.group, body)
            }
        } finally {
            heartbeat.cancel()
        }
    }

    private suspend fun readBounded(input: InputStream, maxBytes: Long, group: String): ByteArray {
        val collected = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var total = 0L
        var lastTouch = clock()
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total += count
            if (total > maxBytes) throw SharedHlsTooLargeException(maxBytes == MAX_PLAYLIST_BYTES)
            collected.write(buffer, 0, count)
            val now = clock()
            if (now - lastTouch >= FETCH_TOUCH_INTERVAL_MS) {
                touchGroup(group, now)
                lastTouch = now
            }
        }
        return collected.toByteArray()
    }

    private fun touchGroup(group: String, now: Long = clock()) {
        synchronized(lock) {
            if (group !in activeGroups) return
            groupLastAccess[group] = now
        }
        gate.touch(group)
    }

    private fun cache(key: Key, response: SharedHlsResponse) = synchronized(lock) {
        if (key.group !in activeGroups) return@synchronized
        cache.put(key, Cached(response, clock()))?.let { cacheBytes -= it.response.bytes.size }
        cacheBytes += response.bytes.size
        trimGroupLocked(key.group)
        trimGlobalLocked()
    }

    private fun trimGroupLocked(group: String) {
        while (true) {
            val groupEntries = cache.entries.filter { it.key.group == group }
            val mediaCount = groupEntries.count { !it.value.response.playlist }
            val bytes = groupEntries.sumOf { it.value.response.bytes.size.toLong() }
            if (groupEntries.size <= MAX_GROUP_ENTRIES &&
                mediaCount <= MAX_GROUP_MEDIA_ENTRIES &&
                bytes <= MAX_GROUP_BYTES
            ) return
            val eldest = groupEntries.firstOrNull()?.key ?: return
            removeLocked(eldest)
        }
    }

    private fun trimGlobalLocked() {
        val iterator = cache.entries.iterator()
        while ((cacheBytes > MAX_TOTAL_BYTES || cache.size > MAX_TOTAL_ENTRIES) &&
            iterator.hasNext()
        ) {
            val entry = iterator.next()
            cacheBytes -= entry.value.response.bytes.size
            iterator.remove()
        }
    }

    private fun removeLocked(key: Key) {
        cache.remove(key)?.let { cacheBytes -= it.response.bytes.size }
    }

    private fun retainBody(group: String, body: InputStream): Boolean = synchronized(lock) {
        if (group !in activeGroups) return@synchronized false
        bodies.getOrPut(group) { HashSet() }.add(body)
        true
    }

    private fun releaseBody(group: String, body: InputStream) = synchronized(lock) {
        bodies[group]?.let { active ->
            active.remove(body)
            if (active.isEmpty()) bodies.remove(group)
        }
    }

    private fun isGroupActive(group: String): Boolean = synchronized(lock) {
        group in activeGroups
    }

    /**
     * Ends a room's cache/read lifetime immediately. Closing bodies unblocks parked reads; cancelling
     * deferred loads wakes every waiter; releasing the group id returns its one provider seat.
     */
    fun drop(group: String) {
        val closingBodies = synchronized(lock) {
            activeGroups.remove(group)
            groupLastAccess.remove(group)
            cache.keys.filter { it.group == group }.forEach(::removeLocked)
            bodies.remove(group)?.toList().orEmpty()
        }
        loads.entries.filter { it.key.group == group }.forEach { (key, load) ->
            if (loads.remove(key, load)) load.cancel()
        }
        closingBodies.forEach { runCatching(it::close) }
        gate.release(group)
    }

    internal fun stats(group: String): SharedHlsCacheStats = synchronized(lock) {
        val entries = cache.filterKeys { it.group == group }.values
        SharedHlsCacheStats(
            entries = entries.size,
            mediaEntries = entries.count { !it.response.playlist },
            bytes = entries.sumOf { it.response.bytes.size.toLong() },
            readers = bodies[group]?.size ?: 0,
            loading = loads.keys.count { it.group == group },
            active = group in activeGroups,
        )
    }

    override fun close() {
        reaper.cancel()
        val groups = synchronized(lock) { activeGroups.toList() }
        groups.forEach(::drop)
        scope.cancel()
    }

    private companion object {
        /** A typical six-second live playlist keeps about two renditions for roughly one minute. */
        const val MAX_GROUP_MEDIA_ENTRIES = 24
        const val MAX_GROUP_ENTRIES = 32
        const val MAX_GROUP_BYTES = 64L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 256L * 1024 * 1024
        const val MAX_TOTAL_ENTRIES = 256
        const val MAX_RESOURCE_BYTES = 32L * 1024 * 1024
        const val MAX_PLAYLIST_BYTES = 2L * 1024 * 1024
        const val PLAYLIST_TTL_MS = 1_000L
        const val IDLE_EVICT_MS = 30_000L
        const val REAP_INTERVAL_MS = 5_000L
        const val FETCH_TOUCH_INTERVAL_MS = 4_000L
        const val COPY_BUFFER_BYTES = 64 * 1024

        private val HLS_CONTENT_TYPES = listOf("mpegurl", "m3u8")

        private fun looksLikeHls(url: String, contentType: String?): Boolean {
            val path = url.substringBefore('?')
            if (path.endsWith(".m3u8", true) || path.endsWith(".m3u", true)) return true
            return contentType != null &&
                HLS_CONTENT_TYPES.any { contentType.contains(it, ignoreCase = true) }
        }

        private fun looksLikePlaylist(bytes: ByteArray): Boolean {
            val prefix = bytes
                .take(minOf(bytes.size, PLAYLIST_SNIFF_BYTES))
                .toByteArray()
                .decodeToString()
                .removePrefix("\uFEFF")
            return prefix.startsWith("#EXTM3U")
        }

        const val PLAYLIST_SNIFF_BYTES = 16
    }
}

internal data class SharedHlsResponse(
    val statusCode: Int,
    val uri: URI,
    val contentType: String?,
    val contentRange: String?,
    val acceptRanges: String?,
    val etag: String?,
    val lastModified: String?,
    val bytes: ByteArray,
    val playlist: Boolean,
)

internal data class SharedHlsCacheStats(
    val entries: Int,
    val mediaEntries: Int,
    val bytes: Long,
    val readers: Int,
    val loading: Int,
    val active: Boolean,
)

internal class SharedHlsCapacityException : IOException("Provider connection limit reached")

internal class SharedHlsUpstreamException(cause: Throwable) : IOException("Upstream request failed", cause)

internal class SharedHlsTooLargeException(playlist: Boolean) : IOException(
    if (playlist) "Upstream playlist is too large" else "Upstream HLS resource is too large",
)
