package com.buco7854.opentv.data.net

import android.content.Context
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

/**
 * Shared HTTP layer, deliberately frugal with requests:
 *  - one connection-pooled client for the whole app,
 *  - a disk cache so repeat fetches can be served locally,
 *  - conditional GET support (ETag / If-Modified-Since) so unchanged playlists
 *    and EPG files cost a single 304 with no body transfer.
 */
object Http {
    const val USER_AGENT = "OpenTV/0.1 (Android)"

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

    sealed class FetchResult {
        /** Server says our cached copy is still current; no body was transferred. */
        object NotModified : FetchResult()
        class Success(val response: Response, val etag: String?, val lastModified: String?) : FetchResult()
    }

    /**
     * GET [url], sending stored validators so the server can answer 304.
     * Caller is responsible for closing the response body on Success.
     */
    @Throws(IOException::class)
    fun conditionalGet(url: String, etag: String?, lastModified: String?): FetchResult {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
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
            // Strip the query string: playlist URLs carry credentials, and this
            // message ends up in snackbars and the in-app error log.
            val safeUrl = url.substringBefore('?')
            throw IOException("HTTP $code for $safeUrl")
        }
        return FetchResult.Success(response, response.header("ETag"), response.header("Last-Modified"))
    }

    /**
     * Unwraps gzip for servers and .gz files that return compressed bodies.
     * Detection is by magic bytes (0x1f 0x8b) rather than headers: it covers
     * `.gz` EPG files, Content-Encoding responses, and bodies OkHttp already
     * decompressed, without ever mangling a plain-text stream.
     */
    fun bodyStream(response: Response): InputStream {
        val raw = BufferedInputStream(response.body!!.byteStream())
        raw.mark(2)
        val first = raw.read()
        val second = raw.read()
        raw.reset()
        return if (first == 0x1f && second == 0x8b) GZIPInputStream(raw) else raw
    }
}
