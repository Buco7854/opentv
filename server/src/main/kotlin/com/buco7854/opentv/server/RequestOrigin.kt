package com.buco7854.opentv.server

import java.net.URI

/**
 * Decides whether a state-changing request came from this server's own web client.
 *
 * The configured public URL is a deployment hint, not the only address a browser may
 * legitimately use. A first-run visitor reaches a fresh server on a LAN address, a
 * container name or the dev server's port long before `OPENTV_PUBLIC_URL` is set, and
 * comparing `Origin` against that setting alone rejected the very first request anyone
 * makes - creating the first administrator.
 *
 * What actually defends the API is that a cross-site page can forge neither the `Origin`
 * header nor the `Host` header of a request the browser sends here: the browser sets both
 * itself. An `Origin` naming the same authority the request was addressed to is therefore
 * same-origin by definition, whatever that authority happens to be. The configured public
 * URL stays accepted as well, because a reverse proxy may rewrite `Host` to its upstream.
 *
 * The scheme is only compared against the configured URL. Behind a TLS-terminating proxy
 * the request arrives over plain HTTP while the browser reports `https://`, so the `Host`
 * comparison is authority-only; exploiting that would mean serving the same hostname over
 * HTTP, which is already a man-in-the-middle.
 */
internal object RequestOrigin {

    fun isSameOrigin(origin: String?, host: String?, publicUrl: URI): Boolean {
        val requested = parseOrigin(origin) ?: return false
        if (requested == endpointOf(publicUrl.scheme, publicUrl.host, publicUrl.port)) return true
        val addressed = parseHost(host, requested.scheme) ?: return false
        return requested.host == addressed.host && requested.port == addressed.port
    }

    /** The origin `OPENTV_PUBLIC_URL` describes, for operator-facing messages. */
    fun expected(publicUrl: URI): String = "${publicUrl.scheme}://${publicUrl.rawAuthority}"

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

    /** `Origin: scheme://host[:port]`, or null for anything else - including `null` and no header. */
    private fun parseOrigin(value: String?): Endpoint? {
        val text = value?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: return null
        return parseEndpoint(text)
    }

    /** `Host: host[:port]`, read under the scheme the browser reported. */
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
