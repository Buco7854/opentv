package com.buco7854.opentv.server

import com.buco7854.opentv.contract.*
import com.buco7854.opentv.serverdata.DownloadBlobStatus
import java.nio.file.Files
import java.nio.file.Path

/** Per-user download use cases backed by shared physical blobs. */
class DownloadApplicationService(
    private val downloads: DownloadManager,
    private val content: ContentIdentityService,
    private val auth: AuthService,
    private val cipher: StreamCipher,
) {
    suspend fun list(actor: Actor): List<DownloadDto> {
        val rows = downloads.list(actor.userId)
        val identities = content.identitiesByContentId(rows.map { (_, blob) -> blob.contentId })
        val access = auth.playlistAccess(actor)
        return rows.mapNotNull { (user, blob) ->
            val identity = identities[blob.contentId]
            if (user.suspended || identity == null || !access.allows(identity.playlistId)) {
                null
            } else {
                val fileCapability = if (
                    (blob.status == DownloadBlobStatus.RUNNING && blob.downloadedBytes > 0) ||
                    (blob.status == DownloadBlobStatus.DONE &&
                        blob.totalBytes > 0 &&
                        blob.downloadedBytes == blob.totalBytes)
                ) {
                    cipher.encryptDownloadFile(actor.userId, user.id)
                } else {
                    null
                }
                DownloadDto(
                    id = user.id,
                    contentId = blob.contentId,
                    title = blob.title,
                    status = blob.status,
                    active = user.active,
                    suspended = user.suspended,
                    totalBytes = blob.totalBytes,
                    downloadedBytes = blob.downloadedBytes,
                    error = blob.error,
                    createdMs = user.createdAtMs,
                    fileToken = fileCapability?.token,
                    fileTokenExpiresAtMs = fileCapability?.expiresAtMs,
                )
            }
        }
    }

    suspend fun enqueue(actor: Actor, request: EnqueueDownloadRequest): MessageDto {
        val (identity, channel) = content.requireChannel(request.contentId)
        if (!auth.hasPlaylistAccess(actor, identity.playlistId)) throw ForbiddenApiException()
        val (_, message) = downloads.enqueue(actor.userId, identity, channel)
        return MessageDto(message ?: "Download started: ${channel.name}")
    }

    suspend fun pause(actor: Actor, id: String) {
        requireEntitled(actor, id)
        downloads.pause(actor.userId, id)
    }

    suspend fun resume(actor: Actor, id: String) {
        requireEntitled(actor, id)
        downloads.resume(actor.userId, id)
    }

    suspend fun retry(actor: Actor, id: String) = resume(actor, id)

    suspend fun delete(actor: Actor, id: String) {
        requireEntitled(actor, id)
        downloads.delete(actor.userId, id)
    }

    suspend fun file(id: String, rawToken: String?): DownloadFile {
        val capability = rawToken?.let(cipher::tryDecryptDownloadFile)
            ?.takeIf { it.downloadId == id }
            ?: throw UnauthenticatedApiException()
        return downloads.downloadFileFor(capability.userId, id)?.let { (download, path) ->
            DownloadFile(
                title = download.title,
                path = path,
                availableBytes = Files.size(path),
            )
        } ?: throw ResourceNotFound("download", "Download not finished")
    }

    suspend fun adminList(actor: Actor): List<AdminDownloadDto> {
        requireAdmin(actor)
        return downloads.adminList().map { (user, blob) ->
            AdminDownloadDto(
                user.userId, user.id, blob.id, blob.contentId, blob.title, blob.status,
                user.active, user.suspended, blob.totalBytes, blob.downloadedBytes,
            )
        }
    }

    suspend fun adminCancelBlob(actor: Actor, blobId: String): AdminBlobCancellationDto {
        requireAdmin(actor)
        return AdminBlobCancellationDto(downloads.adminCancelBlob(blobId))
    }

    private fun requireAdmin(actor: Actor) {
        if (!actor.isAdmin) throw ForbiddenApiException()
    }

    private suspend fun requireEntitled(actor: Actor, id: String) {
        val (_, blob) = downloads.get(actor.userId, id)
        val identity = content.identity(blob.contentId)
        if (!auth.hasPlaylistAccess(actor, identity.playlistId)) throw ForbiddenApiException()
    }
}

data class DownloadFile(
    val title: String,
    val path: Path,
    val availableBytes: Long,
)
