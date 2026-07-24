package com.buco7854.opentv.server

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
import io.ktor.websocket.readText
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/** Ktor and WebSocket adapter for playback-session use cases. */
internal fun Route.sessionRoutes(
    service: SessionApplicationService,
    trustedProxies: TrustedProxies,
) {
    fun ApplicationCall.playbackClient() = PlaybackClient(
        ip = trustedProxies.clientIp(this),
        userAgent = request.headers[HttpHeaders.UserAgent].orEmpty(),
    )

    route("/sessions") {
        get { call.respond(service.active()) }
        post("/heartbeat") {
            call.respond(service.heartbeat(call.playbackClient(), call.receive()))
        }
        post("/intent") {
            call.respond(service.watchIntent(call.receive()))
        }
        post("/{id}/join-request") {
            service.requestJoin(call.requiredParameter("id"), call.receive())
            call.respond(HttpStatusCode.NoContent)
        }
        post("/{id}/join-answer") {
            service.answerJoin(call.requiredParameter("id"), call.receive())
            call.respond(HttpStatusCode.NoContent)
        }
        post("/{id}/sync") {
            service.sync(call.requiredParameter("id"), call.receive())
            call.respond(HttpStatusCode.NoContent)
        }
        post("/{id}/kick") {
            service.kick(call.requiredParameter("id"), call.receive())
            call.respond(HttpStatusCode.NoContent)
        }
        post("/{id}/request-control") {
            service.requestControl(call.requiredParameter("id"), call.receive())
            call.respond(HttpStatusCode.NoContent)
        }
        post("/{id}/grant-control") {
            service.grantControl(call.requiredParameter("id"), call.receive())
            call.respond(HttpStatusCode.NoContent)
        }
        post("/{id}/set-control") {
            service.setControl(call.requiredParameter("id"), call.receive())
            call.respond(HttpStatusCode.NoContent)
        }
        post("/{id}/room-audio") {
            service.setRoomAudio(call.requiredParameter("id"), call.receive())
            call.respond(HttpStatusCode.NoContent)
        }
        post("/{id}/ready") {
            service.ready(call.requiredParameter("id"))
            call.respond(HttpStatusCode.NoContent)
        }
        post("/{id}/leave") {
            service.leave(call.requiredParameter("id"))
            call.respond(HttpStatusCode.NoContent)
        }
        post("/{id}/command") {
            service.command(call.requiredParameter("id"), call.receive())
            call.respond(HttpStatusCode.NoContent)
        }
        webSocket("/{id}/ws") {
            val id = call.requiredParameter("id")
            val client = call.playbackClient()
            suspend fun flush() = service.commands(id).forEach {
                send(Frame.Text(Json.encodeToString(SessionCommandDto.serializer(), it)))
            }
            service.resendRoomState(id)
            val sender = launch {
                flush()
                for (signal in service.commandSignal(id)) flush()
            }
            try {
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val message = runCatching {
                        Json.decodeFromString(ClientFrameDto.serializer(), frame.readText())
                    }.getOrNull() ?: continue
                    when (message.type) {
                        "heartbeat" -> message.heartbeat?.let { service.update(client, it) }
                        "sync" -> message.sync?.let { service.sync(id, it) }
                    }
                }
            } finally {
                sender.cancel()
            }
        }
        delete("/{id}") {
            service.remove(call.requiredParameter("id"))
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
