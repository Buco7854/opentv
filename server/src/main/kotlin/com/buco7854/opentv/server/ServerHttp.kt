package com.buco7854.opentv.server

import com.buco7854.opentv.core.epg.TextSource
import com.buco7854.opentv.core.net.ConditionalFetch
import com.buco7854.opentv.core.net.ConditionalFetcher
import com.buco7854.opentv.core.net.HttpFetcher
import com.buco7854.opentv.core.net.TextBody
import java.io.BufferedReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream

/**
 * :core's fetcher ports: one pooled client, conditional GET, gzip unwrapping.
 * User-Agent is tunable because many IPTV panels reject unknown agents.
 */
class ServerHttp {

    companion object {
        const val DEFAULT_USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20"
        const val MAX_USER_AGENT_LENGTH = 512

        /**
         * A usable agent is one line of printable ASCII.
         *
         * The value is sent verbatim by [java.net.http.HttpClient] - which rejects a header
         * holding a control character, failing *every* provider request - and by ffmpeg's
         * `-user_agent`, which would instead splice a newline into the provider's request as
         * extra headers. Neither is worth discovering at stream time.
         */
        fun isUsableUserAgent(value: String): Boolean =
            value.length <= MAX_USER_AGENT_LENGTH && value.all { it.code in 0x20..0x7E }
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
        val builder = request(url).header("Accept-Encoding", "gzip")
        if (etag != null) builder.header("If-None-Match", etag)
        if (lastModified != null) builder.header("If-Modified-Since", lastModified)

        val response = sendStreaming(builder.build())
        if (response.statusCode() == 304) {
            response.body().close()
            return@ConditionalFetcher ConditionalFetch.NotModified
        }
        if (response.statusCode() !in 200..299) {
            response.body().close()
            // Strip the query string: it carries credentials and this message reaches the UI.
            throw IOException("HTTP ${response.statusCode()} for ${url.substringBefore('?')}")
        }
        ConditionalFetch.Success(
            body = textBody(response.body()),
            etag = response.headers().firstValue("ETag").orElse(null),
            lastModified = response.headers().firstValue("Last-Modified").orElse(null),
        )
    }

    internal suspend fun sendStreaming(request: HttpRequest): HttpResponse<InputStream> =
        suspendCancellableCoroutine { continuation ->
            val future = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
            continuation.invokeOnCancellation {
                future.cancel(true)
                if (future.isDone && !future.isCancelled && !future.isCompletedExceptionally) {
                    runCatching { future.getNow(null)?.body()?.close() }
                }
            }
            future.whenComplete { response, failure ->
                if (failure != null) {
                    val cause = (failure as? CompletionException)?.cause ?: failure
                    if (continuation.isActive) {
                        continuation.resumeWith(Result.failure(cause))
                    }
                } else {
                    if (continuation.isActive) {
                        continuation.resumeWith(Result.success(response))
                    } else {
                        response.body().close()
                    }
                }
            }
        }

    private fun textBody(stream: InputStream): TextBody =
        StreamingTextBody(stream) { bodyStream(stream) }

    /** Unwraps gzip by magic bytes: covers .gz EPG files and encoded bodies alike. */
    private fun bodyStream(raw: InputStream): InputStream {
        val buffered = BufferedInputStream(raw)
        return try {
            buffered.mark(2)
            val first = buffered.read()
            val second = buffered.read()
            buffered.reset()
            if (first == 0x1f && second == 0x8b) GZIPInputStream(buffered) else buffered
        } catch (error: Throwable) {
            try {
                buffered.close()
            } catch (closeError: Throwable) {
                error.addSuppressed(closeError)
            }
            throw error
        }
    }
}

private class StreamingTextBody(
    private val raw: InputStream,
    private val open: () -> InputStream,
) : TextBody {
    private val consumed = AtomicBoolean()

    override suspend fun <T> readLines(block: suspend (Sequence<String>) -> T): T =
        consume { reader ->
            val job = currentCoroutineContext()[Job]
            block(reader.lineSequence().map { line ->
                job?.ensureActive()
                line
            })
        }

    override suspend fun <T> readChars(block: suspend (TextSource) -> T): T =
        consume { reader ->
            val job = currentCoroutineContext()[Job]
            block(
                TextSource {
                    job?.ensureActive()
                    reader.read()
                },
            )
        }

    override fun close() {
        raw.close()
    }

    private suspend fun <T> consume(block: suspend (BufferedReader) -> T): T {
        check(consumed.compareAndSet(false, true)) { "TextBody can only be consumed once" }
        return coroutineScope {
            val finished = AtomicBoolean()
            val cancellation = launch(
                context = Dispatchers.Unconfined,
                start = CoroutineStart.UNDISPATCHED,
            ) {
                try {
                    awaitCancellation()
                } finally {
                    if (!finished.get()) raw.close()
                }
            }
            try {
                withContext(Dispatchers.IO) {
                    open().bufferedReader().use { reader ->
                        block(reader)
                    }
                }
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                throw error
            } finally {
                if (currentCoroutineContext().isActive) finished.set(true)
                raw.close()
                cancellation.cancelAndJoin()
            }
        }
    }
}
