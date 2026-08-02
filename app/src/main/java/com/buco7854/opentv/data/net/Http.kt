package com.buco7854.opentv.data.net

import android.content.Context
import com.buco7854.opentv.core.epg.TextSource
import com.buco7854.opentv.core.net.ConditionalFetch
import com.buco7854.opentv.core.net.ConditionalFetcher
import com.buco7854.opentv.core.net.HttpFetcher
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
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.SequenceInputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream

/** Shared HTTP layer: one pooled client, disk cache, conditional GET (ETag/If-Modified-Since). */
object Http {
    /** Many IPTV panels 404/403 unknown User-Agents. */
    const val DEFAULT_USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20"

    /** Mutable so users can match their provider's whitelist; set from prefs at start. */
    @Volatile
    var userAgent: String = DEFAULT_USER_AGENT

    @Volatile private var client: OkHttpClient? = null

    @Synchronized
    fun init(context: Context) {
        if (client == null) {
            client = OkHttpClient.Builder()
                .cache(Cache(File(context.cacheDir, "http"), 32L * 1024 * 1024))
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }
    }

    val ok: OkHttpClient
        get() = client ?: error("Http.init() not called")

    /** Bridge for :core's platform-neutral clients (Xtream API, metadata). */
    val fetcher: HttpFetcher = HttpFetcher { url ->
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .build()
        ok.newCall(request).executeCancellable { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            response.body.string()
        }
    }

    /** :core's conditional-GET port. Body consumption stays tied to the OkHttp call. */
    val conditionalFetcher: ConditionalFetcher =
        createConditionalFetcher(client = { ok }, userAgent = { userAgent })

    /** Unwraps gzip by magic bytes (0x1f 0x8b), not headers, to cover .gz files and Content-Encoding alike. */
    fun bodyStream(response: Response): InputStream {
        val raw = BufferedInputStream(response.body.byteStream())
        return try {
            raw.mark(2)
            val first = raw.read()
            val second = raw.read()
            raw.reset()
            if (first == 0x1f && second == 0x8b) GZIPInputStream(raw) else raw
        } catch (error: Throwable) {
            try {
                raw.close()
            } catch (closeError: Throwable) {
                error.addSuppressed(closeError)
            }
            throw error
        }
    }
}

internal fun createConditionalFetcher(
    client: () -> OkHttpClient,
    userAgent: () -> String,
): ConditionalFetcher = ConditionalFetcher { url, etag, lastModified ->
    val builder = Request.Builder()
        .url(url)
        .header("User-Agent", userAgent())
        .header("Accept-Encoding", "gzip")
    if (etag != null) builder.header("If-None-Match", etag)
    if (lastModified != null) builder.header("If-Modified-Since", lastModified)

    val call = client().newCall(builder.build())
    val response = call.executeStreamingCancellable()
    when {
        response.code == 304 -> {
            response.close()
            ConditionalFetch.NotModified
        }
        !response.isSuccessful -> {
            val code = response.code
            response.close()
            // Strip query string: playlist URLs carry credentials and this surfaces in logs.
            throw IOException("HTTP $code for ${url.substringBefore('?')}")
        }
        else -> ConditionalFetch.Success(
            body = OkHttpTextBody(call, response),
            etag = response.header("ETag"),
            lastModified = response.header("Last-Modified"),
        )
    }
}

/**
 * A single-use streaming body. Parsing runs on IO, and cancellation cancels
 * the call so a blocked socket read is interrupted rather than occupying Main
 * until OkHttp's timeout.
 */
private class OkHttpTextBody(
    private val call: Call,
    private val response: Response,
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
        response.close()
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
                    if (!finished.get()) call.cancel()
                }
            }
            try {
                withContext(Dispatchers.IO) {
                    textReader(Http.bodyStream(response), response.header("Content-Type")).use { reader ->
                        block(reader)
                    }
                }
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                throw error
            } finally {
                if (currentCoroutineContext().isActive) finished.set(true)
                response.close()
                cancellation.cancelAndJoin()
            }
        }
    }
}

private val CHARSET_PARAMETER = Regex(
    """(?:^|;)\s*charset\s*=\s*(?:"([^"]+)"|([^;\s]+))""",
    RegexOption.IGNORE_CASE,
)

/**
 * HTTP declarations win. Without one, ASCII is streamed unchanged until a BOM or the first
 * non-ASCII sequence chooses strict UTF-8 or ISO-8859-1. No whole-feed lookahead is required.
 */
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

/** Executes only through response headers; the returned response remains open. */
private suspend fun Call.executeStreamingCancellable(): Response = coroutineScope {
    val call = this@executeStreamingCancellable
    val finished = AtomicBoolean()
    val cancellation = launch(
        context = Dispatchers.Unconfined,
        start = CoroutineStart.UNDISPATCHED,
    ) {
        try {
            awaitCancellation()
        } finally {
            if (!finished.get()) call.cancel()
        }
    }
    try {
        withContext(Dispatchers.IO) {
            try {
                call.execute().also {
                    currentCoroutineContext().ensureActive()
                }
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                throw error
            }
        }
    } finally {
        if (currentCoroutineContext().isActive) finished.set(true)
        cancellation.cancelAndJoin()
    }
}
