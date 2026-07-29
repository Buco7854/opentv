package com.buco7854.opentv.server

import java.net.URI

/** Strict parsing for request-derived public URLs. */
internal object RequestOrigin {
    /**
     * `scheme://host[:port]` as an absolute URI, or null when either part is unusable.
     * Shared with [PublicOrigin] so one parser decides what an origin may look like.
     */
    fun absolute(scheme: String, host: String): URI? {
        val endpoint = parseHost(host, scheme) ?: return null
        return runCatching { URI("${endpoint.scheme}://$host") }.getOrNull()
            ?.takeIf { it.host != null }
    }

    private data class Endpoint(val scheme: String, val host: String, val port: Int)

    /** `Host: host[:port]`, read under the trusted scheme. */
    private fun parseHost(value: String?, scheme: String): Endpoint? {
        val text = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        if ("/" in text) return null
        return parseEndpoint("$scheme://$text")
    }

    private fun parseEndpoint(text: String): Endpoint? {
        val uri = runCatching { URI(text) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()?.takeIf { it == "http" || it == "https" } ?: return null
        val host = uri.host?.takeIf(String::isNotEmpty) ?: return null
        if (uri.userInfo != null || uri.query != null || uri.fragment != null) return null
        if (!uri.path.isNullOrEmpty()) return null
        return endpointOf(scheme, host, uri.port)
    }

    private fun endpointOf(scheme: String, host: String?, port: Int): Endpoint? {
        val safeScheme = scheme.lowercase()
        return Endpoint(
            scheme = safeScheme,
            host = (host ?: return null).lowercase(),
            port = if (port != -1) port else if (safeScheme == "https") 443 else 80,
        )
    }
}
