package com.buco7854.opentv.server

import com.buco7854.opentv.contract.*
import com.buco7854.opentv.core.model.ChannelKind
import com.buco7854.opentv.serverdata.db.OpenTvServerDatabase
import com.buco7854.opentv.serverdata.db.UserFavoriteRow
import com.buco7854.opentv.serverdata.db.UserResumeRow

internal data class AuthorizedFavorite(
    val contentId: String,
    val playlistId: Long,
    val kind: Int,
    val providerFingerprint: String,
    val currentChannelId: Long?,
    val addedMs: Long,
)

class UserActivityService(
    private val db: OpenTvServerDatabase,
    private val auth: AuthService,
    private val content: ContentIdentityService,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun resume(actor: Actor): List<ResumePointDto> {
        val rows = db.activity().resumeForUser(actor.userId)
        val identities = content.identitiesByContentId(rows.map { it.contentId })
        val access = auth.playlistAccess(actor)
        return rows.mapNotNull { row ->
            val identity = identities[row.contentId] ?: return@mapNotNull null
            if (!access.allows(identity.playlistId)) return@mapNotNull null
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
        val identity = content.identity(contentId)
        if (!auth.hasPlaylistAccess(actor, identity.playlistId)) throw ForbiddenApiException()
        db.activity().deleteResume(actor.userId, contentId)
    }

    suspend fun clearResume(actor: Actor, playlistId: Long) {
        if (!auth.hasPlaylistAccess(actor, playlistId)) throw ForbiddenApiException()
        db.activity().clearResumeForPlaylist(actor.userId, playlistId)
    }

    suspend fun favorites(actor: Actor, playlistId: Long): List<FavoriteDto> {
        val access = auth.playlistAccess(actor)
        if (!access.allows(playlistId)) throw ForbiddenApiException()
        return authorizedFavorites(actor.userId, access)
            .filter { it.playlistId == playlistId }
            .map { it.toDto() }
    }

    /**
     * Reads the user's favorite rows and their identities in batches, then applies one
     * current entitlement snapshot. The work grows with favorites, not with playlists.
     */
    internal suspend fun authorizedFavorites(
        userId: String,
        access: PlaylistAccess,
    ): List<AuthorizedFavorite> {
        val rows = db.activity().favorites(userId)
        val identities = content.identitiesByContentId(rows.map { it.contentId })
        return rows.mapNotNull { favorite ->
            val identity = identities[favorite.contentId]
                ?.takeIf { access.allows(it.playlistId) }
                ?: return@mapNotNull null
            AuthorizedFavorite(
                contentId = favorite.contentId,
                playlistId = identity.playlistId,
                kind = identity.kind,
                providerFingerprint = identity.providerFingerprint,
                currentChannelId = identity.currentChannelId,
                addedMs = favorite.addedAtMs,
            )
        }
    }

    suspend fun addFavorite(actor: Actor, contentId: String) {
        val identity = content.identity(contentId)
        if (!auth.hasPlaylistAccess(actor, identity.playlistId)) throw ForbiddenApiException()
        db.activity().addFavorite(UserFavoriteRow(actor.userId, contentId, clock()))
    }

    suspend fun removeFavorite(actor: Actor, contentId: String) {
        val identity = content.identity(contentId)
        if (!auth.hasPlaylistAccess(actor, identity.playlistId)) throw ForbiddenApiException()
        db.activity().removeFavorite(actor.userId, contentId)
    }

    suspend fun favoriteIds(actor: Actor, playlistId: Long): Set<String> =
        favorites(actor, playlistId).mapTo(mutableSetOf()) { it.contentId }

    suspend fun prune() {
        db.activity().pruneResume(clock() - 90L * 24 * 60 * 60 * 1000)
    }

    private fun AuthorizedFavorite.toDto() = FavoriteDto(
        contentId = contentId,
        playlistId = playlistId,
        key = contentId,
        kind = kind,
        addedMs = addedMs,
    )
}
