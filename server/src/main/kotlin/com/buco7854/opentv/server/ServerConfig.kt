package com.buco7854.opentv.server

import java.nio.file.Path

/** Immutable process configuration. Mutable user preferences live in [ServerSettings]. */
data class ServerConfig(
    val port: Int,
    val dataDir: Path,
    val pageSize: Int,
    val fallbackProviderConnections: Int,
    val videoEncoder: String,
    val x264Preset: String,
    val trustedProxies: String?,
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): ServerConfig {
            if ("OPENTV_REMUX_CONNECTIONS" in env && "OPENTV_PROVIDER_CONNECTIONS" !in env) {
                throw IllegalArgumentException(
                    "OPENTV_REMUX_CONNECTIONS was renamed to OPENTV_PROVIDER_CONNECTIONS"
                )
            }
            return ServerConfig(
                port = env.int("PORT", 8080, 1..65_535),
                dataDir = Path.of(env["OPENTV_DATA"] ?: "./data").toAbsolutePath().normalize(),
                pageSize = env.int("OPENTV_PAGE_SIZE", 50, 10..1_000),
                fallbackProviderConnections = env.int("OPENTV_PROVIDER_CONNECTIONS", 1, 1..1_000),
                videoEncoder = env["OPENTV_VIDEO_ENCODER"]?.trim()?.takeIf { it.isNotEmpty() } ?: "libx264",
                x264Preset = env["OPENTV_X264_PRESET"]?.trim()?.takeIf { it.isNotEmpty() } ?: "veryfast",
                trustedProxies = env["OPENTV_TRUSTED_PROXIES"]?.trim()?.takeIf { it.isNotEmpty() },
            )
        }

        private fun Map<String, String>.int(name: String, default: Int, range: IntRange): Int {
            val raw = this[name] ?: return default
            val value = raw.toIntOrNull()
                ?: throw IllegalArgumentException("$name must be an integer")
            require(value in range) { "$name must be in ${range.first}..${range.last}" }
            return value
        }
    }
}
