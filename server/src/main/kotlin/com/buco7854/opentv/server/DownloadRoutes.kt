package com.buco7854.opentv.server

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.util.cio.readChannel
import io.ktor.utils.io.ByteReadChannel
import java.nio.file.Path

/** Ktor adapter for download use cases and file delivery. */
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
        }
    }
    route("/admin/downloads") {
        get { call.respond(service.adminList(call.actor)) }
        delete("/blobs/{id}") {
            call.respond(service.adminCancelBlob(call.actor, call.requiredParameter("id")))
        }
    }
}

internal fun Route.downloadFileRoutes(service: DownloadApplicationService) {
    get("/downloads/{id}/file") {
        val file = service.file(
            call.requiredParameter("id"),
            call.request.queryParameters["token"],
        )
        if (call.request.queryParameters["save"] == "1") {
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment
                    .withParameter(
                        ContentDisposition.Parameters.FileName,
                        file.path.fileName.toString(),
                    )
                    .toString(),
            )
        }
        call.respond(DownloadFileSnapshot(file.path, file.availableBytes))
    }
}

/**
 * A growing download is exposed as a fixed snapshot. PartialContent can then answer Range
 * requests without either reading bytes appended later or advertising the provider's final size.
 */
private class DownloadFileSnapshot(
    private val path: Path,
    private val snapshotBytes: Long,
) : OutgoingContent.ReadChannelContent() {
    override val contentType: ContentType = ContentType.Application.OctetStream
    override val contentLength: Long = snapshotBytes

    override fun readFrom(): ByteReadChannel =
        if (snapshotBytes == 0L) ByteReadChannel(ByteArray(0))
        else path.readChannel(0, snapshotBytes - 1)

    override fun readFrom(range: LongRange): ByteReadChannel =
        path.readChannel(range.first, minOf(range.last, snapshotBytes - 1))
}
