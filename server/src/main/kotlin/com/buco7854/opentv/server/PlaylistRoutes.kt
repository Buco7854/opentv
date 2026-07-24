package com.buco7854.opentv.server

import com.buco7854.opentv.core.model.ChannelKind
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

/** Ktor adapter for playlist use cases; business and persistence decisions live in the service. */
internal fun Route.playlistRoutes(service: PlaylistApplicationService) = route("/playlists") {
    get { call.respond(service.list()) }
    post { call.respond(service.create(call.receive())) }

    route("/{id}") {
        get { call.respond(service.detail(call.id())) }
        put { call.respond(service.update(call.id(), call.receive())) }
        delete {
            service.delete(call.id())
            call.respond(HttpStatusCode.NoContent)
        }
        post("/refresh") {
            call.respond(
                service.refresh(
                    call.id(),
                    call.request.queryParameters["force"] == "true",
                )
            )
        }
        post("/clear-progress") {
            service.clearProgress(call.id())
            call.respond(HttpStatusCode.NoContent)
        }
        get("/groups") {
            val kind = call.request.queryParameters["kind"]?.toIntOrNull() ?: ChannelKind.LIVE
            call.respond(service.groups(call.id(), kind))
        }
        get("/channels") {
            val kind = call.request.queryParameters["kind"]?.toIntOrNull() ?: ChannelKind.LIVE
            val group = call.request.queryParameters["group"].orEmpty()
            call.respond(service.channels(call.id(), kind, group))
        }
        get("/series-groups") {
            call.respond(service.seriesGroups(call.id(), call.request.queryParameters["group"]))
        }
        get("/xtream-series") {
            call.respond(service.xtreamSeries(call.id(), call.request.queryParameters["category"]))
        }
        get("/now-airing") {
            call.respond(service.nowAiring(call.id()))
        }
        get("/guide-ids") {
            call.respond(service.guideIds(call.id()))
        }
        get("/search") {
            call.respond(service.search(call.id(), call.request.queryParameters["q"].orEmpty()))
        }
        get("/account") {
            call.respond(
                service.account(
                    call.id(),
                    call.request.queryParameters["force"] == "true",
                )
            )
        }
        put("/group-kind") {
            service.setGroupKind(call.id(), call.receive())
            call.respond(HttpStatusCode.NoContent)
        }
        route("/favorites") {
            get { call.respond(service.favorites(call.id())) }
            put {
                service.addFavorite(call.id(), call.receive())
                call.respond(HttpStatusCode.NoContent)
            }
            delete {
                val key = call.request.queryParameters["key"]
                    ?: throw IllegalArgumentException("Missing key")
                service.removeFavorite(call.id(), key)
                call.respond(HttpStatusCode.NoContent)
            }
            get("/resolved") {
                call.respond(service.resolvedFavorites(call.id()))
            }
        }
        get("/series/{seriesKey}/episodes") {
            call.respond(
                service.episodes(
                    call.id(),
                    call.requiredParameter("seriesKey"),
                )
            )
        }
        get("/xseries/{seriesId}") {
            call.respond(service.xtreamSeriesDetail(call.id(), call.id("seriesId")))
        }
    }
}
