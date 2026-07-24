package com.buco7854.opentv.server

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun Route.mediaRoutes(media: MediaRouteDependencies) {
    get("/stream") { media.proxy.handle(call, sid = call.request.queryParameters["sid"]) }
    get("/img") { media.proxy.handle(call, cache = true) }

    get("/relay") {
        val url = call.request.queryParameters["u"]?.let { media.cipher.tryDecrypt(it) }
        if (url.isNullOrBlank() || !url.startsWith("http")) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorDto("invalid_target", "Invalid or missing target url"))
            return@get
        }
        val sessionId = call.request.queryParameters["sid"].orEmpty()
        val group = media.sessions.shareGroup(sessionId)
        (media.sessions.roomMembers(sessionId) + sessionId).forEach { media.streamGate.release(it) }
        media.liveRelay.stream(
            call,
            url,
            group,
            providerKeyOf(url),
            media.connectionLimit(url),
            sessionId,
        )
    }

    get("/transcode") {
        if (!media.remux.available) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                ApiErrorDto("media_unavailable", "ffmpeg is not installed on the server"),
            )
            return@get
        }
        val url = call.request.queryParameters["u"]?.let { media.cipher.tryDecrypt(it) }
        if (url.isNullOrBlank() || !(url.startsWith("http://") || url.startsWith("https://"))) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorDto("invalid_target", "Invalid or missing target url"))
            return@get
        }
        val sessionId = call.request.queryParameters["sid"]
        if (sessionId != null &&
            !media.streamGate.admit(sessionId, providerKeyOf(url), media.connectionLimit(url))
        ) {
            call.respond(
                HttpStatusCode.TooManyRequests,
                ApiErrorDto("provider_capacity", "Provider connection limit reached"),
            )
            return@get
        }
        media.transcoder.stream(url, call)
    }

    get("/remux/available") { call.respond(RemuxAvailableDto(media.remux.available)) }
    get("/remux/start") {
        if (!media.remux.available) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                ApiErrorDto("media_unavailable", "ffmpeg is not installed on the server"),
            )
            return@get
        }
        val source = remuxSource(media, call) ?: return@get
        val requestedAudio = call.request.queryParameters["audio"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val force = call.request.queryParameters["force"] == "1"
        val sessionId = call.request.queryParameters["sid"].orEmpty()
        val group = media.sessions.shareGroup(sessionId)
        val audio = media.sessions.roomAudio(sessionId) ?: requestedAudio
        val clientHevc = media.sessions.roomHevc(
            sessionId,
            call.request.queryParameters["hevc"] == "1",
        )
        val supersededGroups = media.sessions.roomMembers(sessionId) + sessionId + group
        try {
            val result = withContext(Dispatchers.IO) {
                media.remux.start(
                    source,
                    audio,
                    clientHevc,
                    force,
                    media.connectionLimit(source),
                    group,
                    supersededGroups,
                )
            }
            call.respond(
                RemuxStartDto(
                    result.id,
                    result.playlistUrl,
                    result.durationSec,
                    result.audioTracks,
                    result.subtitleTracks,
                    result.nativeVideoCopy,
                    audio,
                )
            )
        } catch (e: RemuxService.NoExtraTracksException) {
            call.respond(HttpStatusCode.NotFound, ApiErrorDto("no_extra_tracks", e.message ?: "No extra tracks"))
        } catch (e: RemuxService.ConnectionLimitException) {
            call.respond(
                HttpStatusCode.TooManyRequests,
                ApiErrorDto("provider_capacity", e.message ?: "Connection limit reached"),
            )
        } catch (e: IllegalStateException) {
            call.respond(HttpStatusCode.BadGateway, ApiErrorDto("remux_failed", e.message ?: "Remux failed"))
        }
    }
    delete("/remux/{id}") {
        media.remux.stop(call.requiredParameter("id"))
        call.respond(HttpStatusCode.NoContent)
    }
    get("/remux/{id}/{file}") {
        val id = call.requiredParameter("id")
        val file = call.requiredParameter("file")
        when {
            file == "master.m3u8" -> media.remux.master(id, call)
            file == "main.m3u8" -> media.remux.playlist(id, call)
            file == "init.mp4" -> media.remux.initSegment(id, call)
            file.startsWith("main") && (file.endsWith(".m4s") || file.endsWith(".ts")) ->
                file.removePrefix("main").substringBefore('.').toIntOrNull()
                    ?.let { media.remux.segment(id, it, call) }
                    ?: call.respond(HttpStatusCode.NotFound, ApiErrorDto("not_found", "Unknown segment"))
            file.startsWith("sub_") && file.endsWith(".m3u8") ->
                file.removePrefix("sub_").removeSuffix(".m3u8").toIntOrNull()
                    ?.let { media.remux.subtitlePlaylist(id, it, call) }
                    ?: call.respond(HttpStatusCode.NotFound, ApiErrorDto("not_found", "Unknown subtitle"))
            file.startsWith("sub_") && file.endsWith(".vtt") -> {
                val parts = file.removePrefix("sub_").removeSuffix(".vtt").split('_')
                val subtitle = parts.getOrNull(0)?.toIntOrNull()
                val segment = parts.getOrNull(1)?.toIntOrNull()
                if (subtitle != null && segment != null) {
                    media.remux.subtitleSegment(id, subtitle, segment, call)
                } else {
                    call.respond(HttpStatusCode.NotFound, ApiErrorDto("not_found", "Unknown subtitle"))
                }
            }
            else -> call.respond(HttpStatusCode.NotFound, ApiErrorDto("not_found", "Unknown remux file"))
        }
    }
}
