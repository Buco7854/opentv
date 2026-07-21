package com.buco7854.opentv.server

import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import java.net.InetAddress

/**
 * Resolves the real client IP for the (unauthenticated) session dashboard, which
 * identifies viewers by address. The direct peer is only trusted to speak for the
 * client when it is a configured reverse proxy; otherwise `X-Forwarded-For` is
 * attacker-controlled and ignored.
 *
 * OPENTV_TRUSTED_PROXIES is a comma-separated list of IPs and CIDRs (IPv4 or IPv6),
 * e.g. `127.0.0.1,10.0.0.0/8,::1`. When the peer matches, the rightmost XFF entry
 * that is not itself a trusted proxy is taken as the client.
 */
class TrustedProxies(private val ranges: List<IpRange>) {

    fun clientIp(call: ApplicationCall): String {
        val peer = call.request.origin.remoteAddress
        if (!isTrusted(peer)) return peer
        val forwarded = call.request.headers["X-Forwarded-For"]
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: return call.request.headers["X-Real-IP"]?.trim()?.takeIf { it.isNotEmpty() } ?: peer
        // Walk right-to-left past our own trusted proxies to the first untrusted hop.
        return forwarded.lastOrNull { !isTrusted(it) } ?: forwarded.firstOrNull() ?: peer
    }

    private fun isTrusted(ip: String): Boolean {
        val addr = parse(ip) ?: return false
        return ranges.any { it.contains(addr) }
    }

    companion object {
        fun fromEnv(): TrustedProxies =
            fromSpec(System.getenv("OPENTV_TRUSTED_PROXIES").orEmpty())

        fun fromSpec(spec: String): TrustedProxies =
            TrustedProxies(spec.split(',').mapNotNull { IpRange.parse(it.trim()) })

        private fun parse(ip: String): ByteArray? =
            runCatching { InetAddress.getByName(ip.substringBefore('%')).address }.getOrNull()
    }
}

/** A single trusted IP (as a /32 or /128) or CIDR block. */
class IpRange private constructor(private val network: ByteArray, private val prefixBits: Int) {

    fun contains(addr: ByteArray): Boolean {
        if (addr.size != network.size) return false
        var bits = prefixBits
        for (i in addr.indices) {
            if (bits <= 0) break
            val mask = if (bits >= 8) 0xFF else (0xFF shl (8 - bits)) and 0xFF
            if ((addr[i].toInt() and mask) != (network[i].toInt() and mask)) return false
            bits -= 8
        }
        return true
    }

    companion object {
        fun parse(entry: String): IpRange? {
            if (entry.isEmpty()) return null
            val slash = entry.indexOf('/')
            val host = if (slash >= 0) entry.substring(0, slash) else entry
            val addr = runCatching { InetAddress.getByName(host).address }.getOrNull() ?: return null
            val prefix = if (slash >= 0) entry.substring(slash + 1).toIntOrNull() ?: return null else addr.size * 8
            if (prefix < 0 || prefix > addr.size * 8) return null
            return IpRange(addr, prefix)
        }
    }
}
