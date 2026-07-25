package com.buco7854.opentv.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.io.InputStream
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
        val request = HttpRequest.newBuilder(uri)
            .timeout(java.time.Duration.ofSeconds(30))
            .header("User-Agent", http.userAgent)
            .build()
        val upstream = try {
            withContext(Dispatchers.IO) {
                http.client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            }
        } catch (_: Exception) {
            call.respond(HttpStatusCode.BadGateway, ApiErrorDto("upstream_failure", "Image request failed"))
            return
        }
        upstream.body().use { input ->
            if (upstream.statusCode() !in 200..299) {
                call.respond(HttpStatusCode.BadGateway, ApiErrorDto("upstream_failure", "Image request failed"))
                return
            }
            val type = upstream.headers().firstValue("Content-Type").orElse("")
                .substringBefore(';').trim().lowercase()
            if (type !in allowedImageTypes) {
                call.respond(HttpStatusCode.UnsupportedMediaType, ApiErrorDto("invalid_image", "Unsupported image type"))
                return
            }
            val declared = upstream.headers().firstValue("Content-Length").orElse(null)?.toLongOrNull()
            if (declared != null && declared > MAX_IMAGE_BYTES) {
                call.respond(HttpStatusCode.PayloadTooLarge, ApiErrorDto("image_too_large", "Image is too large"))
                return
            }
            val bytes = try {
                withContext(Dispatchers.IO) {
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(32 * 1024)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_IMAGE_BYTES) throw ImageTooLargeException()
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
            } catch (_: ImageTooLargeException) {
                call.respond(HttpStatusCode.PayloadTooLarge, ApiErrorDto("image_too_large", "Image is too large"))
                return
            }
            call.response.header(HttpHeaders.CacheControl, "private, max-age=86400")
            call.response.header("X-Content-Type-Options", "nosniff")
            call.respondBytes(bytes, ContentType.parse(type))
        }
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
            .timeout(java.time.Duration.ofSeconds(30))
            .header("User-Agent", http.userAgent)
        call.request.headers[HttpHeaders.Range]?.let { builder.header("Range", it) }

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
            val text = try {
                body.use { withContext(Dispatchers.IO) { it.readBytes() } }.decodeToString()
            } finally {
                releaseBody(leaseId, body)
            }
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

    private class ImageTooLargeException : RuntimeException()

    private companion object {
        const val MAX_IMAGE_BYTES = 10 * 1024 * 1024
        val URI_ATTRIBUTE = Regex("""URI="([^"]+)"""")
        const val REJECTED_CHILD_URL = "/api/v1/stream"
    }
}
