package com.buco7854.opentv.server

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import java.net.URI

/** The relying party a WebAuthn ceremony runs under. */
internal data class WebAuthnRelyingParty(val rpId: String, val origin: String) {
    /**
     * Whether a browser will accept it. WebAuthn needs a secure context and a domain: an IP
     * literal is never a valid relying-party id, and plain HTTP is refused off localhost.
     * A ceremony offered under an unusable party fails inside the browser with nothing the
     * user can act on, so it is not offered at all.
     */
    val usable: Boolean = runCatching {
        val uri = URI(origin)
        val host = uri.host.orEmpty().removeSurrounding("[", "]")
        val loopback = host.equals("localhost", true) || host == "127.0.0.1" || host == "::1"
        (uri.scheme.equals("https", true) || loopback) && parseIpLiteral(host) == null
    }.getOrDefault(false)
}

/**
 * The address a browser used to reach this server, for the four things that must be
 * absolute: the OIDC callback, the device-link URL, WebAuthn's relying party, and the
 * session cookie's `Secure` flag.
 *
 * `OPENTV_PUBLIC_URL` wins whenever it is set. A proxied deployment needs one predictable
 * identity - the OIDC callback has to be registered at the provider, and a passkey belongs
 * to a single relying party - so a configured address is never second-guessed.
 *
 * When it is not set, these follow the request instead of the loopback default, which is
 * never where anyone browses: a LAN address, a container name or a published port then works
 * without configuring anything. `X-Forwarded-Proto` and `X-Forwarded-Host` are read only
 * when the peer is a configured trusted proxy, the same rule the client address already
 * follows; an untrusted peer cannot talk the server into minting addresses for another host.
 *
 * [trustsPeer] is [TrustedProxies.trustsPeer] in production - passed as a function for the
 * same reason `clientIp` is, so this class does not depend on how trust is decided.
 */
internal class PublicOrigin(
    private val config: AuthConfig,
    private val trustsPeer: (ApplicationCall) -> Boolean,
) {
    fun of(call: ApplicationCall): URI {
        if (config.publicUrlPinned) return config.publicUrl
        val trusted = trustsPeer(call)
        val host = forwarded(call, HttpHeaders.XForwardedHost, trusted)
            ?: call.request.headers[HttpHeaders.Host]
            ?: return config.publicUrl
        val scheme = forwarded(call, HttpHeaders.XForwardedProto, trusted)
            ?.lowercase()
            ?.takeIf { it == "http" || it == "https" }
            ?: call.request.origin.scheme
        return RequestOrigin.absolute(scheme, host) ?: config.publicUrl
    }

    /** Absolute URL of a path on the address this request came from. */
    fun url(call: ApplicationCall, path: String): URI = of(call).resolve(path)

    /**
     * Whether this request reached us over HTTPS, which decides the cookie's `Secure` flag
     * and whether HSTS is promised. Marking a cookie `Secure` on a plain-HTTP deployment
     * makes the browser drop it, which reads as "signing in does nothing".
     */
    fun secure(call: ApplicationCall): Boolean =
        if (config.publicUrlPinned) config.secureCookies else of(call).scheme.equals("https", true)

    fun webAuthn(call: ApplicationCall): WebAuthnRelyingParty {
        if (config.webAuthnPinned) {
            return WebAuthnRelyingParty(config.webAuthnRpId, config.webAuthnOrigin)
        }
        val origin = of(call)
        return WebAuthnRelyingParty(
            rpId = origin.host,
            origin = "${origin.scheme}://${origin.rawAuthority}",
        )
    }

    /** First entry of a comma-separated forwarded header, and only from a trusted peer. */
    private fun forwarded(call: ApplicationCall, header: String, trusted: Boolean): String? =
        if (!trusted) null
        else call.request.headers[header]
            ?.substringBefore(',')
            ?.trim()
            ?.takeIf(String::isNotEmpty)
}
