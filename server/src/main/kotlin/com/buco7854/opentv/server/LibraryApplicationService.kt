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
    private val auth: AuthService,
    private val content: ContentIdentityService,
    private val activity: UserActivityService,
) {
    suspend fun channel(actor: Actor, id: Long): ChannelDto {
        val channel = channelModel(actor, id)
        return channel.toDto(cipher, content.channel(channel).contentId, actor.userId)
    }

    suspend fun guide(actor: Actor, id: Long): List<GuideEntryDto> =
        xtream.guideFor(channelModel(actor, id)).map { it.toDto() }

    suspend fun vodInfo(actor: Actor, id: Long): MetadataDto {
        val channel = channelModel(actor, id)
        val result = channel.xtreamStreamId?.let { xtream.vodMetadata(channel) }
            ?: metadata.forTitle(isSeries = false, rawName = channel.name)
        return result.toDto(cipher, actor.userId, channel.playlistId)
    }

    suspend fun metadata(actor: Actor, type: String, title: String): MetadataDto =
        metadata.forTitle(isSeries = type == "series", rawName = title).toDto(cipher, actor.userId)

    suspend fun episodeMetadata(
        actor: Actor,
        series: String,
        season: Int?,
        episode: Int?,
    ): MetadataDto {
        val result = if (season == null || episode == null) {
            null
        } else {
            metadata.episodeInfo(series, season, episode)
        }
        return result.toDto(cipher, actor.userId)
    }

    suspend fun resumePoints(actor: Actor): List<ResumePointDto> = activity.resume(actor)

    suspend fun saveResume(actor: Actor, request: ResumePointDto) = activity.saveResume(actor, request)

    suspend fun deleteResume(actor: Actor, contentId: String) = activity.deleteResume(actor, contentId)

    fun settings(actor: Actor): SettingsDto {
        if (!actor.isAdmin) throw ForbiddenApiException()
        return SettingsDto(
        userAgent = settings.userAgent,
        downloadLimit = settings.downloadLimit,
        pageSize = settings.pageSize,
        )
    }

    fun saveSettings(actor: Actor, request: SettingsDto) {
        if (!actor.isAdmin) throw ForbiddenApiException()
        val agent = request.userAgent.trim()
        require(ServerHttp.isUsableUserAgent(agent)) {
            "User-Agent must be a single line of at most ${ServerHttp.MAX_USER_AGENT_LENGTH} printable characters"
        }
        settings.userAgent = agent
        settings.downloadLimit = request.downloadLimit
        http.userAgent = agent.ifBlank { ServerHttp.DEFAULT_USER_AGENT }
    }

    private suspend fun channelModel(actor: Actor, id: Long): Channel {
        val channel = storage.channels.get(id) ?: throw ResourceNotFound("channel")
        if (!auth.hasPlaylistAccess(actor, channel.playlistId)) throw ForbiddenApiException()
        return channel
    }
}
