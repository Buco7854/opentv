package com.buco7854.opentv.server

import io.ktor.http.ContentDisposition
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/** Ktor adapter for download use cases and completed-file delivery. */
internal fun Route.downloadRoutes(service: DownloadApplicationService) {
    route("/downloads") {
        get { call.respond(service.list(call.actor)) }
        post { call.respond(service.enqueue(call.actor, call.receive())) }
        route("/{id}") {
        post("/pause") {
            service.pause(call.actor, call.requiredParameter("id"))
            call.respond(HttpStatusCode.NoContent)
        }
        post("/resume") {
            service.resume(call.actor, call.requiredParameter("id"))
            call.respond(HttpStatusCode.NoContent)
        }
        post("/retry") {
            service.retry(call.actor, call.requiredParameter("id"))
            call.respond(HttpStatusCode.NoContent)
        }
        delete {
            service.delete(call.actor, call.requiredParameter("id"))
            call.respond(HttpStatusCode.NoContent)
        }
        get("/file") {
            val file = service.file(call.actor, call.requiredParameter("id"))
            if (call.request.queryParameters["save"] == "1") {
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment
                        .withParameter(ContentDisposition.Parameters.FileName, file.path.fileName.toString())
                        .toString(),
                )
            }
            call.respondFile(file.path.toFile())
        }
        }
    }
    route("/admin/downloads") {
        get { call.respond(service.adminList(call.actor)) }
        delete("/blobs/{id}") {
            call.respond(service.adminCancelBlob(call.actor, call.requiredParameter("id")))
        }
    }
}
