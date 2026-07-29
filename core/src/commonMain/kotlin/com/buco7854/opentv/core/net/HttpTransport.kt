package com.buco7854.opentv.core.net

/**
 * Full-request seam over each platform's HTTP stack, for API clients that need
 * methods, headers, bodies and status codes ([HttpFetcher] deliberately offers
 * none of those). Implementations throw only on transport failure (unreachable,
 * timeout, TLS); every HTTP status — including 4xx/5xx — comes back as a
 * [HttpResponseSpec] so callers can map error semantics themselves.
 */
data class HttpRequestSpec(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val contentType: String? = null,
)

data class HttpResponseSpec(
    val status: Int,
    val headers: Map<String, List<String>>,
    val bodyText: String,
) {
    /** First value of [name], compared case-insensitively per RFC 9110. */
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value?.firstOrNull()

    val isSuccess: Boolean get() = status in 200..299
}

fun interface HttpTransport {
    suspend fun execute(request: HttpRequestSpec): HttpResponseSpec
}
