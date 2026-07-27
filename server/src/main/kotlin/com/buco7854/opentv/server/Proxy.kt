package com.buco7854.opentv.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Streams provider content through the server (browsers can't reach IPTV panels
 * directly: CORS, mixed content). HLS playlists are rewritten to route segment/key
 * URIs through the proxy too; Range requests pass through for VOD seeking.
 */
class StreamProxy(
    private val http: ServerHttp,
    private val cipher: StreamCipher,
    private val gate: StreamGate,
    /** Concurrent reads the provider behind a URL permits (its max_connections). */
    private val connectionLimit: suspend (String) -> Int,
) {
    private val log = LoggerFactory.getLogger("opentv")
    private val activeBodies = ConcurrentHashMap<String, MutableSet<InputStream>>()
    /**
     * Disk tier for the posters too large to hold in heap. A filesystem that refuses it -
     * a read-only container, a full disk - must cost the tier, not the server: the memory
     * tier still works, so this is null rather than a failure to start.
     */
    private val imageCacheDirectory: Path? = runCatching {
        Files.createTempDirectory("opentv-image-cache-").also { it.toFile().deleteOnExit() }
    }.getOrElse {
        log.warn("No image disk cache ({}): large posters will not be cached", it.message)
        null
    }
    private val imageCacheLock = Any()
    /** Access-ordered and byte-bounded: provider posters must not become an unbounded heap cache. */
    private val imageCache = LinkedHashMap<String, CachedImage>(16, 0.75f, true)
    private val activeImageFiles = HashMap<Path, Int>()
    private val retiredImageFiles = HashSet<Path>()
    private val imageLoads = ConcurrentHashMap<String, CompletableDeferred<CachedImage>>()
    private var imageCacheBytes = 0L
    private var imageMemoryBytes = 0L

    private fun proxied(absoluteUrl: String, leaseId: String, mediaGrant: String?): String =
        "/api/v1/stream?u=${urlEncode(cipher.encryptStream(absoluteUrl, leaseId))}" +
            "&sid=${urlEncode(leaseId)}&g=${urlEncode(mediaGrant.orEmpty())}"

    private val hlsContentTypes = listOf("mpegurl", "m3u8")
    private val allowedImageTypes = setOf(
        "image/avif", "image/bmp", "image/gif", "image/jpeg", "image/png",
        "image/webp", "image/x-icon", "image/vnd.microsoft.icon",
    )

    private fun looksLikeHls(url: String, contentType: String?): Boolean {
        val path = url.substringBefore('?')
        if (path.endsWith(".m3u8", ignoreCase = true) || path.endsWith(".m3u", ignoreCase = true)) return true
        return contentType != null && hlsContentTypes.any { contentType.contains(it, ignoreCase = true) }
    }

    internal fun rewriteHls(body: String, baseUri: URI, leaseId: String, mediaGrant: String? = null): String {
        var rejected = 0
        fun child(raw: String): String {
            val resolved = runCatching { baseUri.resolve(raw) }.getOrNull()
            if (resolved == null || !sameOrigin(baseUri, resolved)) {
                rejected++
                return REJECTED_CHILD_URL
            }
            return proxied(resolved.toString(), leaseId, mediaGrant)
        }
        val rewritten = body.lineSequence().joinToString("\n") { line ->
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> line
                trimmed.startsWith("#") ->
                    URI_ATTRIBUTE.replace(line) { """URI="${child(it.groupValues[1])}"""" }
                else -> child(trimmed)
            }
        }
        if (rejected > 0) {
            log.warn("Rejected {} off-origin URI(s) in the playlist at {}", rejected, baseUri)
        }
        return rewritten
    }

    private fun sameOrigin(base: URI, candidate: URI): Boolean {
        val scheme = candidate.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        if (scheme != base.scheme?.lowercase()) return false
        val host = candidate.host ?: return false
        val baseHost = base.host ?: return false
        if (!host.equals(baseHost, ignoreCase = true)) return false
        return effectivePort(candidate) == effectivePort(base)
    }

    private fun effectivePort(uri: URI): Int =
        uri.port.takeIf { it > 0 } ?: if (uri.scheme.equals("https", true)) 443 else 80

    suspend fun image(call: ApplicationCall, target: String) {
        val uri = runCatching { URI(target) }.getOrNull()
        if (uri == null || uri.scheme !in listOf("http", "https")) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorDto("invalid_target", "Invalid image target"))
            return
        }

        val cached = cachedImage(target)
        val entry = if (cached != null && cached.isFresh()) {
            cached
        } else {
            try {
                loadImage(target, uri, cached)
            } catch (error: ImageFetchException) {
                // A validator failure should not turn an already-renderable grid into broken
                // placeholders. The capability and playlist access were checked before here;
                // serving its last good bytes changes availability, not authorization.
                cached?.takeIf(CachedImage::storageExists) ?: run {
                    call.respond(error.status, ApiErrorDto(error.code, error.message.orEmpty()))
                    return
                }
            }
        }

        // A stale entry can have been evicted between lookup and response. Retry once through
        // the single-flight rather than racing a LocalFileContent open against deletion.
        if (!retainImage(entry)) {
            val reloaded = try {
                loadImage(target, uri, null)
            } catch (error: ImageFetchException) {
                call.respond(error.status, ApiErrorDto(error.code, error.message.orEmpty()))
                return
            }
            if (!retainImage(reloaded)) {
                call.respond(
                    HttpStatusCode.BadGateway,
                    ApiErrorDto("upstream_failure", "Image cache entry disappeared"),
                )
                return
            }
            respondImage(call, reloaded)
            return
        }
        respondImage(call, entry)
    }

    private suspend fun respondImage(call: ApplicationCall, entry: CachedImage) {
        try {
            imageHeaders(call, entry)
            val ifNoneMatch = call.request.headers[HttpHeaders.IfNoneMatch]
            val notModified = if (ifNoneMatch != null) {
                ifNoneMatch.matchesEtag(entry.etag)
            } else {
                entry.lastModified != null &&
                    call.request.headers[HttpHeaders.IfModifiedSince] == entry.lastModified
            }
            if (notModified) {
                call.respond(HttpStatusCode.NotModified)
            } else {
                val type = ContentType.parse(entry.contentType)
                if (entry.bytes != null) {
                    call.respondBytes(entry.bytes, type)
                } else {
                    // Large/unknown-length images spool once, then LocalFileContent streams
                    // without putting their whole bodies in heap and participates in ranges.
                    call.respond(LocalFileContent(requireNotNull(entry.path).toFile(), type))
                }
            }
        } finally {
            releaseImage(entry)
        }
    }

    private fun imageHeaders(call: ApplicationCall, entry: CachedImage) {
        call.response.header(HttpHeaders.CacheControl, IMAGE_CACHE_CONTROL)
        call.response.header(HttpHeaders.ETag, entry.etag)
        entry.lastModified?.let { call.response.header(HttpHeaders.LastModified, it) }
        call.response.header(CONTENT_TYPE_OPTIONS_HEADER, "nosniff")
    }

    private suspend fun loadImage(target: String, uri: URI, stale: CachedImage?): CachedImage {
        while (true) {
            val mine = CompletableDeferred<CachedImage>()
            val existing = imageLoads.putIfAbsent(target, mine)
            if (existing != null) return existing.await()
            try {
                val loaded = fetchImage(uri, stale)
                cacheImage(target, loaded)
                mine.complete(loaded)
                return loaded
            } catch (error: Throwable) {
                mine.completeExceptionally(error)
                throw error
            } finally {
                imageLoads.remove(target, mine)
            }
        }
    }

    private suspend fun fetchImage(uri: URI, stale: CachedImage?): CachedImage =
        withContext(Dispatchers.IO) {
            val builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", http.userAgent)
            stale?.upstreamEtag?.let { builder.header(HttpHeaders.IfNoneMatch, it) }
            stale?.lastModified?.let { builder.header(HttpHeaders.IfModifiedSince, it) }
            val upstream = try {
                http.client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
            } catch (_: Exception) {
                throw ImageFetchException(
                    HttpStatusCode.BadGateway,
                    "upstream_failure",
                    "Image request failed",
                )
            }
            upstream.body().use { input ->
                if (upstream.statusCode() == 304 && stale != null && stale.storageExists()) {
                    return@withContext stale.copy(cachedAtMs = System.currentTimeMillis())
                }
                if (upstream.statusCode() !in 200..299) {
                    throw ImageFetchException(
                        HttpStatusCode.BadGateway,
                        "upstream_failure",
                        "Image request failed",
                    )
                }
                val headers = upstream.headers()
                val type = headers.firstValue(HttpHeaders.ContentType).orElse("")
                    .substringBefore(';').trim().lowercase()
                if (type !in allowedImageTypes) {
                    throw ImageFetchException(
                        HttpStatusCode.UnsupportedMediaType,
                        "invalid_image",
                        "Unsupported image type",
                    )
                }
                val declared = headers.firstValue(HttpHeaders.ContentLength).orElse(null)?.toLongOrNull()
                if (declared != null && declared > MAX_IMAGE_BYTES) {
                    throw ImageFetchException(
                        HttpStatusCode.PayloadTooLarge,
                        "image_too_large",
                        "Image is too large",
                    )
                }
                if (declared != null && declared <= MAX_MEMORY_IMAGE_BYTES) {
                    val bytes = ByteArray(declared.toInt())
                    val digest = MessageDigest.getInstance("SHA-256")
                    var offset = 0
                    while (offset < bytes.size) {
                        val count = input.read(bytes, offset, bytes.size - offset)
                        if (count < 0) break
                        if (count == 0) continue
                        digest.update(bytes, offset, count)
                        offset += count
                    }
                    if (offset != bytes.size) {
                        throw ImageFetchException(
                            HttpStatusCode.BadGateway,
                            "upstream_failure",
                            "Image response ended early",
                        )
                    }
                    return@withContext CachedImage(
                        path = null,
                        bytes = bytes,
                        size = bytes.size.toLong(),
                        contentType = type,
                        etag = "\"${digest.digest().toHex()}\"",
                        upstreamEtag = headers.firstValue(HttpHeaders.ETag).orElse(null),
                        lastModified = headers.firstValue(HttpHeaders.LastModified).orElse(null),
                        cachedAtMs = System.currentTimeMillis(),
                    )
                }
                val cacheDirectory = imageCacheDirectory
                if (cacheDirectory == null) {
                    // No disk tier: hold it in memory under the same hard cap, which is what
                    // this proxy did for every image before the tier existed.
                    val digest = MessageDigest.getInstance("SHA-256")
                    val collected = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(IMAGE_COPY_BUFFER_BYTES)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        total += count
                        if (total > MAX_IMAGE_BYTES) {
                            throw ImageFetchException(
                                HttpStatusCode.PayloadTooLarge,
                                "image_too_large",
                                "Image is too large",
                            )
                        }
                        digest.update(buffer, 0, count)
                        collected.write(buffer, 0, count)
                    }
                    val bytes = collected.toByteArray()
                    return@withContext CachedImage(
                        path = null,
                        bytes = bytes,
                        size = bytes.size.toLong(),
                        contentType = type,
                        etag = "\"${digest.digest().toHex()}\"",
                        upstreamEtag = headers.firstValue(HttpHeaders.ETag).orElse(null),
                        lastModified = headers.firstValue(HttpHeaders.LastModified).orElse(null),
                        cachedAtMs = System.currentTimeMillis(),
                    )
                }
                val temp = Files.createTempFile(cacheDirectory, "image-", ".cache")
                temp.toFile().deleteOnExit()
                try {
                    val digest = MessageDigest.getInstance("SHA-256")
                    var total = 0L
                    Files.newOutputStream(temp).use { output ->
                        val buffer = ByteArray(IMAGE_COPY_BUFFER_BYTES)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            total += count
                            if (total > MAX_IMAGE_BYTES) {
                                throw ImageFetchException(
                                    HttpStatusCode.PayloadTooLarge,
                                    "image_too_large",
                                    "Image is too large",
                                )
                            }
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                    }
                    CachedImage(
                        path = temp,
                        bytes = null,
                        size = total,
                        contentType = type,
                        etag = "\"${digest.digest().toHex()}\"",
                        upstreamEtag = headers.firstValue(HttpHeaders.ETag).orElse(null),
                        lastModified = headers.firstValue(HttpHeaders.LastModified).orElse(null),
                        cachedAtMs = System.currentTimeMillis(),
                    )
                } catch (error: Throwable) {
                    Files.deleteIfExists(temp)
                    throw error
                }
            }
        }

    private fun cachedImage(target: String): CachedImage? = synchronized(imageCacheLock) {
        imageCache[target]?.takeIf(CachedImage::storageExists) ?: imageCache.remove(target)?.also {
            subtractImageSize(it)
        }?.let { null }
    }

    private fun cacheImage(target: String, image: CachedImage) = synchronized(imageCacheLock) {
        imageCache.put(target, image)?.let { replaced ->
            subtractImageSize(replaced)
            if (replaced.path != null && replaced.path != image.path) retireImage(replaced.path)
        }
        imageCacheBytes += image.size
        if (image.bytes != null) imageMemoryBytes += image.size
        val iterator = imageCache.entries.iterator()
        while ((imageCacheBytes > MAX_IMAGE_CACHE_BYTES ||
                imageMemoryBytes > MAX_MEMORY_IMAGE_CACHE_BYTES ||
                imageCache.size > MAX_IMAGE_CACHE_ENTRIES) &&
            iterator.hasNext()
        ) {
            val evicted = iterator.next().value
            iterator.remove()
            subtractImageSize(evicted)
            evicted.path?.let(::retireImage)
        }
    }

    private fun retainImage(image: CachedImage): Boolean = synchronized(imageCacheLock) {
        val path = image.path ?: return@synchronized true
        if (!Files.exists(path) || path in retiredImageFiles) return@synchronized false
        activeImageFiles[path] = (activeImageFiles[path] ?: 0) + 1
        true
    }

    private fun releaseImage(image: CachedImage) = synchronized(imageCacheLock) {
        val path = image.path ?: return@synchronized
        val remaining = (activeImageFiles[path] ?: 1) - 1
        if (remaining > 0) activeImageFiles[path] = remaining else {
            activeImageFiles.remove(path)
            if (retiredImageFiles.remove(path)) Files.deleteIfExists(path)
        }
    }

    private fun subtractImageSize(image: CachedImage) {
        imageCacheBytes -= image.size
        if (image.bytes != null) imageMemoryBytes -= image.size
    }

    private fun retireImage(path: Path) {
        if (activeImageFiles.containsKey(path)) retiredImageFiles.add(path)
        else Files.deleteIfExists(path)
    }

    suspend fun handle(
        call: ApplicationCall,
        capability: StreamCapability,
        mediaGrant: String?,
        leaseGuard: () -> Unit,
    ) {
        val leaseId = capability.leaseId
        val target = if (call.request.queryParameters["hls"] == "1") {
            capability.url.replace(Regex("""\.ts(\?|$)"""), ".m3u8$1")
        } else {
            capability.url
        }
        val uri = runCatching { URI(target) }.getOrNull()
        if (uri == null || uri.scheme !in listOf("http", "https")) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorDto("invalid_target", "Invalid or missing target url"))
            return
        }

        // Enforce the provider's concurrent-stream cap here, not just in the UI: refuse a new
        // live stream when the provider's other streams already fill it, instead of cutting one.
        val streamUrl = uri.toString()
        if (!gate.admit(leaseId, providerKeyOf(streamUrl), connectionLimit(streamUrl))) {
            call.respond(
                HttpStatusCode.TooManyRequests,
                ApiErrorDto("provider_capacity", "Provider connection limit reached"),
            )
            return
        }

        val builder = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(30))
            .header("User-Agent", http.userAgent)
        call.request.headers[HttpHeaders.Range]?.let { builder.header("Range", it) }
        call.request.headers[HttpHeaders.IfNoneMatch]?.let { builder.header(HttpHeaders.IfNoneMatch, it) }
        call.request.headers[HttpHeaders.IfModifiedSince]
            ?.let { builder.header(HttpHeaders.IfModifiedSince, it) }

        val upstream = try {
            withContext(Dispatchers.IO) {
                http.client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
            }
        } catch (e: Exception) {
            log.warn("Upstream request failed for {}: {}", providerKeyOf(streamUrl), e.message)
            call.respond(
                HttpStatusCode.BadGateway,
                ApiErrorDto("upstream_failure", "Upstream request failed"),
            )
            return
        }

        val status = HttpStatusCode.fromValue(upstream.statusCode())
        val body = upstream.body()
        trackBody(leaseId, body)
        try {
            leaseGuard()
        } catch (error: Exception) {
            body.close()
            releaseBody(leaseId, body)
            throw error
        }
        val headers = upstream.headers()
        val contentType = headers.firstValue("Content-Type").orElse(null)
        val upstreamEtag = headers.firstValue(HttpHeaders.ETag).orElse(null)
        val upstreamModified = headers.firstValue(HttpHeaders.LastModified).orElse(null)

        if (upstream.statusCode() == HttpStatusCode.NotModified.value) {
            body.close()
            releaseBody(leaseId, body)
            upstreamEtag?.let { call.response.header(HttpHeaders.ETag, it) }
            upstreamModified?.let { call.response.header(HttpHeaders.LastModified, it) }
            call.response.header(HttpHeaders.CacheControl, "private, no-cache")
            call.respond(HttpStatusCode.NotModified)
            return
        }

        if (upstream.statusCode() !in 200..299) {
            body.close()
            releaseBody(leaseId, body)
            call.respond(
                HttpStatusCode.BadGateway,
                ApiErrorDto("upstream_failure", "Upstream returned HTTP ${upstream.statusCode()}"),
            )
            return
        }

        // HLS playlists are text and small: buffer, rewrite, respond.
        if (looksLikeHls(streamUrl, contentType)) {
            val bytes = try {
                body.use {
                    withContext(Dispatchers.IO) { it.readNBytes(MAX_HLS_PLAYLIST_BYTES + 1) }
                }
            } finally {
                releaseBody(leaseId, body)
            }
            if (bytes.size > MAX_HLS_PLAYLIST_BYTES) {
                call.respond(
                    HttpStatusCode.BadGateway,
                    ApiErrorDto("upstream_failure", "Upstream playlist is too large"),
                )
                return
            }
            val text = bytes.decodeToString()
            call.response.header(HttpHeaders.CacheControl, "no-store")
            if (text.startsWith("#EXTM3U")) {
                call.respondText(
                    rewriteHls(text, upstream.uri(), leaseId, mediaGrant),
                    ContentType.parse("application/vnd.apple.mpegurl"),
                )
                return
            }
            call.respondText(text, contentType?.let { ContentType.parse(it) } ?: ContentType.Application.OctetStream)
            return
        }

        headers.firstValue("Content-Range").orElse(null)?.let { call.response.header(HttpHeaders.ContentRange, it) }
        headers.firstValue("Accept-Ranges").orElse(null)?.let { call.response.header(HttpHeaders.AcceptRanges, it) }
        upstreamEtag?.let { call.response.header(HttpHeaders.ETag, it) }
        upstreamModified?.let { call.response.header(HttpHeaders.LastModified, it) }
        call.response.header(
            HttpHeaders.CacheControl,
            if (upstreamEtag != null || upstreamModified != null) "private, no-cache" else "private, no-store",
        )

        val length = headers.firstValue("Content-Length").orElse(null)?.toLongOrNull()
        // Past the playlist branch everything here is opaque media, streamed for as long as the
        // viewer watches. The content type is the provider's to choose, so one that mislabels a
        // continuous stream as text would opt this response into the compression allowlist - the
        // one streaming route that cannot simply be trusted to declare its own type.
        val type = contentType
            ?.let { runCatching { ContentType.parse(it) }.getOrNull() }
            ?.takeUnless(::isCompressible)
            ?: ContentType.Application.OctetStream
        try {
            call.respondOutputStream(type, status, length) {
                    // A continuous transport stream is one long read: touch the gate as bytes
                    // flow so its slot isn't reaped mid-stream. Segment reads finish fast and
                    // re-admit on the next request, so this is a no-op for them.
                body.use { input ->
                    withContext(Dispatchers.IO) {
                        val buffer = ByteArray(64 * 1024)
                        var lastTouch = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            this@respondOutputStream.write(buffer, 0, n)
                            val now = System.currentTimeMillis()
                            if (now - lastTouch > 4_000) { lastTouch = now; gate.touch(leaseId) }
                        }
                    }
                }
            }
        } finally {
            releaseBody(leaseId, body)
        }
    }

    private fun trackBody(leaseId: String, body: InputStream) {
        activeBodies.computeIfAbsent(leaseId) { ConcurrentHashMap.newKeySet() }.add(body)
    }

    private fun releaseBody(leaseId: String, body: InputStream) {
        activeBodies[leaseId]?.let {
            it.remove(body)
            if (it.isEmpty()) activeBodies.remove(leaseId, it)
        }
    }

    fun drop(leaseId: String) {
        activeBodies.remove(leaseId)?.forEach { runCatching { it.close() } }
    }

    private data class CachedImage(
        val path: Path?,
        val bytes: ByteArray?,
        val size: Long,
        val contentType: String,
        /** Server-owned content validator: upstream validators are only used for revalidation. */
        val etag: String,
        val upstreamEtag: String?,
        val lastModified: String?,
        val cachedAtMs: Long,
    ) {
        fun isFresh(): Boolean = System.currentTimeMillis() - cachedAtMs < IMAGE_REVALIDATE_MS
        fun storageExists(): Boolean = bytes != null || path?.let(Files::exists) == true
    }

    private class ImageFetchException(
        val status: HttpStatusCode,
        val code: String,
        message: String,
    ) : IOException(message)

    private companion object {
        const val MAX_IMAGE_BYTES = 10L * 1024 * 1024
        const val MAX_IMAGE_CACHE_BYTES = 128L * 1024 * 1024
        const val MAX_MEMORY_IMAGE_BYTES = 2L * 1024 * 1024
        const val MAX_MEMORY_IMAGE_CACHE_BYTES = 32L * 1024 * 1024
        const val MAX_IMAGE_CACHE_ENTRIES = 512
        const val IMAGE_REVALIDATE_MS = 60 * 60_000L
        const val IMAGE_COPY_BUFFER_BYTES = 64 * 1024
        const val IMAGE_CACHE_CONTROL = "private, max-age=86400"
        const val MAX_HLS_PLAYLIST_BYTES = 2 * 1024 * 1024
        val URI_ATTRIBUTE = Regex("""URI="([^"]+)"""")
        const val REJECTED_CHILD_URL = "/api/v1/stream"
    }
}

private fun String?.matchesEtag(etag: String): Boolean =
    this?.split(',')?.any { candidate ->
        candidate.trim().let { it == "*" || it == etag || it.removePrefix("W/") == etag }
    } == true

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
