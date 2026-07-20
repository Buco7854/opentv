package com.buco7854.opentv.data.net

import android.content.Context
import com.buco7854.opentv.core.epg.TextSource
import com.buco7854.opentv.core.net.ConditionalFetch
import com.buco7854.opentv.core.net.ConditionalFetcher
import com.buco7854.opentv.core.net.HttpFetcher
import com.buco7854.opentv.core.net.TextBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/** Shared HTTP layer: one pooled client, disk cache, conditional GET (ETag/If-Modified-Since). */
object Http {
    /** Many IPTV panels 404/403 unknown User-Agents. */
    const val DEFAULT_USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20"

    /** Mutable so users can match their provider's whitelist; set from prefs at start. */
    @Volatile
    var userAgent: String = DEFAULT_USER_AGENT

    @Volatile private var client: OkHttpClient? = null

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
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .build()
            ok.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                response.body?.string().orEmpty()
            }
        }
    }

    sealed class FetchResult {
        /** Cached copy still current; no body transferred. */
        object NotModified : FetchResult()
        class Success(val response: Response, val etag: String?, val lastModified: String?) : FetchResult()
    }

    /** :core's conditional-GET port, backed by [conditionalGet] below. */
    val conditionalFetcher = ConditionalFetcher { url, etag, lastModified ->
        withContext(Dispatchers.IO) {
            when (val result = conditionalGet(url, etag, lastModified)) {
                is FetchResult.NotModified -> ConditionalFetch.NotModified
                is FetchResult.Success -> ConditionalFetch.Success(
                    body = object : TextBody {
                        private val reader = bodyStream(result.response).bufferedReader()
                        override fun lines(): Sequence<String> = reader.lineSequence()
                        override fun chars(): TextSource = TextSource { reader.read() }
                        override fun close() = result.response.close()
                    },
                    etag = result.etag,
                    lastModified = result.lastModified,
                )
            }
        }
    }

    /** GET [url] with stored validators for a 304. Caller closes the body on Success. */
    @Throws(IOException::class)
    fun conditionalGet(url: String, etag: String?, lastModified: String?): FetchResult {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept-Encoding", "gzip")
        if (etag != null) builder.header("If-None-Match", etag)
        if (lastModified != null) builder.header("If-Modified-Since", lastModified)

        val response = ok.newCall(builder.build()).execute()
        if (response.code == 304) {
            response.close()
            return FetchResult.NotModified
        }
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            // Strip query string: playlist URLs carry credentials and this surfaces in logs.
            val safeUrl = url.substringBefore('?')
            throw IOException("HTTP $code for $safeUrl")
        }
        return FetchResult.Success(response, response.header("ETag"), response.header("Last-Modified"))
    }

    /** Unwraps gzip by magic bytes (0x1f 0x8b), not headers, to cover .gz files and Content-Encoding alike. */
    fun bodyStream(response: Response): InputStream {
        val raw = BufferedInputStream(response.body!!.byteStream())
        raw.mark(2)
        val first = raw.read()
        val second = raw.read()
        raw.reset()
        return if (first == 0x1f && second == 0x8b) GZIPInputStream(raw) else raw
    }
}
