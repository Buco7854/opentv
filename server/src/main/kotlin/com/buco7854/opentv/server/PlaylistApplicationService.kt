package com.buco7854.opentv.server

import com.buco7854.opentv.contract.*
import com.buco7854.opentv.core.log.ProviderSecrets
import com.buco7854.opentv.core.model.ChannelKind
import com.buco7854.opentv.core.model.Playlist
import com.buco7854.opentv.core.model.SeriesGroup
import com.buco7854.opentv.core.repo.AccountInfoResult
import com.buco7854.opentv.core.repo.AccountRepository
import com.buco7854.opentv.core.repo.EpgRepository
import com.buco7854.opentv.core.repo.PlaylistRepository
import com.buco7854.opentv.core.repo.XtreamRepository
import com.buco7854.opentv.core.repo.XtreamUnreachableException
import com.buco7854.opentv.core.repo.xtreamSeriesKey
import com.buco7854.opentv.core.storage.SEARCH_RESULTS_PER_KIND
import com.buco7854.opentv.core.storage.Storage
import com.buco7854.opentv.core.xtream.XtreamAuthException
import com.buco7854.opentv.serverdata.db.PlaylistDeletionRow
import com.buco7854.opentv.serverdata.db.OpenTvServerDatabase
import com.buco7854.opentv.serverdata.db.deleteCatalogPlaylist
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private val userDatabase: OpenTvServerDatabase,
    private val downloads: DownloadManager,
    private val cleanup: UserStateCleanupCoordinator = NoopUserStateCleanupCoordinator,
) : AutoCloseable {
    private data class RefreshJob(
        val playlistId: Long,
        val status: String,
        val result: PlaylistRefreshResultDto? = null,
    )

    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshJobsMutex = Mutex()
    private val refreshJobs = LinkedHashMap<String, RefreshJob>()

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

    suspend fun edit(actor: Actor, id: Long): PlaylistEditDto {
        requireAdmin(actor)
        return playlist(id).toEditDto()
    }

    suspend fun update(actor: Actor, id: Long, request: PlaylistUpdateRequest): PlaylistDto {
        requireAdmin(actor)
        content.updatePlaylist(id) {
            val existing = playlist(id)
            request.requireApplicableTo(existing.mode)
            when (existing.mode) {
                "xtream" -> playlists.updateXtream(
                    id,
                    request.name.orKeeping(existing.name),
                    request.server.orKeeping(existing.xtreamBase),
                    request.username.orKeeping(existing.xtreamUser),
                    request.password.orKeeping(existing.xtreamPass),
                )
                "url" -> playlists.updateUrl(
                    id,
                    request.name.orKeeping(existing.name),
                    request.url.orKeeping(existing.url),
                    request.epgUrl.orKeeping(existing.epgUrl),
                )
                "file" -> if (!request.content.isNullOrBlank()) {
                    val replacement = requireNotNull(request.content)
                    playlists.replaceFromLines(
                        id,
                        request.name.orKeeping(existing.name),
                        replacement.lineSequence(),
                    )
                } else {
                    playlists.rename(id, request.name.orKeeping(existing.name))
                }
                else -> throw IllegalArgumentException("Unknown mode")
            }
        }
        return playlist(id).toApiDto()
    }

    suspend fun delete(actor: Actor, id: Long) {
        requireAdmin(actor)
        userDatabase.maintenance().beginPlaylistDeletion(
            PlaylistDeletionRow(id, System.currentTimeMillis()),
        )
        completePlaylistDeletion(id)
    }

    suspend fun deleteInfo(actor: Actor, id: Long): PlaylistDeleteInfoDto {
        requireAdmin(actor)
        val playlist = playlist(id)
        return PlaylistDeleteInfoDto(
            id = playlist.id,
            name = playlist.name,
            warning = "\"${playlist.name}\" and its cached guide data will be removed, " +
                "along with every user's favorites, watch progress and downloads for it. " +
                "This cannot be undone.",
        )
    }

    suspend fun reconcilePendingDeletions() {
        userDatabase.maintenance().pendingPlaylistDeletions().forEach {
            completePlaylistDeletion(it.playlistId)
        }
        // A process can die after a channel swap commits but before identity reconciliation.
        // Rebind the stored catalog at startup without treating a partial swap as retirement.
        storage.playlists.getAll().forEach { content.repairPlaylist(it.id) }
    }

    suspend fun refresh(actor: Actor, id: Long, force: Boolean): PlaylistRefreshResultDto {
        requireAdmin(actor)
        val catalogChanged = content.refreshPlaylist(id) { playlists.refresh(id, force) }
        val refreshed = playlist(id)
        val epgStatus = if (refreshed.epgUrl == null) {
            PlaylistEpgRefreshStatus.NOT_CONFIGURED
        } else {
            try {
                epg.refresh(id, force)
                PlaylistEpgRefreshStatus.SUCCEEDED
            } catch (failure: Throwable) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                PlaylistEpgRefreshStatus.FAILED
            }
        }
        return PlaylistRefreshResultDto(refreshed.toApiDto(), catalogChanged, epgStatus)
    }

    suspend fun startRefresh(actor: Actor, id: Long, force: Boolean): PlaylistRefreshJobDto {
        requireAdmin(actor)
        playlist(id)
        val refreshId = UUID.randomUUID().toString()
        val queued = RefreshJob(id, PlaylistRefreshJobStatus.QUEUED)
        refreshJobsMutex.withLock {
            refreshJobs[refreshId] = queued
            pruneRefreshJobs()
        }
        refreshScope.launch {
            setRefreshJob(refreshId, queued.copy(status = PlaylistRefreshJobStatus.RUNNING))
            try {
                val result = refresh(actor, id, force)
                setRefreshJob(
                    refreshId,
                    queued.copy(
                        status = PlaylistRefreshJobStatus.SUCCEEDED,
                        result = result,
                    ),
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                refreshJobsMutex.withLock { refreshJobs.remove(refreshId) }
                throw cancelled
            } catch (_: Throwable) {
                setRefreshJob(
                    refreshId,
                    queued.copy(status = PlaylistRefreshJobStatus.FAILED),
                )
            }
        }
        return PlaylistRefreshJobDto(refreshId, queued.status, queued.result)
    }

    suspend fun refreshStatus(actor: Actor, id: Long, refreshId: String): PlaylistRefreshJobDto {
        requireAdmin(actor)
        val job = refreshJobsMutex.withLock { refreshJobs[refreshId] }
            ?.takeIf { it.playlistId == id }
            ?: throw ResourceNotFound("playlist refresh")
        return PlaylistRefreshJobDto(refreshId, job.status, job.result)
    }

    suspend fun clearProgress(actor: Actor, id: Long) = activity.clearResume(actor, id)

    suspend fun capabilities(actor: Actor, id: Long): PlaylistCapabilitiesDto {
        requireAccess(actor, id)
        val playlist = playlist(id)
        val operations = buildList {
            add(inAppOperation(PlaylistOperation.CLEAR_WATCH_PROGRESS))
            if (auth.hasCurrentAdminAuthority(actor)) {
                if (!playlist.isXtreamNative) {
                    add(inAppOperation(PlaylistOperation.CORRECT_CATEGORY_TYPE))
                }
                if (playlist.url != null || playlist.xtreamBase != null) {
                    add(inAppOperation(PlaylistOperation.REFRESH))
                }
                add(inAppOperation(PlaylistOperation.EDIT))
                add(inAppOperation(PlaylistOperation.DELETE))
                if (playlist.xtreamBase != null) {
                    add(inAppOperation(PlaylistOperation.VIEW_PROVIDER_ACCOUNT))
                }
            }
        }
        return PlaylistCapabilitiesDto(operations)
    }

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
        return when (val result = account.accountInfo(playlist(id), force)) {
            is AccountInfoResult.Fresh ->
                result.info.toDto(result.fetchedAtMs, stale = false)
            is AccountInfoResult.Stale ->
                result.info.toDto(result.fetchedAtMs, stale = true)
            is AccountInfoResult.Unavailable -> when (val cause = result.cause) {
                null -> throw ResourceNotFound("account", "No account API for this playlist")
                is XtreamAuthException -> throw cause
                else -> throw XtreamUnreachableException("Could not reach the provider account API")
            }
        }
    }

    suspend fun setGroupKind(actor: Actor, id: Long, request: GroupKindRequest) {
        // Admin, not merely granted. A category override is stored on the playlist and
        // re-applied at every refresh, so it changes what the catalog looks like for
        // everyone who can see it -- that is administration, however small the edit.
        requireAdmin(actor)
        require(!playlist(id).isXtreamNative) {
            "Native Xtream categories cannot be reclassified"
        }
        require(request.groupTitle.length <= MAX_GROUP_TITLE_CHARS) {
            "groupTitle must be at most $MAX_GROUP_TITLE_CHARS characters"
        }
        require(
            request.kind == null ||
                request.kind == ChannelKind.LIVE ||
                request.kind == ChannelKind.MOVIE ||
                request.kind == ChannelKind.SERIES,
        ) { "Unknown category type" }
        content.mutatePlaylist(id) {
            playlists.setGroupOverride(id, request.groupTitle, request.kind)
        }
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
        return content.withStablePlaylist(id) {
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
            FavoritesResolvedDto(live, movies, series.sortedBy { it.seriesKey.lowercase() })
        }
    }

    /** Every favorite owned by [actor], across the playlists currently visible to them. */
    suspend fun resolvedFavorites(actor: Actor): UserFavoritesResolvedDto {
        // Hold the granted catalogs stable across the identity and catalog reads. The same
        // current entitlement snapshot filters both the gates and every favorite row.
        val access = auth.playlistAccess(actor)
        val playlistIds = storage.playlists.getAll()
            .filter { access.allows(it.id) }
            .map(Playlist::id)
            .sorted()
        suspend fun resolve(index: Int): UserFavoritesResolvedDto =
            if (index == playlistIds.size) {
                resolveUserFavorites(actor, access)
            } else {
                content.withStablePlaylist(playlistIds[index]) { resolve(index + 1) }
            }
        return resolve(0)
    }

    private suspend fun resolveUserFavorites(
        actor: Actor,
        access: PlaylistAccess,
    ): UserFavoritesResolvedDto {
        val favorites = activity.authorizedFavorites(actor.userId, access)
        val channels = favorites.mapNotNull { it.currentChannelId }
            .distinct()
            .chunked(FAVORITE_RESOLUTION_BATCH_SIZE)
            .flatMap { storage.channels.getMany(it) }
            .associateBy { it.id }
        val resolvedChannels = favorites.mapNotNull { favorite ->
            channels[favorite.currentChannelId]?.let { favorite to it }
        }
        val live = resolvedChannels.filter { it.second.kind == ChannelKind.LIVE }
            .map { (favorite, channel) ->
                channel.toDto(cipher, favorite.contentId, actor.userId)
            }
        val movies = resolvedChannels.filter { it.second.kind == ChannelKind.MOVIE }
            .map { (favorite, channel) ->
                channel.toDto(cipher, favorite.contentId, actor.userId)
            }
        val series = resolveUserFavoriteSeries(
            actor,
            favorites.filter { it.kind == ChannelKind.SERIES },
        )
        return UserFavoritesResolvedDto(live, movies, series)
    }

    private suspend fun resolveUserFavoriteSeries(
        actor: Actor,
        favorites: List<AuthorizedFavorite>,
    ): List<UserFavoriteSeriesDto> {
        val resolved = mutableMapOf<String, UserFavoriteSeriesDto>()
        favorites.groupBy(AuthorizedFavorite::playlistId).forEach { (playlistId, playlistFavorites) ->
            val byFingerprint = playlistFavorites.associateBy(AuthorizedFavorite::providerFingerprint)
            storage.xtreamSeries.observeAll(playlistId).first().forEach { entry ->
                val favorite = byFingerprint[content.xtreamSeriesFingerprint(entry.seriesId)]
                    ?: return@forEach
                resolved[favorite.contentId] = UserFavoriteSeriesDto(
                    contentId = favorite.contentId,
                    playlistId = playlistId,
                    seriesKey = entry.name,
                    count = 0,
                    logo = cipher.encryptOrNull(entry.cover, actor.userId, playlistId),
                    groupTitle = entry.categoryName,
                    xtreamSeriesId = entry.seriesId.toString(),
                )
            }
            storage.channels.observeAllSeries(playlistId).first()
                .filterNot { it.seriesKey.startsWith("xs:") }
                .forEach { entry ->
                    val favorite = byFingerprint[content.m3uSeriesFingerprint(entry.seriesKey)]
                        ?: return@forEach
                    resolved[favorite.contentId] = UserFavoriteSeriesDto(
                        contentId = favorite.contentId,
                        playlistId = playlistId,
                        seriesKey = entry.seriesKey,
                        count = entry.count,
                        logo = cipher.encryptOrNull(entry.logo, actor.userId, playlistId),
                        groupTitle = entry.groupTitle,
                    )
                }
        }
        return favorites.mapNotNull { resolved[it.contentId] }
            .sortedBy { it.seriesKey.lowercase() }
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
        return content.withStablePlaylist(id) {
            val series = storage.xtreamSeries.get(id, seriesId) ?: throw ResourceNotFound("series")
            val failure = runCatching { xtream.ensureEpisodes(id, seriesId) }.exceptionOrNull()
            val episodes = storage.channels.observeEpisodes(id, xtreamSeriesKey(seriesId)).first()
            content.channels(episodes)
            XtreamSeriesDetailDto(
                series.toDto(cipher, content.xtreamSeries(series).contentId, actor.userId),
                channelDtos(actor, episodes),
                failure?.let { "Couldn't load episodes: ${ProviderSecrets.redact(it)}" }
                    ?.takeIf { episodes.isEmpty() },
            )
        }
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

    private suspend fun requireAdmin(actor: Actor) {
        if (!auth.hasCurrentAdminAuthority(actor)) throw ForbiddenApiException()
    }

    private suspend fun playlist(id: Long): Playlist =
        storage.playlists.get(id) ?: throw ResourceNotFound("playlist")

    private suspend fun completePlaylistDeletion(id: Long) {
        cleanup.playlistDeleting(id)
        content.deletePlaylist(id) {
            // Blob records cascade through content identities when the playlist disappears, so
            // delete their files first. The tombstone makes this external cleanup crash-safe.
            downloads.deletePlaylist(id)
            if (storage.playlists.get(id) != null) userDatabase.deleteCatalogPlaylist(id)
            userDatabase.maintenance().finishPlaylistDeletion(id)
        }
    }

    private suspend fun setRefreshJob(id: String, job: RefreshJob) {
        refreshJobsMutex.withLock {
            if (id in refreshJobs) refreshJobs[id] = job
        }
    }

    /** Retain bounded terminal history without ever evicting a refresh in flight. */
    private fun pruneRefreshJobs() {
        while (refreshJobs.size > MAX_REFRESH_JOBS) {
            val completed = refreshJobs.entries.firstOrNull {
                it.value.status == PlaylistRefreshJobStatus.SUCCEEDED ||
                    it.value.status == PlaylistRefreshJobStatus.FAILED
            } ?: return
            refreshJobs.remove(completed.key)
        }
    }

    override fun close() {
        refreshScope.cancel()
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
private const val MAX_GROUP_TITLE_CHARS = 500
private const val FAVORITE_RESOLUTION_BATCH_SIZE = 500
private val Playlist.mode: String get() = when {
    url != null -> "url"
    xtreamBase != null -> "xtream"
    else -> "file"
}

private fun Playlist.toApiDto() = PlaylistDto(
    id, name, mode, xtreamBase != null, lastRefreshedMs, channelCount,
)

private fun Playlist.toEditDto(): PlaylistEditDto {
    val fields = when (mode) {
        "xtream" -> listOf(
            PlaylistEditField.NAME,
            PlaylistEditField.SERVER,
            PlaylistEditField.USERNAME,
            PlaylistEditField.PASSWORD,
        )
        "url" -> listOf(
            PlaylistEditField.NAME,
            PlaylistEditField.URL,
            PlaylistEditField.EPG_URL,
        )
        else -> listOf(PlaylistEditField.NAME, PlaylistEditField.CONTENT)
    }
    val storedFields = buildList {
        if (!xtreamBase.isNullOrBlank()) add(PlaylistEditField.SERVER)
        if (!xtreamUser.isNullOrBlank()) add(PlaylistEditField.USERNAME)
        if (!xtreamPass.isNullOrBlank()) add(PlaylistEditField.PASSWORD)
        if (!url.isNullOrBlank()) add(PlaylistEditField.URL)
        if (!epgUrl.isNullOrBlank()) add(PlaylistEditField.EPG_URL)
        if (mode == "file" && channelCount > 0) add(PlaylistEditField.CONTENT)
    }
    return PlaylistEditDto(id, name, mode, fields, storedFields)
}

private fun inAppOperation(operation: String) = PlaylistOperationCapabilityDto(
    operation = operation,
    execution = PlaylistOperationExecution.IN_APP,
)

private fun PlaylistUpdateRequest.requireApplicableTo(mode: String) {
    when (mode) {
        "xtream" -> require(url == null && epgUrl == null && content == null) {
            "M3U and file fields do not apply to an Xtream playlist"
        }
        "url" -> require(
            server == null && username == null && password == null && content == null,
        ) { "Xtream and file fields do not apply to an M3U playlist" }
        "file" -> require(
            server == null && username == null && password == null &&
                url == null && epgUrl == null,
        ) { "Provider fields do not apply to a file playlist" }
        else -> throw IllegalArgumentException("Unknown mode")
    }
}

private fun String?.orKeeping(existing: String?): String =
    this?.trim()?.takeIf(String::isNotEmpty) ?: existing.orEmpty()

private const val MAX_REFRESH_JOBS = 128
