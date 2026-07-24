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
internal suspend fun remuxSource(media: MediaRouteDependencies, call: ApplicationCall): String? {
    val downloadId = call.request.queryParameters["d"]
    val source = if (downloadId != null) {
        media.downloads.fileFor(call.actor.userId, downloadId)?.second?.toString()
    } else {
        call.request.queryParameters["u"]?.let(media.cipher::tryDecrypt)
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }
    if (source == null) {
        call.respond(
            HttpStatusCode.BadRequest,
            ApiErrorDto("invalid_target", "Invalid or missing target url"),
        )
    }
    return source
}

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
        publicAuthRoutes(
            graph.auth,
            graph.oidc,
            graph.webAuthn,
            graph.authConfig,
            graph.trustedProxies::clientIp,
        )
        route("") {
            apiSecurityBoundary(security, graph.trustedProxies::clientIp)
            authenticatedAuthRoutes(
                graph.auth,
                graph.webAuthn,
                graph.authConfig,
                graph.trustedProxies::clientIp,
            )
            adminAuthRoutes(graph.auth, graph.trustedProxies::clientIp)
            playlistRoutes(graph.apiServices.playlists)
            libraryRoutes(graph.apiServices.library)
            downloadRoutes(graph.apiServices.downloads)
            sessionRoutes(graph.apiServices.sessions, graph.trustedProxies)
            mediaRoutes(graph.mediaApi)
        }
    }
