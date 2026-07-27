package com.buco7854.opentv.core.net

import com.buco7854.opentv.core.epg.TextSource

/** Streaming text body of a fetched playlist/EPG file. Close when done. */
interface TextBody {
    fun lines(): Sequence<String>
    fun chars(): TextSource
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
