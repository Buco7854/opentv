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
    get { call.respond(service.list(call.actor)) }
    post { call.respond(service.create(call.actor, call.receive())) }

    route("/{id}") {
        get { call.respond(service.detail(call.actor, call.id())) }
        put { call.respond(service.update(call.actor, call.id(), call.receive())) }
        delete {
            service.delete(call.actor, call.id())
            call.respond(HttpStatusCode.NoContent)
        }
        post("/refresh") {
            call.respond(
                service.refresh(
                    call.actor, call.id(),
                    call.request.queryParameters["force"] == "true",
                )
            )
        }
        post("/clear-progress") {
            service.clearProgress(call.actor, call.id())
            call.respond(HttpStatusCode.NoContent)
        }
        get("/groups") {
            val kind = call.request.queryParameters["kind"]?.toIntOrNull() ?: ChannelKind.LIVE
            call.respond(service.groups(call.actor, call.id(), kind))
        }
        get("/channels") {
            val kind = call.request.queryParameters["kind"]?.toIntOrNull() ?: ChannelKind.LIVE
            val group = call.request.queryParameters["group"].orEmpty()
            call.respond(service.channels(call.actor, call.id(), kind, group))
        }
        get("/series-groups") {
            call.respond(service.seriesGroups(call.actor, call.id(), call.request.queryParameters["group"]))
        }
        get("/xtream-series") {
            call.respond(service.xtreamSeries(call.actor, call.id(), call.request.queryParameters["category"]))
        }
        get("/now-airing") {
            call.respond(service.nowAiring(call.actor, call.id()))
        }
        get("/guide-ids") {
            call.respond(service.guideIds(call.actor, call.id()))
        }
        get("/search") {
            call.respond(service.search(call.actor, call.id(), call.request.queryParameters["q"].orEmpty()))
        }
        get("/account") {
            call.respond(
                service.account(
                    call.actor, call.id(),
                    call.request.queryParameters["force"] == "true",
                )
            )
        }
        put("/group-kind") {
            service.setGroupKind(call.actor, call.id(), call.receive())
            call.respond(HttpStatusCode.NoContent)
        }
        route("/favorites") {
            get { call.respond(service.favorites(call.actor, call.id())) }
            put {
                service.addFavorite(call.actor, call.id(), call.receive())
                call.respond(HttpStatusCode.NoContent)
            }
            delete {
                val contentId = call.request.queryParameters["contentId"]
                    ?: throw IllegalArgumentException("Missing contentId")
                service.removeFavorite(call.actor, call.id(), contentId)
                call.respond(HttpStatusCode.NoContent)
            }
            get("/resolved") {
                call.respond(service.resolvedFavorites(call.actor, call.id()))
            }
        }
        get("/series/{seriesKey}/episodes") {
            call.respond(
                service.episodes(
                    call.actor, call.id(),
                    call.requiredParameter("seriesKey"),
                )
            )
        }
        get("/xseries/{seriesId}") {
            call.respond(service.xtreamSeriesDetail(call.actor, call.id(), call.id("seriesId")))
        }
    }
}
