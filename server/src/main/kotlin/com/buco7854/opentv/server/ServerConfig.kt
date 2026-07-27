package com.buco7854.opentv.server

import java.nio.file.Path
import java.net.URI
import java.util.Base64

/** Immutable process configuration. Mutable user preferences live in [ServerSettings]. */
data class ServerConfig(
    val port: Int,
    val dataDir: Path,
    val pageSize: Int,
    val fallbackProviderConnections: Int,
    val videoEncoder: String,
    val x264Preset: String,
    val trustedProxies: String?,
    val auth: AuthConfig,
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
                auth = AuthConfig.fromEnv(env),
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

data class InitialAdminConfig(val username: String, val password: String)

data class OidcConfig(
    val issuer: URI,
    val clientId: String,
    val clientSecret: String,
    val scopes: List<String>,
    val usernameClaim: String,
    val displayNameClaim: String,
    val groupsClaim: String,
    val adminGroups: Set<String>,
    val autoProvision: Boolean,
)

data class AuthConfig(
    /** `OPENTV_PUBLIC_URL`, or the loopback default when it was left unset. */
    val publicUrl: URI,
    /**
     * Whether [publicUrl] was configured.
     *
     * When it was not, the addresses that have to be absolute - the OIDC callback, the
     * device-link URL, the cookie's `Secure` flag - are derived from each request by
     * [PublicOrigin] instead of being pinned to a default nobody actually browses.
     */
    val publicUrlPinned: Boolean = true,
    val passwordEnabled: Boolean,
    val encryptionKey: ByteArray?,
    val initialAdmin: InitialAdminConfig?,
    val mfaRequiredRoles: Set<String>,
    val oidc: OidcConfig?,
    val secureCookies: Boolean,
    val webAuthnRpId: String,
    val webAuthnOrigin: String,
    /**
     * Whether the relying party above was pinned, by `OPENTV_WEBAUTHN_*` or through
     * [publicUrl]. When it was not, it too follows the requested address - a passkey then
     * belongs to the host it was enrolled on, which is what WebAuthn requires anyway.
     */
    val webAuthnPinned: Boolean = true,
    val sessionIdleMs: Long,
    val sessionAbsoluteMs: Long,
) {
    companion object {
        fun fromEnv(env: Map<String, String>): AuthConfig {
            val configuredPublicUrl = env["OPENTV_PUBLIC_URL"]?.trim()?.takeIf(String::isNotEmpty)
            val publicUrl = configuredPublicUrl ?: "http://localhost:8080"
            val uri = runCatching { URI(publicUrl) }
                .getOrElse { throw IllegalArgumentException("OPENTV_PUBLIC_URL must be an absolute URL") }
            require(
                uri.isAbsolute && uri.host != null &&
                    uri.userInfo == null && uri.query == null && uri.fragment == null &&
                    uri.path.orEmpty().let { it.isEmpty() || it == "/" }
            ) {
                "OPENTV_PUBLIC_URL must contain only scheme and authority"
            }
            val allowInsecure = env.boolean("OPENTV_ALLOW_INSECURE_HTTP", false)
            val loopback = uri.host.equals("localhost", true) ||
                uri.host == "127.0.0.1" || uri.host == "[::1]"
            require(uri.scheme == "https" || (uri.scheme == "http" && (loopback || allowInsecure))) {
                "OPENTV_PUBLIC_URL must use HTTPS (or explicitly allow insecure development HTTP)"
            }

            val passwordEnabled = env.boolean("OPENTV_PASSWORD_AUTH_ENABLED", true)
            val encryptionKey = env["OPENTV_AUTH_ENCRYPTION_KEY"]
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.let(::decodeEncryptionKey)
            if (passwordEnabled) {
                require(encryptionKey != null) {
                    "OPENTV_AUTH_ENCRYPTION_KEY is required when password authentication is enabled"
                }
            }

            val initialUsername = env["OPENTV_INITIAL_ADMIN_USERNAME"]?.trim()?.takeIf(String::isNotEmpty)
            val initialPassword = env["OPENTV_INITIAL_ADMIN_PASSWORD"]?.takeIf(String::isNotEmpty)
            require((initialUsername == null) == (initialPassword == null)) {
                "OPENTV_INITIAL_ADMIN_USERNAME and OPENTV_INITIAL_ADMIN_PASSWORD must be set together"
            }
            require(passwordEnabled || initialUsername == null) {
                "Initial password admin cannot be configured when password authentication is disabled"
            }

            val issuer = env["OPENTV_OIDC_ISSUER"]?.trim()?.takeIf(String::isNotEmpty)
            val clientId = env["OPENTV_OIDC_CLIENT_ID"]?.trim()?.takeIf(String::isNotEmpty)
            val clientSecret = env["OPENTV_OIDC_CLIENT_SECRET"]?.takeIf(String::isNotEmpty)
            val oidcValues = listOf(issuer, clientId, clientSecret)
            val anyOidcSetting = env.keys.any { it.startsWith("OPENTV_OIDC_") }
            require(oidcValues.all { it == null } || oidcValues.all { it != null }) {
                "OPENTV_OIDC_ISSUER, OPENTV_OIDC_CLIENT_ID and OPENTV_OIDC_CLIENT_SECRET must be set together"
            }
            require(!anyOidcSetting || oidcValues.all { it != null }) {
                "Optional OPENTV_OIDC_* settings require a complete OIDC configuration"
            }
            val oidc = issuer?.let {
                val issuerUri = runCatching { URI(it) }
                    .getOrElse { throw IllegalArgumentException("OPENTV_OIDC_ISSUER must be a URL") }
                require(
                    issuerUri.isAbsolute && issuerUri.scheme == "https" &&
                        issuerUri.host != null && issuerUri.userInfo == null &&
                        issuerUri.query == null && issuerUri.fragment == null
                ) {
                    "OPENTV_OIDC_ISSUER must be an absolute HTTPS URL without user info, query, or fragment"
                }
                require(requireNotNull(clientId).length <= 512) { "OPENTV_OIDC_CLIENT_ID is too long" }
                require(requireNotNull(clientSecret).length <= 4_096) { "OPENTV_OIDC_CLIENT_SECRET is too long" }
                val usernameClaim = env.nonBlank("OPENTV_OIDC_USERNAME_CLAIM") ?: "preferred_username"
                val displayNameClaim = env.nonBlank("OPENTV_OIDC_DISPLAY_NAME_CLAIM") ?: "name"
                val groupsClaim = env.nonBlank("OPENTV_OIDC_GROUPS_CLAIM") ?: "groups"
                require(listOf(usernameClaim, displayNameClaim, groupsClaim).all { it.length <= 128 }) {
                    "OIDC claim names must be at most 128 characters"
                }
                val adminGroups = env.csv("OPENTV_OIDC_ADMIN_GROUPS")
                require(adminGroups.size <= 100 && adminGroups.all { group -> group.length <= 256 }) {
                    "OPENTV_OIDC_ADMIN_GROUPS is too large"
                }
                OidcConfig(
                    issuer = issuerUri,
                    clientId = clientId,
                    clientSecret = clientSecret,
                    scopes = env["OPENTV_OIDC_SCOPES"]?.split(Regex("\\s+"))
                        ?.filter(String::isNotBlank) ?: listOf("openid", "profile", "email"),
                    usernameClaim = usernameClaim,
                    displayNameClaim = displayNameClaim,
                    groupsClaim = groupsClaim,
                    adminGroups = adminGroups,
                    autoProvision = env.boolean("OPENTV_OIDC_AUTO_PROVISION", false),
                )
            }
            require(passwordEnabled || oidc != null) {
                "OIDC must be configured when password authentication is disabled"
            }

            val requiredRoles = (env.csv("OPENTV_MFA_REQUIRED_ROLES")
                .ifEmpty { setOf("USER", "ADMIN") })
                .map(String::uppercase).toSet()
            require(requiredRoles.all { it == "USER" || it == "ADMIN" }) {
                "OPENTV_MFA_REQUIRED_ROLES accepts only USER and ADMIN"
            }

            val configuredRpId = env.nonBlank("OPENTV_WEBAUTHN_RP_ID")
            val configuredWebAuthnOrigin = env.nonBlank("OPENTV_WEBAUTHN_ORIGIN")
            val rpId = configuredRpId ?: requireNotNull(uri.host)
            val origin = configuredWebAuthnOrigin ?: "${uri.scheme}://${uri.rawAuthority}"
            val originUri = runCatching { URI(origin) }
                .getOrElse { throw IllegalArgumentException("OPENTV_WEBAUTHN_ORIGIN must be a URL origin") }
            val webAuthnLocalhost = originUri.host.equals("localhost", true)
            require(originUri.isAbsolute && originUri.host != null && originUri.userInfo == null &&
                originUri.path.orEmpty().let { it.isEmpty() || it == "/" } &&
                originUri.query == null && originUri.fragment == null
            ) { "OPENTV_WEBAUTHN_ORIGIN must contain only scheme and authority" }
            require(originUri.scheme == "https" || (originUri.scheme == "http" && webAuthnLocalhost)) {
                "WebAuthn requires HTTPS, except on localhost"
            }
            require(originUri.host.equals(rpId, true) ||
                originUri.host.lowercase().endsWith(".${rpId.lowercase()}")
            ) { "OPENTV_WEBAUTHN_RP_ID must be the WebAuthn origin host or a registrable suffix" }
            return AuthConfig(
                publicUrl = uri,
                publicUrlPinned = configuredPublicUrl != null,
                passwordEnabled = passwordEnabled,
                encryptionKey = encryptionKey,
                initialAdmin = initialUsername?.let { InitialAdminConfig(it, requireNotNull(initialPassword)) },
                mfaRequiredRoles = requiredRoles,
                oidc = oidc,
                secureCookies = uri.scheme == "https",
                webAuthnRpId = rpId,
                webAuthnOrigin = origin,
                webAuthnPinned = configuredPublicUrl != null ||
                    configuredRpId != null || configuredWebAuthnOrigin != null,
                sessionIdleMs = env.long("OPENTV_SESSION_IDLE_HOURS", 24, 1L..720L) * 60 * 60 * 1000,
                sessionAbsoluteMs = env.long("OPENTV_SESSION_ABSOLUTE_DAYS", 30, 1L..365L) * 24 * 60 * 60 * 1000,
            )
        }

        private fun Map<String, String>.nonBlank(name: String) =
            this[name]?.trim()?.takeIf(String::isNotEmpty)

        private fun decodeEncryptionKey(value: String): ByteArray =
            runCatching {
                val decoded = if (HEX_ENCRYPTION_KEY.matches(value)) {
                    value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                } else {
                    Base64.getDecoder().decode(value)
                }
                require(decoded.size == 32)
                decoded
            }.getOrElse {
                throw IllegalArgumentException(ENCRYPTION_KEY_FORMAT_ERROR)
            }

        private fun Map<String, String>.csv(name: String): Set<String> =
            this[name]?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.toSet().orEmpty()

        private fun Map<String, String>.boolean(name: String, default: Boolean): Boolean =
            when (val raw = this[name]?.trim()?.lowercase()) {
                null -> default
                "true", "1", "yes" -> true
                "false", "0", "no" -> false
                else -> throw IllegalArgumentException("$name must be true or false")
            }

        private val HEX_ENCRYPTION_KEY = Regex("[0-9a-fA-F]{64}")
        private const val ENCRYPTION_KEY_FORMAT_ERROR =
            "OPENTV_AUTH_ENCRYPTION_KEY must be a 32-byte key encoded as base64 or 64-character hex"

        private fun Map<String, String>.long(name: String, default: Long, range: LongRange): Long {
            val value = this[name]?.toLongOrNull() ?: if (name in this) {
                throw IllegalArgumentException("$name must be an integer")
            } else default
            require(value in range) { "$name must be in ${range.first}..${range.last}" }
            return value
        }
    }
}
