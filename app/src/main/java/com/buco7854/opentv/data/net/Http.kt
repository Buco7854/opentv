package com.buco7854.opentv.data.net

import android.content.Context
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
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
            throw IOException("HTTP $code for $url")
        }
        return FetchResult.Success(response, response.header("ETag"), response.header("Last-Modified"))
    }

    /** Unwraps gzip for servers (and .gz files) that return compressed bodies. */
    fun bodyStream(response: Response, url: String): InputStream {
        val raw = response.body!!.byteStream()
        val gzipped = response.header("Content-Encoding").equals("gzip", ignoreCase = true) ||
            url.substringBefore('?').endsWith(".gz", ignoreCase = true)
        return if (gzipped) {
            try {
                GZIPInputStream(raw)
            } catch (_: IOException) {
                raw // OkHttp may have already transparently decompressed it
            }
        } else raw
    }
}
