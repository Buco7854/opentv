package com.buco7854.opentv.core.net

/**
 * Seam over each platform's HTTP stack: GET the url as text, throw on non-2xx,
 * and handle own threading.
 */
fun interface HttpFetcher {
    suspend fun getText(url: String): String
}
