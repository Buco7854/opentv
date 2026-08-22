package com.buco7854.opentv.core.net

/** Identifies keyless metadata requests to public services that require a contactable UA. */
const val OPENTV_METADATA_USER_AGENT =
    "OpenTV-Metadata/1.0 (+https://github.com/Buco7854/opentv)"

/**
 * Seam over each platform's HTTP stack: GET the url as text, throw on non-2xx,
 * and handle own threading.
 */
fun interface HttpFetcher {
    suspend fun getText(url: String): String
}
