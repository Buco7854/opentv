package com.buco7854.opentv.server

import com.buco7854.opentv.core.epg.TextSource
import com.buco7854.opentv.core.net.ConditionalFetch
import com.buco7854.opentv.core.net.ConditionalFetcher
import com.buco7854.opentv.core.net.HttpFetcher
import com.buco7854.opentv.core.net.OPENTV_METADATA_USER_AGENT
import com.buco7854.opentv.core.net.TextBody
import java.io.BufferedReader
import java.io.ByteArrayInputStream
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
import java.io.InputStreamReader
import java.io.SequenceInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
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

    private fun request(url: String, agent: String = userAgent): HttpRequest.Builder =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(60))
            .header("User-Agent", agent)

    /** :core's plain-GET port (Xtream API, metadata lookups). */
    val fetcher: HttpFetcher = HttpFetcher { url ->
        withContext(Dispatchers.IO) {
            val response = client.send(request(url).build(), HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) throw IOException("HTTP ${response.statusCode()}")
            response.body()
        }
    }

    /** Public metadata services require an identifiable application UA, not the provider UA. */
    val metadataFetcher: HttpFetcher = HttpFetcher { url ->
        withContext(Dispatchers.IO) {
            val response = client.send(
                request(url, OPENTV_METADATA_USER_AGENT).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
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
            body = textBody(
                response.body(),
                response.headers().firstValue("Content-Type").orElse(null),
            ),
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

    private fun textBody(stream: InputStream, contentType: String?): TextBody =
        StreamingTextBody(stream, contentType) { bodyStream(stream) }

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
    private val contentType: String?,
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
                    textReader(open(), contentType).use { reader ->
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

private val CHARSET_PARAMETER = Regex(
    """(?:^|;)\s*charset\s*=\s*(?:"([^"]+)"|([^;\s]+))""",
    RegexOption.IGNORE_CASE,
)

/** Keep this policy byte-for-byte equivalent to the Android conditional-feed adapter. */
private fun textReader(stream: InputStream, contentType: String?): BufferedReader =
    StreamingCharsetReader(stream, declaredCharset(contentType)).buffered()

private fun declaredCharset(contentType: String?): Charset? {
    val match = contentType?.let(CHARSET_PARAMETER::find) ?: return null
    val name = match.groupValues[1].ifEmpty { match.groupValues[2] }
    return runCatching { Charset.forName(name) }.getOrNull()
}

private data class Bom(val charset: Charset, val length: Int)

private fun detectBom(bytes: ByteArray, count: Int): Bom? = when {
    count >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() &&
        bytes[2] == 0xBF.toByte() -> Bom(StandardCharsets.UTF_8, 3)
    count >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
        Bom(StandardCharsets.UTF_16BE, 2)
    count >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
        Bom(StandardCharsets.UTF_16LE, 2)
    else -> null
}

private class StreamingCharsetReader(
    private val stream: InputStream,
    private val declared: Charset?,
) : java.io.Reader() {
    private var delegate: InputStreamReader? = null
    private var atStart = true
    private var eof = false

    override fun read(chars: CharArray, offset: Int, length: Int): Int {
        if (offset < 0 || length < 0 || length > chars.size - offset) throw IndexOutOfBoundsException()
        if (length == 0) return 0
        if (declared != null && delegate == null && !eof) selectDeclared()
        delegate?.let { return it.read(chars, offset, length) }
        if (eof) return -1

        var written = 0
        while (written < length && delegate == null) {
            val next = stream.read()
            if (next < 0) {
                eof = true
                break
            }
            if (next < 0x80) {
                chars[offset + written++] = next.toChar()
                atStart = false
            } else {
                selectUndeclared(next)
            }
        }
        delegate?.let { reader ->
            val decoded = reader.read(chars, offset + written, length - written)
            if (decoded < 0) {
                eof = true
                return if (written == 0) -1 else written
            }
            if (decoded > 0) written += decoded
        }
        return if (written == 0 && eof) -1 else written
    }

    override fun close() {
        delegate?.close() ?: stream.close()
    }

    private fun selectDeclared() {
        val charset = requireNotNull(declared)
        val first = stream.read()
        if (first < 0) {
            eof = true
            return
        }
        val length = when {
            charset == StandardCharsets.UTF_8 && first == 0xEF -> 3
            charset == StandardCharsets.UTF_16BE && first == 0xFE -> 2
            charset == StandardCharsets.UTF_16LE && first == 0xFF -> 2
            else -> 1
        }
        val bytes = readPrefix(first, length)
        val bom = detectBom(bytes, bytes.size)?.takeIf { it.charset == charset }
        select(bytes, charset, bom?.length ?: 0)
    }

    private fun selectUndeclared(first: Int) {
        val utf8Length = when (first) {
            in 0xC2..0xDF -> 2
            in 0xE0..0xEF -> 3
            in 0xF0..0xF4 -> 4
            else -> null
        }
        val length = if (atStart && (first == 0xFE || first == 0xFF)) 2 else utf8Length ?: 1
        val bytes = readPrefix(first, length)
        val bom = detectBom(bytes, bytes.size).takeIf { atStart }
        atStart = false
        when {
            bom != null -> select(bytes, bom.charset, bom.length)
            utf8Length != null && bytes.size == utf8Length && bytes.isStrictUtf8() ->
                select(bytes, StandardCharsets.UTF_8, 0)
            else -> select(bytes, StandardCharsets.ISO_8859_1, 0)
        }
    }

    private fun readPrefix(first: Int, length: Int): ByteArray {
        val bytes = ByteArray(length)
        bytes[0] = first.toByte()
        var count = 1
        while (count < length) {
            val next = stream.read()
            if (next < 0) break
            bytes[count++] = next.toByte()
        }
        return if (count == length) bytes else bytes.copyOf(count)
    }

    private fun select(bytes: ByteArray, charset: Charset, skip: Int) {
        delegate = InputStreamReader(
            SequenceInputStream(ByteArrayInputStream(bytes, skip, bytes.size - skip), stream),
            charset,
        )
    }
}

private fun ByteArray.isStrictUtf8(): Boolean = runCatching {
    StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(this))
}.isSuccess
