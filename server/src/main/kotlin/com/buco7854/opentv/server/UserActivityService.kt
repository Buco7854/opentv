package com.buco7854.opentv.server

import com.buco7854.opentv.core.model.ChannelKind
import com.buco7854.opentv.serverdata.db.ServerUserDatabase
import com.buco7854.opentv.serverdata.db.UserFavoriteRow
import com.buco7854.opentv.serverdata.db.UserResumeRow

class UserActivityService(
    private val db: ServerUserDatabase,
    private val auth: AuthService,
    private val content: ContentIdentityService,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun resume(actor: Actor): List<ResumePointDto> =
        db.activity().resumeForUser(actor.userId).mapNotNull { row ->
            val identity = content.resolve(row.contentId).first
            identity.takeIf { item -> auth.hasPlaylistAccess(actor, item.playlistId) }?.let {
                ResumePointDto(row.contentId, row.positionMs, row.durationMs, row.updatedAtMs)
            }
        }

    suspend fun saveResume(actor: Actor, request: ResumePointDto) {
        val (identity, channel) = content.requireChannel(request.contentId)
        if (!auth.hasPlaylistAccess(actor, identity.playlistId)) throw ForbiddenApiException()
        if (channel.kind == ChannelKind.LIVE) {
            db.activity().deleteResume(actor.userId, request.contentId)
            return
        }
        if (request.durationMs <= 0 || request.positionMs < 10_000 ||
            request.positionMs > request.durationMs - 15_000
        ) {
            db.activity().deleteResume(actor.userId, request.contentId)
        } else {
            db.activity().upsertResume(
                UserResumeRow(
                    actor.userId,
                    request.contentId,
                    request.positionMs,
                    request.durationMs,
                    clock(),
                )
            )
        }
    }

    suspend fun deleteResume(actor: Actor, contentId: String) {
        val identity = content.resolve(contentId).first
        if (!auth.hasPlaylistAccess(actor, identity.playlistId)) throw ForbiddenApiException()
        db.activity().deleteResume(actor.userId, contentId)
    }

    suspend fun clearResume(actor: Actor, playlistId: Long) {
        if (!auth.hasPlaylistAccess(actor, playlistId)) throw ForbiddenApiException()
        db.activity().clearResumeForPlaylist(actor.userId, playlistId)
    }

    suspend fun favorites(actor: Actor, playlistId: Long): List<FavoriteDto> {
        if (!auth.hasPlaylistAccess(actor, playlistId)) throw ForbiddenApiException()
        return db.activity().favorites(actor.userId).mapNotNull { favorite ->
            val identity = content.resolve(favorite.contentId).first
            identity.takeIf { it.playlistId == playlistId }?.let {
                FavoriteDto(
                    contentId = favorite.contentId,
                    playlistId = playlistId,
                    key = favorite.contentId,
                    kind = identity.kind,
                    addedMs = favorite.addedAtMs,
                )
            }
        }
    }

    suspend fun addFavorite(actor: Actor, contentId: String) {
        val identity = content.resolve(contentId).first
        if (!auth.hasPlaylistAccess(actor, identity.playlistId)) throw ForbiddenApiException()
        db.activity().addFavorite(UserFavoriteRow(actor.userId, contentId, clock()))
    }

    suspend fun removeFavorite(actor: Actor, contentId: String) {
        val identity = content.resolve(contentId).first
        if (!auth.hasPlaylistAccess(actor, identity.playlistId)) throw ForbiddenApiException()
        db.activity().removeFavorite(actor.userId, contentId)
    }

    suspend fun favoriteIds(actor: Actor, playlistId: Long): Set<String> =
        favorites(actor, playlistId).mapTo(mutableSetOf()) { it.contentId }

    suspend fun prune() {
        db.activity().pruneResume(clock() - 90L * 24 * 60 * 60 * 1000)
    }
}
