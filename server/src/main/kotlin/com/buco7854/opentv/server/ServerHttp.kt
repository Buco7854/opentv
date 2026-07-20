package com.buco7854.opentv.server

import com.buco7854.opentv.core.epg.TextSource
import com.buco7854.opentv.core.net.ConditionalFetch
import com.buco7854.opentv.core.net.ConditionalFetcher
import com.buco7854.opentv.core.net.HttpFetcher
import com.buco7854.opentv.core.net.TextBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.zip.GZIPInputStream

/**
 * :core's fetcher ports: one pooled client, conditional GET, gzip unwrapping.
 * User-Agent is tunable because many IPTV panels reject unknown agents.
 */
class ServerHttp {

    companion object {
        const val DEFAULT_USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20"
    }

    @Volatile
    var userAgent: String = DEFAULT_USER_AGENT

    // ALWAYS, not NORMAL: panels often redirect HTTPS to plain-HTTP stream hosts.
    val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .connectTimeout(Duration.ofSeconds(20))
        .build()

    private fun request(url: String): HttpRequest.Builder =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(60))
            .header("User-Agent", userAgent)

    /** :core's plain-GET port (Xtream API, metadata lookups). */
    val fetcher: HttpFetcher = HttpFetcher { url ->
        withContext(Dispatchers.IO) {
            val response = client.send(request(url).build(), HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) throw IOException("HTTP ${response.statusCode()}")
            response.body()
        }
    }

    /** :core's conditional-GET port (playlist and EPG downloads). */
    val conditionalFetcher = ConditionalFetcher { url, etag, lastModified ->
        withContext(Dispatchers.IO) {
            val builder = request(url).header("Accept-Encoding", "gzip")
            if (etag != null) builder.header("If-None-Match", etag)
            if (lastModified != null) builder.header("If-Modified-Since", lastModified)

            val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
            if (response.statusCode() == 304) {
                response.body().close()
                return@withContext ConditionalFetch.NotModified
            }
            if (response.statusCode() !in 200..299) {
                response.body().close()
                // Strip the query string: it carries credentials and this message reaches the UI.
                throw IOException("HTTP ${response.statusCode()} for ${url.substringBefore('?')}")
            }
            ConditionalFetch.Success(
                body = textBody(bodyStream(response.body())),
                etag = response.headers().firstValue("ETag").orElse(null),
                lastModified = response.headers().firstValue("Last-Modified").orElse(null),
            )
        }
    }

    private fun textBody(stream: InputStream): TextBody = object : TextBody {
        private val reader = stream.bufferedReader()
        override fun lines(): Sequence<String> = reader.lineSequence()
        override fun chars(): TextSource = TextSource { reader.read() }
        override fun close() = reader.close()
    }

    /** Unwraps gzip by magic bytes: covers .gz EPG files and encoded bodies alike. */
    private fun bodyStream(raw: InputStream): InputStream {
        val buffered = BufferedInputStream(raw)
        buffered.mark(2)
        val first = buffered.read()
        val second = buffered.read()
        buffered.reset()
        return if (first == 0x1f && second == 0x8b) GZIPInputStream(buffered) else buffered
    }
}
