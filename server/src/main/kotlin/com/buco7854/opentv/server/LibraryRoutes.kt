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
        get { call.respond(service.channel(call.id())) }
        get("/guide") { call.respond(service.guide(call.id())) }
        get("/catchup-url") {
            val start = call.request.queryParameters["start"]?.toLongOrNull()
                ?: throw IllegalArgumentException("Missing start")
            val end = call.request.queryParameters["end"]?.toLongOrNull()
                ?: throw IllegalArgumentException("Missing end")
            call.respond(service.catchupUrl(call.id(), start, end))
        }
        get("/vod-info") { call.respond(service.vodInfo(call.id())) }
    }

    get("/meta") {
        val type = call.request.queryParameters["type"] ?: "movie"
        val title = call.request.queryParameters["title"]
            ?: throw IllegalArgumentException("Missing title")
        call.respond(service.metadata(type, title))
    }
    get("/meta/episode") {
        val series = call.request.queryParameters["series"]
            ?: throw IllegalArgumentException("Missing series")
        call.respond(
            service.episodeMetadata(
                series,
                call.request.queryParameters["season"]?.toIntOrNull(),
                call.request.queryParameters["episode"]?.toIntOrNull(),
            )
        )
    }

    route("/resume") {
        get { call.respond(service.resumePoints()) }
        put {
            service.saveResume(call.receive())
            call.respond(HttpStatusCode.NoContent)
        }
        delete {
            val url = call.request.queryParameters["url"]
                ?: throw IllegalArgumentException("Missing url")
            service.deleteResume(url)
            call.respond(HttpStatusCode.NoContent)
        }
    }

    route("/settings") {
        get { call.respond(service.settings()) }
        put {
            service.saveSettings(call.receive())
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
