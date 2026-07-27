package com.buco7854.opentv.server

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route

/** Ktor adapter for library, progress, and settings use cases. */
internal fun Route.libraryRoutes(service: LibraryApplicationService) {
    route("/channels/{id}") {
        get { call.respond(service.channel(call.actor, call.id())) }
        get("/guide") { call.respond(service.guide(call.actor, call.id())) }
        get("/vod-info") { call.respond(service.vodInfo(call.actor, call.id())) }
    }

    // The same three resources addressed by stable content id. A catalog refresh reassigns
    // every numeric channel id, so a link built from one dies at the next refresh; the
    // content id survives it.
    route("/content/{contentId}") {
        get { call.respond(service.channelByContent(call.actor, call.requiredParameter("contentId"))) }
        get("/guide") {
            call.respond(service.guideByContent(call.actor, call.requiredParameter("contentId")))
        }
        get("/vod-info") {
            call.respond(service.vodInfoByContent(call.actor, call.requiredParameter("contentId")))
        }
    }

    get("/meta") {
        val type = call.request.queryParameters["type"] ?: "movie"
        val title = call.request.queryParameters["title"]
            ?: throw IllegalArgumentException("Missing title")
        call.respond(service.metadata(call.actor, type, title))
    }
    get("/meta/episode") {
        val series = call.request.queryParameters["series"]
            ?: throw IllegalArgumentException("Missing series")
        call.respond(
            service.episodeMetadata(
                call.actor,
                series,
                call.request.queryParameters["season"]?.toIntOrNull(),
                call.request.queryParameters["episode"]?.toIntOrNull(),
            )
        )
    }

    route("/resume") {
        get { call.respond(service.resumePoints(call.actor)) }
        put {
            service.saveResume(call.actor, call.receive())
            call.respond(HttpStatusCode.NoContent)
        }
        delete {
            val contentId = call.request.queryParameters["contentId"]
                ?: throw IllegalArgumentException("Missing contentId")
            service.deleteResume(call.actor, contentId)
            call.respond(HttpStatusCode.NoContent)
        }
    }

    route("/settings") {
        get { call.respond(service.settings(call.actor)) }
        put {
            service.saveSettings(call.actor, call.receive())
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
