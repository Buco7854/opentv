package com.buco7854.opentv.core.net

import com.buco7854.opentv.core.epg.TextSource

/**
 * Streaming text body of a fetched playlist/EPG file.
 *
 * Implementations run [readLines] and [readChars] on their blocking-I/O
 * context and tie cancellation to the underlying exchange. The callback is
 * suspending so parsing and batched storage writes stay inside that context
 * without buffering the whole body.
 */
interface TextBody {
    suspend fun <T> readLines(block: suspend (Sequence<String>) -> T): T
    suspend fun <T> readChars(block: suspend (TextSource) -> T): T
    fun close()
}

sealed class ConditionalFetch {
    /** Cached copy still current; no body transferred. */
    object NotModified : ConditionalFetch()
    class Success(val body: TextBody, val etag: String?, val lastModified: String?) : ConditionalFetch()
}

/**
 * Conditional GET (ETag / If-Modified-Since) for big text downloads, so
 * unchanged files cost a 304. Implementations handle gzip and their own threading.
 */
fun interface ConditionalFetcher {
    suspend fun conditionalGet(url: String, etag: String?, lastModified: String?): ConditionalFetch
}
