package com.buco7854.opentv.server

import com.buco7854.opentv.contract.*
import com.buco7854.opentv.core.log.ProviderSecrets
import com.buco7854.opentv.core.model.ChannelKind
import com.buco7854.opentv.core.model.Playlist
import com.buco7854.opentv.core.model.SeriesGroup
import com.buco7854.opentv.core.repo.AccountRepository
import com.buco7854.opentv.core.repo.EpgRepository
import com.buco7854.opentv.core.repo.PlaylistRepository
import com.buco7854.opentv.core.repo.XtreamRepository
import com.buco7854.opentv.core.repo.xtreamSeriesKey
import com.buco7854.opentv.core.storage.SEARCH_RESULTS_PER_KIND
import com.buco7854.opentv.core.storage.Storage
import com.buco7854.opentv.serverdata.db.PlaylistDeletionRow
import com.buco7854.opentv.serverdata.db.ServerUserDatabase
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first

/** Playlist/catalog use cases with explicit actor and entitlement checks. */
class PlaylistApplicationService(
    private val storage: Storage,
    private val playlists: PlaylistRepository,
    private val epg: EpgRepository,
    private val xtream: XtreamRepository,
    private val account: AccountRepository,
    private val cipher: StreamCipher,
    private val auth: AuthService,
    private val content: ContentIdentityService,
    private val activity: UserActivityService,
    private val userDatabase: ServerUserDatabase,
    private val downloads: DownloadManager,
    private val cleanup: UserStateCleanupCoordinator = NoopUserStateCleanupCoordinator,
) {
    suspend fun list(actor: Actor): List<PlaylistDto> {
        val access = auth.playlistAccess(actor)
        return storage.playlists.getAll()
            .filter { access.allows(it.id) }
            .map(Playlist::toApiDto)
    }

    suspend fun create(actor: Actor, request: PlaylistUpsertRequest): PlaylistDto {
        requireAdmin(actor)
        val id = when (request.mode) {
            "xtream" -> playlists.addFromXtream(
                request.name, request.server, request.username, request.password,
            )
            "url" -> playlists.addFromUrl(request.name, request.url, request.epgUrl)
            "file" -> playlists.importFromLines(request.name, request.content.lineSequence())
            else -> throw IllegalArgumentException("Unknown mode")
        }
        runCatching { epg.refresh(id) }
        content.reconcilePlaylist(id)
        return playlist(id).toApiDto()
    }

    suspend fun update(actor: Actor, id: Long, request: PlaylistUpsertRequest): PlaylistDto {
        requireAdmin(actor)
        val resolved = request.preservingSecretsFrom(playlist(id))
        when (resolved.mode) {
            "xtream" -> playlists.updateXtream(
                id, resolved.name, resolved.server, resolved.username, resolved.password,
            )
            "url" -> playlists.updateUrl(id, resolved.name, resolved.url, resolved.epgUrl)
            "file" -> if (resolved.content.isNotBlank()) {
                playlists.replaceFromLines(id, resolved.name, resolved.content.lineSequence())
            } else {
                playlists.rename(id, resolved.name)
            }
            else -> throw IllegalArgumentException("Unknown mode")
        }
        content.reconcilePlaylist(id)
        return playlist(id).toApiDto()
    }

    suspend fun delete(actor: Actor, id: Long) {
        requireAdmin(actor)
        userDatabase.maintenance().beginPlaylistDeletion(
            PlaylistDeletionRow(id, System.currentTimeMillis()),
        )
        completePlaylistDeletion(id)
    }

    suspend fun reconcilePendingDeletions() {
        userDatabase.maintenance().pendingPlaylistDeletions().forEach {
            completePlaylistDeletion(it.playlistId)
        }
        val existing = storage.playlists.getAll().mapTo(mutableSetOf()) { it.id }
        userDatabase.maintenance().referencedPlaylistIds()
            .filterNot { it in existing }
            .forEach { completePlaylistDeletion(it) }
    }

    suspend fun refresh(actor: Actor, id: Long, force: Boolean): PlaylistDto {
        requireAdmin(actor)
        val refreshed = playlists.refresh(id, force)
        runCatching { epg.refresh(id, force) }
        if (refreshed) content.reconcilePlaylist(id)
        return playlist(id).toApiDto()
    }

    suspend fun clearProgress(actor: Actor, id: Long) = activity.clearResume(actor, id)

    suspend fun detail(actor: Actor, id: Long): PlaylistDetailDto {
        requireAccess(actor, id)
        val playlist = playlist(id)
        val nativeXtream = playlist.isXtreamNative
        val seriesCount = if (nativeXtream) storage.xtreamSeries.observeCount(id).first()
        else storage.channels.observeCount(id, ChannelKind.SERIES).first()
        return PlaylistDetailDto(
            playlist.toApiDto(),
            nativeXtream,
            storage.channels.observeCount(id, ChannelKind.LIVE).first(),
            storage.channels.observeCount(id, ChannelKind.MOVIE).first(),
            seriesCount,
        )
    }

    suspend fun groups(actor: Actor, id: Long, kind: Int): List<GroupCountDto> {
        requireAccess(actor, id)
        val groups = if (kind == ChannelKind.SERIES && playlist(id).isXtreamNative) {
            storage.xtreamSeries.observeCategories(id).first()
        } else storage.channels.observeGroups(id, kind).first()
        return groups.map { it.toDto() }
    }

    suspend fun channels(
        actor: Actor,
        id: Long,
        kind: Int,
        group: String,
        page: ListingRequest,
    ): ChannelPageDto {
        requireAccess(actor, id)
        val result = storage.channels.pageInGroup(
            id, kind, group, page.filter, page.limit, page.offset,
        )
        val identities = content.channelListings(result.items)
        return ChannelPageDto(
            result.items.map {
                it.toChannelListItemDto(
                    cipher,
                    requireNotNull(identities[it.id]).contentId,
                    actor.userId,
                )
            },
            result.total,
            page.offset,
            page.limit,
        )
    }

    suspend fun seriesGroups(
        actor: Actor,
        id: Long,
        group: String,
        page: ListingRequest,
    ): SeriesGroupPageDto {
        requireAccess(actor, id)
        val result = storage.channels.pageSeriesInGroup(
            id, group, page.filter, page.limit, page.offset,
        )
        val identities = content.m3uSeriesIdentities(id, result.items)
        return SeriesGroupPageDto(
            result.items.map {
                it.toDto(
                    cipher,
                    requireNotNull(identities[it.seriesKey]).contentId,
                    actor.userId,
                    id,
                )
            },
            result.total,
            page.offset,
            page.limit,
        )
    }

    suspend fun xtreamSeries(
        actor: Actor,
        id: Long,
        category: String,
        page: ListingRequest,
    ): XtreamSeriesPageDto {
        requireAccess(actor, id)
        val result = storage.xtreamSeries.pageInCategory(
            id, category, page.filter, page.limit, page.offset,
        )
        val identities = content.xtreamSeriesListingIdentities(result.items)
        return XtreamSeriesPageDto(
            result.items.map {
                it.toListItemDto(
                    cipher,
                    requireNotNull(identities[it.seriesId]).contentId,
                    actor.userId,
                )
            },
            result.total,
            page.offset,
            page.limit,
        )
    }

    suspend fun nowAiring(actor: Actor, id: Long): Map<String, ProgrammeDto> {
        requireAccess(actor, id)
        return epg.nowAiring(id).mapValues { it.value.toDto() }
    }

    suspend fun guideIds(actor: Actor, id: Long): Set<String> {
        requireAccess(actor, id)
        return epg.observeGuideIds(id).first()
    }

    suspend fun search(actor: Actor, id: Long, query: String): SearchResultsDto {
        requireAccess(actor, id)
        val term = query.trim().take(MAX_SEARCH_QUERY_CHARS)
        if (term.length < 2) return SearchResultsDto()
        val (rows, panelHits) = coroutineScope {
            val rows = async { storage.channels.search(id, term) }
            val panelHits = async { storage.xtreamSeries.search(id, term) }
            rows.await() to panelHits.await()
        }
        val m3uGroups = rows.filter { it.kind == ChannelKind.SERIES }
            .filterNot { it.seriesKey?.startsWith("xs:") == true }
            .groupBy { it.seriesKey ?: it.name }
            .map { (key, episodes) ->
                SeriesGroup(
                    key,
                    episodes.size,
                    episodes.firstOrNull { it.logo != null }?.logo,
                    episodes.first().groupTitle,
                )
            }
        val m3uIdentities = content.m3uSeriesIdentities(id, m3uGroups)
        val m3uSeries = m3uGroups.map { group ->
            SeriesHitDto(
                requireNotNull(m3uIdentities[group.seriesKey]).contentId,
                group.seriesKey,
                group.count,
                cipher.encryptOrNull(group.logo, actor.userId, id),
                group.groupTitle,
            )
        }
        val panelIdentities = content.xtreamSeriesIdentities(panelHits)
        val panelSeries = panelHits.map {
            SeriesHitDto(
                requireNotNull(panelIdentities[it.seriesId]).contentId,
                it.name,
                0,
                cipher.encryptOrNull(it.cover, actor.userId, id),
                it.categoryName,
                it.seriesId.toString(),
            )
        }
        return SearchResultsDto(
            live = channelDtos(actor, rows.filter { it.kind == ChannelKind.LIVE }),
            movies = channelDtos(actor, rows.filter { it.kind == ChannelKind.MOVIE }),
            series = (panelSeries + m3uSeries)
                .sortedWith(seriesSearchComparator(term))
                .take(SEARCH_RESULTS_PER_KIND),
        )
    }

    suspend fun account(actor: Actor, id: Long, force: Boolean): AccountInfoDto {
        requireAdmin(actor)
        return account.accountInfo(playlist(id), force)?.toDto()
            ?: throw ResourceNotFound("account", "No account API for this playlist")
    }

    suspend fun setGroupKind(actor: Actor, id: Long, request: GroupKindRequest) {
        requireAdmin(actor)
        playlists.setGroupOverride(id, request.groupTitle, request.kind)
    }

    suspend fun favorites(actor: Actor, id: Long): List<FavoriteDto> =
        activity.favorites(actor, id)

    suspend fun addFavorite(actor: Actor, id: Long, request: FavoriteRequest) {
        requireAccess(actor, id)
        require(content.identity(request.contentId).playlistId == id) {
            "Content does not belong to playlist"
        }
        activity.addFavorite(actor, request.contentId)
    }

    suspend fun removeFavorite(actor: Actor, id: Long, contentId: String) {
        requireAccess(actor, id)
        require(content.identity(contentId).playlistId == id) {
            "Content does not belong to playlist"
        }
        activity.removeFavorite(actor, contentId)
    }

    suspend fun resolvedFavorites(actor: Actor, id: Long): FavoritesResolvedDto {
        requireAccess(actor, id)
        val ids = activity.favoriteIds(actor, id)
        val identities = content.identitiesByContentId(ids)
        // One query for every favorite's channel, not one query per favorite.
        val channelIds = ids.mapNotNull { contentId ->
            identities[contentId]?.takeIf { it.playlistId == id }?.currentChannelId
        }
        val channels = storage.channels.getMany(channelIds).associateBy { it.id }
        val resolved = ids.mapNotNull { contentId ->
            val identity = identities[contentId]?.takeIf { it.playlistId == id }
                ?: return@mapNotNull null
            identity.currentChannelId?.let { channels[it] }?.let { contentId to it }
        }
        val live = resolved.filter { it.second.kind == ChannelKind.LIVE }
            .map { (contentId, channel) -> channel.toDto(cipher, contentId, actor.userId) }
        val movies = resolved.filter { it.second.kind == ChannelKind.MOVIE }
            .map { (contentId, channel) -> channel.toDto(cipher, contentId, actor.userId) }
        val series = mutableListOf<SeriesHitDto>()
        val panel = storage.xtreamSeries.observeAll(id).first()
        val panelIdentities = content.xtreamSeriesIdentities(panel)
        panel.forEach { entry ->
            val contentId = panelIdentities[entry.seriesId]?.contentId ?: return@forEach
            if (contentId !in ids) return@forEach
            series += SeriesHitDto(
                contentId, entry.name, 0,
                cipher.encryptOrNull(entry.cover, actor.userId, id),
                entry.categoryName, entry.seriesId.toString(),
            )
        }
        val m3u = storage.channels.observeAllSeries(id).first()
            .filterNot { it.seriesKey.startsWith("xs:") }
        val m3uIdentities = content.m3uSeriesIdentities(id, m3u)
        m3u.forEach { entry ->
            val contentId = m3uIdentities[entry.seriesKey]?.contentId ?: return@forEach
            if (contentId !in ids) return@forEach
            series += SeriesHitDto(
                contentId, entry.seriesKey, entry.count,
                cipher.encryptOrNull(entry.logo, actor.userId, id), entry.groupTitle,
            )
        }
        return FavoritesResolvedDto(live, movies, series.sortedBy { it.seriesKey.lowercase() })
    }

    suspend fun episodes(
        actor: Actor,
        id: Long,
        seriesKey: String,
        season: Int?,
        page: ListingRequest,
    ): EpisodePageDto {
        requireAccess(actor, id)
        val result = storage.channels.pageEpisodes(
            id, seriesKey, season, page.limit, page.offset,
        )
        val seasons = storage.channels.episodeSeasons(id, seriesKey)
        val identities = content.channelListings(result.items)
        return EpisodePageDto(
            result.items.map {
                it.toEpisodeListItemDto(
                    cipher,
                    requireNotNull(identities[it.id]).contentId,
                    actor.userId,
                )
            },
            result.total,
            page.offset,
            page.limit,
            seasons,
            if (result.total > 0 || seasons.isNotEmpty()) {
                content.m3uSeries(id, seriesKey).contentId
            } else null,
            result.items.firstOrNull()?.groupTitle,
        )
    }

    suspend fun xtreamSeriesDetail(actor: Actor, id: Long, seriesId: Long): XtreamSeriesDetailDto {
        requireAccess(actor, id)
        val series = storage.xtreamSeries.get(id, seriesId) ?: throw ResourceNotFound("series")
        val failure = runCatching { xtream.ensureEpisodes(id, seriesId) }.exceptionOrNull()
        val episodes = storage.channels.observeEpisodes(id, xtreamSeriesKey(seriesId)).first()
        content.channels(episodes)
        return XtreamSeriesDetailDto(
            series.toDto(cipher, content.xtreamSeries(series).contentId, actor.userId),
            channelDtos(actor, episodes),
            failure?.let { "Couldn't load episodes: ${ProviderSecrets.redact(it)}" }
                ?.takeIf { episodes.isEmpty() },
        )
    }

    private suspend fun channelDtos(
        actor: Actor,
        rows: List<com.buco7854.opentv.core.model.Channel>,
    ): List<ChannelDto> {
        val identities = content.channels(rows)
        return rows.map {
            it.toDto(cipher, requireNotNull(identities[it.id]).contentId, actor.userId)
        }
    }

    private suspend fun requireAccess(actor: Actor, id: Long) {
        if (!auth.hasPlaylistAccess(actor, id)) throw ForbiddenApiException()
    }

    private fun requireAdmin(actor: Actor) {
        if (!actor.isAdmin) throw ForbiddenApiException()
    }

    private suspend fun playlist(id: Long): Playlist =
        storage.playlists.get(id) ?: throw ResourceNotFound("playlist")

    private suspend fun completePlaylistDeletion(id: Long) {
        cleanup.playlistDeleting(id)
        if (storage.playlists.get(id) != null) playlists.delete(id)
        downloads.deletePlaylist(id)
        content.deletePlaylist(id)
        auth.deletePlaylistState(id)
        userDatabase.maintenance().finishPlaylistDeletion(id)
    }
}

data class ListingRequest(
    val offset: Int,
    val limit: Int,
    val filter: String = "",
)

private val Playlist.isXtreamNative: Boolean get() = url == null && xtreamBase != null

private fun seriesSearchComparator(term: String): Comparator<SeriesHitDto> =
    compareBy<SeriesHitDto>(
        { searchMatchTier(it.seriesKey, term) },
        { it.seriesKey.lowercase() },
        { it.xtreamSeriesId ?: Long.MAX_VALUE },
        { it.seriesKey },
    )

private fun searchMatchTier(name: String, term: String): Int {
    val normalizedName = name.lowercase()
    val normalizedTerm = term.lowercase()
    val index = normalizedName.indexOf(normalizedTerm)
    return when {
        index == 0 && normalizedName.length == normalizedTerm.length -> 0
        index == 0 -> 1
        index > 0 && !normalizedName[index - 1].isLetterOrDigit() -> 2
        else -> 3
    }
}

private const val MAX_SEARCH_QUERY_CHARS = 80
private val Playlist.mode: String get() = when {
    url != null -> "url"
    xtreamBase != null -> "xtream"
    else -> "file"
}

private fun Playlist.toApiDto() = PlaylistDto(
    id, name, mode, xtreamBase != null, lastRefreshedMs, channelCount,
)

internal fun PlaylistUpsertRequest.preservingSecretsFrom(existing: Playlist): PlaylistUpsertRequest =
    when (mode) {
        "xtream" -> copy(
            server = server.ifBlank { existing.xtreamBase.orEmpty() },
            username = username.ifBlank { existing.xtreamUser.orEmpty() },
            password = password.ifBlank { existing.xtreamPass.orEmpty() },
        )
        "url" -> copy(
            url = url.ifBlank { existing.url.orEmpty() },
            epgUrl = epgUrl.ifBlank { existing.epgUrl.orEmpty() },
        )
        else -> this
    }
