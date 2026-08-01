package com.buco7854.opentv.server

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.net.URI
import java.nio.file.Files

/**
 * The public authentication routes mounted the way production mounts them, over a real
 * user database. Several of their contracts - origins, cookies, redirects - only exist at
 * the HTTP boundary and cannot be observed from the services underneath.
 */
internal fun withPublicAuthServer(
    block: suspend ApplicationTestBuilder.(bootstrapToken: String) -> Unit,
) = testApplication {
    val persistence = ServerTestPersistence("opentv-public-auth")
    val dir = persistence.directory
    val db = persistence.database
    try {
        val config = publicAuthTestConfig()
        val auth = AuthService(db, config, dir)
        auth.initialize()
        val bootstrapToken = Files.readString(dir.resolve("bootstrap.token")).trim()
        application {
            install(ContentNegotiation) { json(Json { encodeDefaults = true }) }
            installOpenTvErrorResponses()
            routing {
                route("/api/v1") {
                    publicAuthRoutes(
                        auth,
                        OidcService(auth, config),
                        WebAuthnService(db, auth, config),
                        DeviceLinkService(db, auth, config),
                        config,
                        PublicOrigin(config) { false },
                    ) { "127.0.0.1" }
                }
            }
        }
        block(bootstrapToken)
    } finally {
        persistence.close()
    }
}

private fun publicAuthTestConfig() = AuthConfig(
    publicUrl = URI("http://localhost:8080"),
    passwordEnabled = true,
    encryptionKey = ByteArray(32) { it.toByte() },
    initialAdmin = null,
    mfaRequiredRoles = emptySet(),
    oidc = null,
    secureCookies = false,
    webAuthnRpId = "localhost",
    webAuthnOrigin = "http://localhost:8080",
    sessionIdleMs = 24 * 60 * 60_000L,
    sessionAbsoluteMs = 30L * 24 * 60 * 60_000L,
)
