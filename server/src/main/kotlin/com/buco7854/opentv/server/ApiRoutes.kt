package com.buco7854.opentv.server

import com.buco7854.opentv.contract.*
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

private val PROVIDER_ID = Regex("""[1-9][0-9]*""")

internal fun ApplicationCall.id(name: String = "id"): Long =
    parameters[name]?.toLongOrNull() ?: throw IllegalArgumentException("Bad id")

internal fun ApplicationCall.providerId(name: String): Long {
    val raw = parameters[name]
        ?.takeIf(PROVIDER_ID::matches)
        ?: throw IllegalArgumentException("Bad provider id")
    return raw.toLongOrNull()?.takeIf { it.toString() == raw }
        ?: throw IllegalArgumentException("Bad provider id")
}

internal fun ApplicationCall.requiredParameter(name: String): String =
    parameters[name] ?: throw IllegalArgumentException("Missing $name")

/** Accepts only an opaque provider token. Download playback is resolved by an owned lease. */
internal fun RemuxService.RemuxDiagnostics.toDto() = RemuxDiagDto(
    videoCodec = videoCodec,
    transcodeVideo = transcodeVideo,
    videoEncoder = videoEncoder,
    nativeVideoCopy = nativeVideoCopy,
    audioCodec = audioCodec,
    audioChannels = audioChannels,
    audioLabel = audioLabel,
    subtitleCount = subtitleCount,
    segmentCount = segmentCount,
    timeshift = timeshift,
    // URI.authority may contain user-info. Diagnostics need only a provider label,
    // never credentials.
    providerKey = providerKey.substringAfterLast('@'),
    connectionLimit = connectionLimit,
    ffmpegRunning = ffmpegRunning,
    durationSec = durationSec,
    lastLog = lastLog,
)

fun Route.api(
    graph: ServerGraph,
    security: ApiSecurity = ApiSecurity.authenticated(graph.auth, graph.cipher),
) =
    route("/api/v1") {
        // Derived from configuration alone, so an instance here and the one the security
        // headers use answer identically.
        val origins = PublicOrigin(graph.authConfig, graph.trustedProxies::trustsPeer)
        publicAuthRoutes(
            graph.auth,
            graph.oidc,
            graph.webAuthn,
            graph.deviceLink,
            graph.authConfig,
            origins,
            graph.trustedProxies::clientIp,
        )
        serverInfoRoutes()
        downloadFileRoutes(graph.apiServices.downloads)
        mediaRoutes(graph.mediaApi)
        apiSecurityBoundary(security, graph.trustedProxies::clientIp) {
            authenticatedAuthRoutes(
                graph.auth,
                graph.webAuthn,
                graph.deviceLink,
                origins,
                graph.trustedProxies::clientIp,
            )
            adminAuthRoutes(graph.auth)
            playlistRoutes(graph.apiServices.playlists)
            favoriteRoutes(graph.apiServices.playlists)
            libraryRoutes(graph.apiServices.library)
            downloadRoutes(graph.apiServices.downloads)
            sessionRoutes(graph.apiServices.sessions, graph.trustedProxies)
        }
        unknownApiPaths()
    }

internal fun Route.serverInfoRoutes(
    version: String = ServerInfoDto::class.java.`package`.implementationVersion ?: "dev",
) {
    get("/server-info") {
        call.respond(ServerInfoDto(version = version))
    }
}

internal fun Route.unknownApiPaths() = route("{...}") {
    handle {
        call.respond(
            HttpStatusCode.NotFound,
            ApiErrorDto("not_found", "Unknown API endpoint"),
        )
    }
}
