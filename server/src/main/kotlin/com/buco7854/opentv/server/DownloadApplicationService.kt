package com.buco7854.opentv.server

import com.buco7854.opentv.core.storage.Storage
import kotlinx.coroutines.flow.first
import java.nio.file.Path

/** Application use cases for background downloads. */
class DownloadApplicationService(
    private val storage: Storage,
    private val downloads: DownloadManager,
    private val cipher: StreamCipher,
) {
    suspend fun list(): List<DownloadDto> =
        storage.downloads.observeAll().first().map { it.toDto(cipher) }

    suspend fun enqueue(request: EnqueueDownloadRequest): MessageDto {
        val channel = storage.channels.get(request.channelId) ?: throw ResourceNotFound("channel")
        val blockedReason = downloads.enqueue(channel)
        return MessageDto(blockedReason ?: "Download started: ${channel.name}")
    }

    suspend fun pause(id: Long) = downloads.pause(id)
    suspend fun resume(id: Long) = downloads.resume(id)
    suspend fun retry(id: Long) = downloads.retry(id)
    suspend fun delete(id: Long) = downloads.delete(id)

    suspend fun file(id: Long): DownloadFile =
        downloads.fileFor(id)?.let { (download, path) ->
            DownloadFile(download.title, path)
        } ?: throw ResourceNotFound("download", "Download not finished")
}

data class DownloadFile(val title: String, val path: Path)
