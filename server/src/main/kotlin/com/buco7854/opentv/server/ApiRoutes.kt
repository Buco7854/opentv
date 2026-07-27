package com.buco7854.opentv.server

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route

internal fun ApplicationCall.id(name: String = "id"): Long =
    parameters[name]?.toLongOrNull() ?: throw IllegalArgumentException("Bad id")

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
    providerKey = providerKey,
    connectionLimit = connectionLimit,
    ffmpegRunning = ffmpegRunning,
    durationSec = durationSec,
    lastLog = lastLog,
)

fun Route.api(
    graph: ServerGraph,
    security: ApiSecurity = ApiSecurity.authenticated(graph.auth, graph.authConfig),
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
        apiSecurityBoundary(security, graph.trustedProxies::clientIp) {
            authenticatedAuthRoutes(
                graph.auth,
                graph.webAuthn,
                graph.deviceLink,
                graph.authConfig,
                origins,
                graph.trustedProxies::clientIp,
            )
            adminAuthRoutes(graph.auth)
            playlistRoutes(graph.apiServices.playlists)
            libraryRoutes(graph.apiServices.library)
            downloadRoutes(graph.apiServices.downloads)
            sessionRoutes(graph.apiServices.sessions, graph.trustedProxies)
            mediaRoutes(graph.mediaApi)
        }
        unknownApiPaths()
    }

internal fun Route.unknownApiPaths() = route("{...}") {
    handle {
        call.respond(
            HttpStatusCode.NotFound,
            ApiErrorDto("not_found", "Unknown API endpoint"),
        )
    }
}
