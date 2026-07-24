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

private val DOWNLOAD_FILE_URL = Regex("""/api/v1/downloads/(\d+)/file""")

/** Accepts only an opaque provider token or this server's own completed download. */
internal suspend fun remuxSource(media: MediaRouteDependencies, call: ApplicationCall): String? {
    val raw = call.request.queryParameters["u"]
        ?: throw IllegalArgumentException("Missing target url")
    val decoded = media.cipher.tryDecrypt(raw)
    val downloadId = DOWNLOAD_FILE_URL.find(decoded ?: raw)
        ?.groupValues?.get(1)?.toLongOrNull()
    val source = downloadId?.let { media.downloads.fileFor(it)?.second?.toString() }
        ?: decoded?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
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

fun Route.api(graph: ServerGraph, security: ApiSecurity = ApiSecurity.openAccess()) =
    route("/api/v1") {
        apiSecurityBoundary(security, graph.trustedProxies::clientIp)
        playlistRoutes(graph.apiServices.playlists)
        libraryRoutes(graph.apiServices.library)
        downloadRoutes(graph.apiServices.downloads)
        sessionRoutes(graph.apiServices.sessions, graph.trustedProxies)
        mediaRoutes(graph.mediaApi)
    }
