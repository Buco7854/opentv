package com.buco7854.opentv.data.net

import android.content.Context
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
                    Http.bodyStream(response).bufferedReader().use { reader ->
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
