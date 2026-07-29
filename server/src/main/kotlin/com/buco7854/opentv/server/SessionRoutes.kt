package com.buco7854.opentv.server

import com.buco7854.opentv.contract.*
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

private val LEASE_ENDED = CloseReason(CloseReason.Codes.NORMAL, "playback lease ended")
internal val sessionProtocolJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** Ktor adapter for owner-bound playback leases and admin playback control. */
internal fun Route.sessionRoutes(
    service: SessionApplicationService,
    trustedProxies: TrustedProxies,
) {
    fun ApplicationCall.playbackClient() = PlaybackClient(
        ip = trustedProxies.clientIp(this),
        userAgent = request.headers[HttpHeaders.UserAgent].orEmpty(),
    )

    route("/playback") {
        post {
            call.respond(service.create(call.actor, call.playbackClient(), call.receive()))
        }
        route("/{id}") {
            post("/heartbeat") {
                val id = call.requiredParameter("id")
                val heartbeat = call.receive<SessionHeartbeatDto>()
                require(heartbeat.id == id) { "Playback lease mismatch" }
                call.respond(service.heartbeat(call.actor, call.playbackClient(), heartbeat))
            }
            post("/media-grant") {
                call.respond(service.refreshMediaGrant(call.actor, call.requiredParameter("id")))
            }
            post("/ws-token") {
                call.respond(service.webSocketAccess(call.actor, call.requiredParameter("id")))
            }
            post("/intent") {
                call.respond(service.watchIntent(call.actor, call.requiredParameter("id")))
            }
            post("/join-request") {
                service.requestJoin(call.actor, call.requiredParameter("id"), call.receive())
                call.respond(HttpStatusCode.NoContent)
            }
            post("/join-answer") {
                service.answerJoin(call.actor, call.requiredParameter("id"), call.receive())
                call.respond(HttpStatusCode.NoContent)
            }
            post("/sync") {
                service.sync(call.actor, call.requiredParameter("id"), call.receive())
                call.respond(HttpStatusCode.NoContent)
            }
            post("/kick") {
                service.kick(call.actor, call.requiredParameter("id"), call.receive())
                call.respond(HttpStatusCode.NoContent)
            }
            post("/request-control") {
                service.requestControl(call.actor, call.requiredParameter("id"), call.receive())
                call.respond(HttpStatusCode.NoContent)
            }
            post("/grant-control") {
                service.grantControl(call.actor, call.requiredParameter("id"), call.receive())
                call.respond(HttpStatusCode.NoContent)
            }
            post("/set-control") {
                service.setControl(call.actor, call.requiredParameter("id"), call.receive())
                call.respond(HttpStatusCode.NoContent)
            }
            post("/room-audio") {
                service.setRoomAudio(call.actor, call.requiredParameter("id"), call.receive())
                call.respond(HttpStatusCode.NoContent)
            }
            post("/ready") {
                service.ready(call.actor, call.requiredParameter("id"), call.receive())
                call.respond(HttpStatusCode.NoContent)
            }
            post("/leave") {
                service.leave(call.actor, call.requiredParameter("id"))
                call.respond(HttpStatusCode.NoContent)
            }
            webSocket("/ws") {
                val id = call.requiredParameter("id")
                val actor = call.actor
                val client = call.playbackClient()
                suspend fun flush() = service.commands(actor, id).forEach {
                    send(Frame.Text(sessionProtocolJson.encodeToString(SessionCommandDto.serializer(), it)))
                }
                try {
                    service.resendRoomState(actor, id)
                    val sender = launch {
                        flush()
                        for (signal in service.commandSignal(actor, id)) flush()
                        this@webSocket.close(LEASE_ENDED)
                    }
                    try {
                        for (frame in incoming) {
                            if (frame !is Frame.Text) continue
                            val message = runCatching {
                                sessionProtocolJson.decodeFromString(
                                    ClientFrameDto.serializer(),
                                    frame.readText(),
                                )
                            }.getOrNull() ?: continue
                            when (message.type) {
                                "heartbeat" -> message.heartbeat
                                    ?.takeIf { it.id == id }
                                    ?.let { service.update(actor, client, it) }
                                "sync" -> message.sync?.let { service.sync(actor, id, it) }
                            }
                        }
                    } finally {
                        sender.cancel()
                    }
                } catch (_: PlaybackRevokedException) {
                    close(LEASE_ENDED)
                } catch (_: ResourceNotFound) {
                    close(LEASE_ENDED)
                } catch (_: UnauthenticatedApiException) {
                    close(LEASE_ENDED)
                } catch (_: ForbiddenApiException) {
                    close(LEASE_ENDED)
                }
            }
            delete {
                service.remove(call.actor, call.requiredParameter("id"))
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }

    route("/admin/playback") {
        get { call.respond(service.active(call.actor)) }
        post("/{id}/command") {
            service.command(call.actor, call.requiredParameter("id"), call.receive())
            call.respond(HttpStatusCode.NoContent)
        }
        delete("/{id}") {
            service.adminRemove(call.actor, call.requiredParameter("id"))
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
