package com.buco7854.opentv.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Streams provider content through the server (browsers can't reach IPTV panels
 * directly: CORS, mixed content). HLS playlists are rewritten to route segment/key
 * URIs through the proxy too; Range requests pass through for VOD seeking.
 */
class StreamProxy(private val http: ServerHttp, private val cipher: StreamCipher) {

    // URIs go back as tokens so a rewritten playlist never exposes provider URLs.
    private fun proxied(absoluteUrl: String) =
        "/api/stream?u=${java.net.URLEncoder.encode(cipher.encrypt(absoluteUrl), Charsets.UTF_8)}"

    private val hlsContentTypes = listOf("mpegurl", "m3u8")

    private fun looksLikeHls(url: String, contentType: String?): Boolean {
        val path = url.substringBefore('?')
        if (path.endsWith(".m3u8", ignoreCase = true) || path.endsWith(".m3u", ignoreCase = true)) return true
        return contentType != null && hlsContentTypes.any { contentType.contains(it, ignoreCase = true) }
    }

    /** Rewrite every URI in an HLS playlist to go through the proxy. */
    internal fun rewriteHls(body: String, baseUri: URI): String {
        val uriAttr = Regex("""URI="([^"]+)"""")
        return body.lineSequence().joinToString("\n") { line ->
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> line
                trimmed.startsWith("#") -> uriAttr.replace(line) { match ->
                    """URI="${proxied(baseUri.resolve(match.groupValues[1]).toString())}""""
                }
                else -> proxied(baseUri.resolve(trimmed).toString())
            }
        }
    }

    suspend fun handle(call: ApplicationCall, cache: Boolean = false) {
        // Only tokens are accepted - a raw provider URL is never a valid input.
        var target = call.request.queryParameters["u"]?.let { cipher.tryDecrypt(it) }
        // Xtream live: the client asks for the HLS variant of a `.ts` token.
        if (call.request.queryParameters["hls"] == "1" && target != null) {
            target = target.replace(Regex("""\.ts(\?|$)"""), ".m3u8$1")
        }
        val uri = try {
            URI(target ?: "")
        } catch (_: Exception) {
            null
        }
        if (uri == null || uri.scheme !in listOf("http", "https")) {
            call.respond(HttpStatusCode.BadRequest, MessageDto("Invalid or missing target url"))
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
            call.respond(HttpStatusCode.BadGateway, MessageDto("Upstream request failed: ${e.message}"))
            return
        }

        val status = HttpStatusCode.fromValue(upstream.statusCode())
        val headers = upstream.headers()
        val contentType = headers.firstValue("Content-Type").orElse(null)

        if (upstream.statusCode() !in 200..299) {
            upstream.body().close()
            call.respond(HttpStatusCode.BadGateway, MessageDto("Upstream returned HTTP ${upstream.statusCode()}"))
            return
        }

        // HLS playlists are text and small: buffer, rewrite, respond.
        if (looksLikeHls(uri.toString(), contentType)) {
            val text = upstream.body().use { withContext(Dispatchers.IO) { it.readBytes() } }.decodeToString()
            if (text.startsWith("#EXTM3U")) {
                call.respondText(
                    rewriteHls(text, upstream.uri()),
                    ContentType.parse("application/vnd.apple.mpegurl"),
                )
                return
            }
            call.respondText(text, contentType?.let { ContentType.parse(it) } ?: ContentType.Application.OctetStream)
            return
        }

        headers.firstValue("Content-Range").orElse(null)?.let { call.response.header(HttpHeaders.ContentRange, it) }
        headers.firstValue("Accept-Ranges").orElse(null)?.let { call.response.header(HttpHeaders.AcceptRanges, it) }
        if (cache) call.response.header(HttpHeaders.CacheControl, "public, max-age=86400")

        val length = headers.firstValue("Content-Length").orElse(null)?.toLongOrNull()
        val type = contentType?.let { runCatching { ContentType.parse(it) }.getOrNull() }
            ?: ContentType.Application.OctetStream
        call.respondOutputStream(type, status, length) {
            upstream.body().use { input ->
                withContext(Dispatchers.IO) { input.copyTo(this@respondOutputStream) }
            }
        }
    }
}
