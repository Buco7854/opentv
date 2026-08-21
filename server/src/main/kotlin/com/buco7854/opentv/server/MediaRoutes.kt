package com.buco7854.opentv.server

import com.buco7854.opentv.contract.*
import com.buco7854.opentv.core.log.ProviderSecrets
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun Route.mediaRoutes(media: MediaRouteDependencies) {
    get("/stream") {
        val authorized = authorizedStream(media, call)
        media.proxy.handle(call, authorized.capability, authorized.grant, authorized.guard)
    }
    get("/shared-hls") {
        val authorized = authorizedStream(media, call, PlaybackMediaTransport.SHARED_HLS)
        val leaseId = authorized.capability.leaseId
        val group = media.sessions.shareGroup(leaseId)
        if (group == leaseId || !authorized.capability.hlsResource) {
            call.respond(
                HttpStatusCode.BadRequest,
                ApiErrorDto("hls_sharing_unavailable", "Shared HLS is unavailable for this playback"),
            )
            return@get
        }
        val members = media.sessions.roomMembers(leaseId)
        media.proxy.beginSharedRead(members)
        media.proxy.handleSharedHls(
            call = call,
            capability = authorized.capability,
            mediaGrant = authorized.grant,
            group = group,
            leaseGuard = authorized.guard,
            membershipGuard = {
                if (!media.sessions.isShareGroupMember(leaseId, group)) {
                    throw PlaybackRevokedException()
                }
            },
            groupStillActive = { media.sessions.hasShareGroup(group) },
        )
    }
    get("/img") {
        val capability = call.request.queryParameters["u"]
            ?.let(media.cipher::tryDecryptImage)
            ?: throw IllegalArgumentException("Invalid or missing image capability")
        media.proxy.image(call, capability.url)
    }

    get("/relay") {
        val (capability, _, guard) = authorizedStream(media, call, PlaybackMediaTransport.RELAY)
        val sessionId = capability.leaseId
        val url = capability.url
        val group = media.sessions.shareGroup(sessionId)
        val capabilities = media.sessions.roomCapabilities(sessionId)
        media.proxy.beginSharedRead(media.sessions.roomMembers(sessionId) + sessionId)
        media.liveRelay.stream(
            call,
            url,
            group,
            providerKeyOf(url),
            media.connectionLimit(url),
            sessionId,
            capabilities,
            guard,
        )
    }

    get("/transcode") {
        if (!requireFfmpeg(media, call)) return@get
        val (capability, _, guard) = authorizedStream(media, call)
        val sessionId = capability.leaseId
        val gateId = transcodeGateId(sessionId)
        val url = capability.url
        if (!media.streamGate.admit(gateId, providerKeyOf(url), media.connectionLimit(url))) {
            call.respond(
                HttpStatusCode.TooManyRequests,
                ApiErrorDto("provider_capacity", "Provider connection limit reached"),
            )
            return@get
        }
        media.transcoder.stream(
            url,
            call,
            sessionId,
            {
                guard()
                media.streamGate.touch(gateId)
            },
        )
    }

    get("/remux/available") {
        call.respond(RemuxAvailableDto(withContext(Dispatchers.IO) { media.remux.available }))
    }
    post("/remux/start") {
        if (!requireFfmpeg(media, call)) return@post
        val target = remuxTarget(media, call)
        val source = target.source
        val sessionId = target.leaseId
        val startupStartedNs = System.nanoTime()
        val requestedAudio = call.request.queryParameters["audio"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val timeshift = call.request.queryParameters["timeshift"] == "1"
        val rawGrant = call.request.queryParameters["g"]
        val group = media.sessions.shareGroup(sessionId)
        val audio = media.sessions.roomAudio(sessionId) ?: requestedAudio
        val capabilities = media.sessions.roomCapabilities(sessionId)
        val supersededGroups = media.sessions.roomMembers(sessionId) + sessionId + group
        try {
            val result = withLeaseKeepAlive(target.guard) {
                val connectionLimitStartedNs = System.nanoTime()
                val connectionLimit = media.connectionLimit(source)
                val connectionLimitMs = (System.nanoTime() - connectionLimitStartedNs) / 1_000_000
                val prepared = withContext(Dispatchers.IO) {
                    media.remux.start(
                        source,
                        audio,
                        capabilities,
                        timeshift,
                        connectionLimit,
                        group,
                        supersededGroups,
                        startupStartedNs,
                        connectionLimitMs,
                    )
                }
                try {
                    media.sessions.withLiveLease(sessionId) {
                        media.mediaGrants.bindResource(sessionId, prepared.id)
                    }
                } catch (error: Exception) {
                    if (!media.mediaGrants.hasAttachments(prepared.id)) media.remux.stop(prepared.id)
                    throw error
                }
                prepared
            }
            val suffix = "?sid=${urlEncode(sessionId)}&g=${urlEncode(requireNotNull(rawGrant))}"
            call.respond(
                RemuxStartDto(
                    result.id,
                    result.playlistUrl + suffix,
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            call.respond(
                HttpStatusCode.BadGateway,
                ApiErrorDto("remux_failed", ProviderSecrets.redact(e)),
            )
        }
    }
    delete("/remux/{id}") {
        val id = call.requiredParameter("id")
        val leaseId = call.request.queryParameters["sid"]
        val finalAttachment = media.mediaGrants.releaseResource(
            leaseId,
            call.request.queryParameters["g"],
            id,
        )
        if (finalAttachment) media.remux.stop(id)
        call.respond(HttpStatusCode.NoContent)
    }
    get("/remux/{id}/{file}") {
        val id = call.requiredParameter("id")
        val leaseId = call.request.queryParameters["sid"]
        media.mediaGrants.validateResource(
            leaseId,
            call.request.queryParameters["g"],
            id,
        )
        media.sessions.touch(requireNotNull(leaseId))
        val file = call.requiredParameter("file")
        when {
            file == "master.m3u8" -> media.remux.master(id, call, mediaQuery(call))
            file == "main.m3u8" -> media.remux.playlist(id, call, mediaQuery(call))
            file == "init.mp4" -> media.remux.initSegment(id, call)
            file.startsWith("main") && (file.endsWith(".m4s") || file.endsWith(".ts")) ->
                file.removePrefix("main").substringBefore('.').toIntOrNull()
                    ?.let { media.remux.segment(id, it, call) }
                    ?: call.respond(HttpStatusCode.NotFound, ApiErrorDto("not_found", "Unknown segment"))
            file.startsWith("sub_") && file.endsWith(".m3u8") ->
                file.removePrefix("sub_").removeSuffix(".m3u8").toIntOrNull()
                    ?.let { media.remux.subtitlePlaylist(id, it, call, mediaQuery(call)) }
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

private fun requiredStreamCapability(
    media: MediaRouteDependencies,
    call: ApplicationCall,
): StreamCapability =
    call.request.queryParameters["u"]?.let(media.cipher::tryDecryptStream)
        ?.takeIf { it.url.startsWith("http://") || it.url.startsWith("https://") }
        ?: throw IllegalArgumentException("Invalid or missing target url")

/** A decrypted stream capability whose lease was checked, plus the re-check the transport
 *  runs while streaming: a lease revoked mid-response must cut the response, not outlive it. */
private data class AuthorizedStream(
    val capability: StreamCapability,
    val grant: String?,
    val guard: () -> Unit,
)

private fun authorizedStream(
    media: MediaRouteDependencies,
    call: ApplicationCall,
    transport: PlaybackMediaTransport = PlaybackMediaTransport.SOLO,
): AuthorizedStream {
    val capability = requiredStreamCapability(media, call)
    val grant = call.request.queryParameters["g"]
    val guard: () -> Unit = {
        media.mediaGrants.validateCapability(
            capability.leaseId,
            grant,
            capability,
            transport,
        )
        media.sessions.touch(capability.leaseId)
    }
    guard()
    return AuthorizedStream(capability, grant, guard)
}

/** False (having answered 503) when the route needs ffmpeg and the server has none. */
private suspend fun requireFfmpeg(media: MediaRouteDependencies, call: ApplicationCall): Boolean {
    if (withContext(Dispatchers.IO) { media.remux.available }) return true
    call.respond(
        HttpStatusCode.ServiceUnavailable,
        ApiErrorDto("media_unavailable", "ffmpeg is not installed on the server"),
    )
    return false
}

private data class RemuxTarget(
    val source: String,
    val leaseId: String,
    val guard: () -> Unit,
)

private suspend fun remuxTarget(
    media: MediaRouteDependencies,
    call: ApplicationCall,
): RemuxTarget {
    val grant = call.request.queryParameters["g"]
    val downloadId = call.request.queryParameters["d"]
        ?: return requiredStreamCapability(media, call).let { capability ->
            val guard: () -> Unit = {
                // A room remux is keyed by the room share group and therefore remains one
                // provider read; only the lease-owned proxy/transcode transports are independent.
                media.mediaGrants.validateCapability(
                    capability.leaseId,
                    grant,
                    capability,
                    PlaybackMediaTransport.REMUX,
                )
                media.sessions.touch(capability.leaseId)
            }
            guard()
            RemuxTarget(capability.url, capability.leaseId, guard)
        }
    val leaseId = call.request.queryParameters["sid"]
    val lease = leaseId?.let(media.sessions::lease)
        ?: throw IllegalArgumentException("Invalid or missing target url")
    val source = media.downloads.fileFor(lease.userId, downloadId)?.second?.toString()
        ?: throw IllegalArgumentException("Invalid or missing target url")
    val guard: () -> Unit = {
        media.mediaGrants.validateSource(leaseId, grant, source)
        media.sessions.touch(requireNotNull(leaseId))
    }
    guard()
    return RemuxTarget(source, requireNotNull(leaseId), guard)
}

private suspend fun <T> withLeaseKeepAlive(
    guard: () -> Unit,
    block: suspend () -> T,
): T = coroutineScope {
    guard()
    val heartbeat = launch {
        while (isActive) {
            delay(MEDIA_GUARD_INTERVAL_MS)
            guard()
        }
    }
    try {
        block()
    } finally {
        heartbeat.cancel()
    }
}

private fun mediaQuery(call: ApplicationCall): String =
    "?sid=${urlEncode(call.request.queryParameters["sid"].orEmpty())}" +
        "&g=${urlEncode(call.request.queryParameters["g"].orEmpty())}"

private const val MEDIA_GUARD_INTERVAL_MS = 4_000L
