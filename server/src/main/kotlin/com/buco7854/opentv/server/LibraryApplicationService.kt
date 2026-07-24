package com.buco7854.opentv.server

import com.buco7854.opentv.core.model.Channel
import com.buco7854.opentv.core.model.ResumePoint
import com.buco7854.opentv.core.repo.MetadataRepository
import com.buco7854.opentv.core.repo.XtreamRepository
import com.buco7854.opentv.core.storage.Storage
import com.buco7854.opentv.core.util.nowMs

/** Application use cases for individual library items, progress, and server settings. */
class LibraryApplicationService(
    private val storage: Storage,
    private val xtream: XtreamRepository,
    private val metadata: MetadataRepository,
    private val cipher: StreamCipher,
    private val settings: ServerSettings,
    private val http: ServerHttp,
) {
    suspend fun channel(id: Long): ChannelDto = channelModel(id).toDto(cipher)

    suspend fun guide(id: Long): List<GuideEntryDto> =
        xtream.guideFor(channelModel(id)).map { it.toDto() }

    suspend fun catchupUrl(id: Long, startMs: Long, endMs: Long): CatchupUrlDto =
        CatchupUrlDto(
            xtream.catchupUrlFor(channelModel(id), startMs, endMs)?.let(cipher::encrypt)
        )

    suspend fun vodInfo(id: Long): MetadataDto {
        val channel = channelModel(id)
        val result = channel.xtreamStreamId?.let { xtream.vodMetadata(channel) }
            ?: metadata.forTitle(isSeries = false, rawName = channel.name)
        return result.toDto(cipher)
    }

    suspend fun metadata(type: String, title: String): MetadataDto =
        metadata.forTitle(isSeries = type == "series", rawName = title).toDto(cipher)

    suspend fun episodeMetadata(series: String, season: Int?, episode: Int?): MetadataDto {
        val result = if (season == null || episode == null) {
            null
        } else {
            metadata.episodeInfo(series, season, episode)
        }
        return result.toDto(cipher)
    }

    suspend fun resumePoints(): List<ResumePointDto> =
        storage.resume.getAll().map { it.toDto(cipher) }

    suspend fun saveResume(request: ResumePointDto) {
        storage.resume.upsert(
            ResumePoint(
                cipher.resolve(request.url),
                request.positionMs,
                request.durationMs,
                nowMs(),
            )
        )
    }

    suspend fun deleteResume(url: String) = storage.resume.delete(cipher.resolve(url))

    fun settings(): SettingsDto = SettingsDto(
        userAgent = settings.userAgent,
        downloadLimit = settings.downloadLimit,
        pageSize = settings.pageSize,
    )

    fun saveSettings(request: SettingsDto) {
        settings.userAgent = request.userAgent
        settings.downloadLimit = request.downloadLimit
        http.userAgent = request.userAgent.trim().ifBlank { ServerHttp.DEFAULT_USER_AGENT }
    }

    private suspend fun channelModel(id: Long): Channel =
        storage.channels.get(id) ?: throw ResourceNotFound("channel")
}
