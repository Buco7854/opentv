package com.buco7854.opentv.source

import com.buco7854.opentv.contract.AccountInfoDto
import com.buco7854.opentv.contract.ChannelDto
import com.buco7854.opentv.contract.ChannelPageDto
import com.buco7854.opentv.contract.EpisodePageDto
import com.buco7854.opentv.contract.FavoriteDto
import com.buco7854.opentv.contract.FavoritesResolvedDto
import com.buco7854.opentv.contract.GroupCountDto
import com.buco7854.opentv.contract.GuideEntryDto
import com.buco7854.opentv.contract.ProgrammeDto
import com.buco7854.opentv.contract.PlaylistDeleteInfoDto
import com.buco7854.opentv.contract.PlaylistDetailDto
import com.buco7854.opentv.contract.PlaylistEditDto
import com.buco7854.opentv.contract.PlaylistEditField as WirePlaylistEditField
import com.buco7854.opentv.contract.PlaylistEpgRefreshStatus
import com.buco7854.opentv.contract.PlaylistRefreshJobDto
import com.buco7854.opentv.contract.PlaylistRefreshJobStatus
import com.buco7854.opentv.contract.PlaylistRefreshResultDto
import com.buco7854.opentv.contract.PlaylistUpdateRequest
import com.buco7854.opentv.contract.ResumePointDto
import com.buco7854.opentv.contract.SearchResultsDto
import com.buco7854.opentv.contract.SeriesGroupPageDto
import com.buco7854.opentv.contract.SeriesHitDto
import com.buco7854.opentv.contract.XtreamSeriesPageDto
import com.buco7854.opentv.contract.XtreamSeriesDetailDto
import com.buco7854.opentv.contract.PlaylistOperation as WirePlaylistOperation
import com.buco7854.opentv.core.model.ChannelKind
import com.buco7854.opentv.hub.HubCredentials
import com.buco7854.opentv.hub.HubEndpoints
import com.buco7854.opentv.hub.HubPlaylistCapabilities
import com.buco7854.opentv.hub.HubPlaylistOperation
import com.buco7854.opentv.hub.HubRegistry
import com.buco7854.opentv.hub.HubUnauthorizedException
import com.buco7854.opentv.hub.HubUnreachableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

class HubCatalogGateway internal constructor(
    override val source: SourceId.Hub,
    private val backend: HubCatalogBackend,
) : CatalogGateway {
    constructor(source: SourceId.Hub, registry: HubRegistry) :
        this(source, RegistryHubCatalogBackend(source, registry))

    override suspend fun traits(): SourceTraits {
        val detail = backend.detail()
        val capabilities = backend.capabilities().toCatalogCapabilities()
        val edit = if (PlaylistOperation.EDIT in capabilities.operations) {
            backend.edit()
        } else {
            null
        }
        return SourceTraits(
            // Detail is entitled to every playlist viewer. Neither identity nor source type
            // may depend on administrator-only edit/category-correction capabilities.
            title = detail.playlist.name,
            hasXtreamSeries = detail.isXtreamNative,
            hasGuide = true,
            hasAccountPanel =
                PlaylistOperation.VIEW_PROVIDER_ACCOUNT in capabilities.operations,
            favoritesAreServerSide = true,
            resumeIsServerSide = true,
            supportsRefresh = PlaylistOperation.REFRESH in capabilities.operations,
            supportsSourceEditing = edit != null,
            usesXtreamCredentials = edit?.mode == "xtream",
            usesM3uUrl = edit?.mode == "url",
            isFileImport = edit?.mode == "file",
        )
    }

    override suspend fun playlistCapabilities(): CatalogResult<PlaylistCapabilities> = hubCall {
        backend.capabilities().toCatalogCapabilities()
    }

    override suspend fun clearWatchProgress(): CatalogResult<Unit> = hubCall {
        backend.clearProgress()
    }

    override suspend fun correctCategoryType(
        groupTitle: String,
        kind: Int?,
    ): CatalogResult<Unit> = hubCall {
        backend.setGroupKind(groupTitle, kind)
    }

    override suspend fun playlistEditForm(): CatalogResult<PlaylistEditForm> = hubCall {
        backend.edit().toCatalogEditForm()
    }

    override suspend fun updatePlaylist(update: PlaylistEditUpdate): CatalogResult<Unit> = hubCall {
        backend.update(update.toWire())
    }

    override suspend fun refreshPlaylist(
        force: Boolean,
        onProgress: (PlaylistRefreshProgress) -> Unit,
    ): CatalogResult<PlaylistRefreshResult> = hubCall {
        var job = backend.startRefresh(force)
        var lastStatus: String? = null
        while (true) {
            if (job.status != lastStatus) {
                when (job.status) {
                    PlaylistRefreshJobStatus.QUEUED ->
                        onProgress(PlaylistRefreshProgress.Queued)
                    PlaylistRefreshJobStatus.RUNNING ->
                        onProgress(PlaylistRefreshProgress.Running)
                }
                lastStatus = job.status
            }
            when (job.status) {
                PlaylistRefreshJobStatus.SUCCEEDED -> {
                    val result = requireNotNull(job.result) {
                        "A successful playlist refresh must carry its result"
                    }.toCatalogRefreshResult()
                    onProgress(PlaylistRefreshProgress.Finished(result))
                    return@hubCall result
                }
                PlaylistRefreshJobStatus.FAILED -> throw PlaylistRefreshFailedException()
                PlaylistRefreshJobStatus.QUEUED,
                PlaylistRefreshJobStatus.RUNNING -> {
                    delay(REFRESH_POLL_INTERVAL_MS)
                    job = backend.refreshStatus(job.id)
                }
                else -> throw IllegalArgumentException(
                    "Unknown playlist refresh job status: ${job.status}",
                )
            }
        }
        error("unreachable")
    }

    override suspend fun playlistDeleteInfo(): CatalogResult<PlaylistDeleteInfo> = hubCall {
        backend.deleteInfo().toCatalogDeleteInfo()
    }

    override suspend fun deletePlaylist(): CatalogResult<Unit> = hubCall {
        backend.delete()
    }

    override suspend fun providerAccount(force: Boolean): CatalogResult<ProviderAccountInfo> =
        hubCall {
            backend.account(force).toCatalogAccount()
        }

    override suspend fun groups(kind: Int): CatalogResult<List<CatalogGroup>> = hubCall {
        backend.groups(kind).map { CatalogGroup(it.groupTitle, it.count) }
    }

    override suspend fun channels(
        kind: Int,
        group: String,
        offset: Int,
        limit: Int,
        filter: String,
    ): CatalogResult<Page<CatalogItem>> = hubCall {
        val progress = progress()
        val page = backend.channels(kind, group, offset, limit, filter)
        Page(page.items.map { row ->
            CatalogItem(
                ref = ContentRef.HubContent(row.contentId),
                title = row.name,
                imageUrl = image(row.logo),
                kind = row.kind,
                group = group,
                tvgId = row.tvgId,
                catchupDays = row.catchupDays,
                hasCatchup = row.hasCatchup,
                hasGuide = row.xtreamStreamId != null,
                progress = progress[row.contentId],
            )
        }, page.total)
    }

    override suspend fun seriesGroups(
        group: String,
        offset: Int,
        limit: Int,
        filter: String,
    ): CatalogResult<Page<CatalogItem>> = hubCall {
        val page = backend.seriesGroups(group, offset, limit, filter)
        Page(page.items.map {
            CatalogItem(
                ref = ContentRef.HubContent(it.contentId),
                title = it.seriesKey,
                imageUrl = image(it.logo),
                kind = ChannelKind.SERIES,
                group = it.groupTitle,
                seriesKey = it.seriesKey,
                count = it.count,
            )
        }, page.total)
    }

    override suspend fun xtreamSeries(
        category: String,
        offset: Int,
        limit: Int,
        filter: String,
    ): CatalogResult<Page<CatalogItem>> = hubCall {
        val page = backend.xtreamSeries(category, offset, limit, filter)
        if (page.total > 0 || page.items.isNotEmpty()) {
            Page(page.items.map {
                CatalogItem(
                    ref = ContentRef.HubContent(it.contentId),
                    title = it.name,
                    imageUrl = image(it.cover),
                    kind = ChannelKind.SERIES,
                    group = category,
                    seriesKey = "xs:${it.seriesId}",
                    seriesId = it.seriesId,
                    genre = it.genre,
                    rating = it.rating,
                )
            }, page.total)
        } else {
            val groups = backend.seriesGroups(category, offset, limit, filter)
            Page(groups.items.map {
                CatalogItem(
                    ref = ContentRef.HubContent(it.contentId),
                    title = it.seriesKey,
                    imageUrl = image(it.logo),
                    kind = ChannelKind.SERIES,
                    group = it.groupTitle,
                    seriesKey = it.seriesKey,
                    count = it.count,
                )
            }, groups.total)
        }
    }

    override suspend fun episodes(
        seriesKey: String,
        season: Int?,
        offset: Int,
        limit: Int,
    ): CatalogResult<Page<CatalogItem>> = hubCall {
        val progress = progress()
        val page = backend.episodes(seriesKey, season, offset, limit)
        Page(
            items = page.items.map {
                CatalogItem(
                    ref = ContentRef.HubContent(it.contentId),
                    title = it.name,
                    imageUrl = image(it.logo),
                    kind = it.kind,
                    group = it.groupTitle,
                    seriesKey = it.seriesKey,
                    season = it.season,
                    episode = it.episode,
                    durationSecs = it.durationSecs,
                    airDate = it.airDate,
                    progress = progress[it.contentId],
                )
            },
            total = page.total,
            seasons = page.seasons,
        )
    }

    override suspend fun search(query: String): CatalogResult<CatalogSearchResult> = hubCall {
        val progress = progress()
        val result = backend.search(query)
        CatalogSearchResult(
            live = result.live.map { it.toCatalogItem(progress[it.contentId], image(it.logo)) },
            movies = result.movies.map { it.toCatalogItem(progress[it.contentId], image(it.logo)) },
            series = result.series.map { it.toCatalogItem(image(it.logo)) },
        )
    }

    override suspend fun nowAiring(
        tvgIds: Set<String>,
    ): CatalogResult<Map<String, CatalogProgramme>> = hubCall {
        backend.nowAiring(tvgIds.toList()).associate {
            it.tvgId to CatalogProgramme(it.tvgId, it.title, it.description, it.startMs, it.endMs)
        }
    }

    override suspend fun guideIds(tvgIds: Set<String>): CatalogResult<Set<String>> = hubCall {
        backend.guideIds(tvgIds.toList()).toSet()
    }

    override suspend fun favorites(offset: Int, limit: Int): CatalogResult<Page<CatalogItem>> =
        hubCall {
            val progress = progress()
            val resolved = backend.favoritesResolved()
            val items = buildList {
                addAll(resolved.live.map {
                    it.toCatalogItem(progress[it.contentId], image(it.logo))
                })
                addAll(resolved.movies.map {
                    it.toCatalogItem(progress[it.contentId], image(it.logo))
                })
                addAll(resolved.series.map { it.toCatalogItem(image(it.logo)) })
            }
            val liveContentIds = resolved.live.mapTo(mutableSetOf(), ChannelDto::contentId)
            val pageItems = items.drop(offset).take(limit)
            val tvgIds = pageItems.asSequence()
                .filter { it.ref.hubContentId() in liveContentIds }
                .mapNotNull(CatalogItem::tvgId)
                .distinct()
                .take(MAX_FAVORITE_DECORATION_CHANNELS)
                .toList()
            val nowAiring = if (tvgIds.isEmpty()) emptyMap() else {
                optionalDecoration(emptyMap()) {
                    backend.nowAiring(tvgIds).associateBy { it.tvgId }
                }
            }
            val guideIds = if (tvgIds.isEmpty()) emptySet() else {
                optionalDecoration(emptySet()) {
                    backend.guideIds(tvgIds).toSet()
                }
            }
            Page(
                pageItems.map { item ->
                    if (item.ref.hubContentId() !in liveContentIds) item else item.copy(
                        hasGuide = item.hasGuide || item.tvgId in guideIds,
                        nowAiring = nowAiring[item.tvgId]?.toCatalogProgramme(),
                    )
                },
                items.size,
            )
        }

    override suspend fun resumePoints(): CatalogResult<List<CatalogResumePoint>> = hubCall {
        backend.resume().map {
            CatalogResumePoint(
                ref = ContentRef.HubContent(it.contentId),
                positionMs = it.positionMs,
                durationMs = it.durationMs,
                updatedMs = it.updatedMs,
            )
        }
    }

    override suspend fun guideFor(ref: ContentRef): CatalogResult<List<CatalogGuideEntry>> =
        hubCall {
            val contentId = ref.hubContentId()
            backend.guide(contentId).map {
                CatalogGuideEntry(it.title, it.description, it.startMs, it.endMs, it.replayable)
            }
        }

    override suspend fun detail(ref: ContentRef): CatalogResult<CatalogDetail?> = hubCall {
        val contentId = ref.hubContentId()
        val progress = progress()
        val channel = backend.content(contentId)
        val item = channel.toCatalogItem(progress[contentId], image(channel.logo))
        CatalogDetail(item, description = channel.description)
    }

    override suspend fun seriesDetail(
        ref: ContentRef,
        seriesKey: String,
        seriesId: String?,
    ): CatalogResult<CatalogDetail?> = hubCall {
        val contentId = ref.hubContentId()
        if (seriesId != null) {
            val result = backend.xtreamSeriesDetail(seriesId)
            require(result.series.contentId == contentId) {
                "Series identity does not match the requested content"
            }
            require(result.series.playlistId == source.playlistId) {
                "Series belongs to another playlist"
            }
            result.error?.let { throw IllegalStateException(it) }
            val series = result.series
            CatalogDetail(
                item = CatalogItem(
                    ref = ref,
                    title = series.name,
                    imageUrl = image(series.cover),
                    kind = ChannelKind.SERIES,
                    group = series.categoryName,
                    seriesKey = "xs:${series.seriesId}",
                    seriesId = series.seriesId,
                    count = result.episodes.size,
                    genre = series.genre,
                    rating = series.rating,
                ),
                description = series.plot,
                cast = series.castNames,
            )
        } else {
            val page = backend.episodes(seriesKey, season = null, offset = 0, limit = 1)
            require(page.seriesContentId == contentId) {
                "Series identity does not match the requested content"
            }
            val first = page.items.firstOrNull()
            CatalogDetail(
                item = CatalogItem(
                    ref = ref,
                    title = seriesKey,
                    imageUrl = image(first?.logo),
                    kind = ChannelKind.SERIES,
                    group = page.groupTitle ?: first?.groupTitle,
                    seriesKey = seriesKey,
                    count = page.total,
                ),
            )
        }
    }

    override suspend fun isFavorite(ref: ContentRef): CatalogResult<Boolean> = hubCall {
        val contentId = ref.hubContentId()
        backend.favorites().any { it.contentId == contentId }
    }

    override suspend fun toggleFavorite(ref: ContentRef): CatalogResult<Boolean> = hubCall {
        val contentId = ref.hubContentId()
        val favorite = backend.favorites().any { it.contentId == contentId }
        if (favorite) {
            backend.removeFavorite(contentId)
            false
        } else {
            backend.addFavorite(contentId)
            true
        }
    }

    override suspend fun setFavorite(
        ref: ContentRef,
        favorite: Boolean,
    ): CatalogResult<Boolean> = hubCall {
        val contentId = ref.hubContentId()
        if (favorite) backend.addFavorite(contentId) else backend.removeFavorite(contentId)
        favorite
    }

    private suspend fun progress(): Map<String, Float> =
        backend.resume().mapNotNull {
            if (it.durationMs <= 0) null
            else it.contentId to (it.positionMs.toFloat() / it.durationMs).coerceIn(0f, 1f)
        }.toMap()

    private fun image(token: String?): String? =
        token?.let { HubEndpoints.image(backend.baseUrl, it) }

    private suspend fun <T> hubCall(block: suspend () -> T): CatalogResult<T> = try {
        CatalogResult.Success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: HubUnauthorizedException) {
        CatalogResult.SignedOut
    } catch (_: HubUnreachableException) {
        CatalogResult.Unreachable
    } catch (error: Throwable) {
        CatalogResult.Failed(error)
    }

    private suspend fun <T> optionalDecoration(fallback: T, block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        fallback
    }
}

private fun ContentRef.hubContentId(): String =
    (this as? ContentRef.HubContent)?.contentId
        ?: throw IllegalArgumentException("A hub source requires a hub content reference")

internal interface HubCatalogBackend {
    val baseUrl: String
    suspend fun detail(): PlaylistDetailDto
    suspend fun capabilities(): HubPlaylistCapabilities
    suspend fun edit(): PlaylistEditDto
    suspend fun update(request: PlaylistUpdateRequest)
    suspend fun startRefresh(force: Boolean): PlaylistRefreshJobDto
    suspend fun refreshStatus(refreshId: String): PlaylistRefreshJobDto
    suspend fun deleteInfo(): PlaylistDeleteInfoDto
    suspend fun delete()
    suspend fun account(force: Boolean): AccountInfoDto
    suspend fun clearProgress()
    suspend fun setGroupKind(groupTitle: String, kind: Int?)
    suspend fun groups(kind: Int): List<GroupCountDto>
    suspend fun channels(kind: Int, group: String, offset: Int, limit: Int, filter: String): ChannelPageDto
    suspend fun seriesGroups(group: String, offset: Int, limit: Int, filter: String): SeriesGroupPageDto
    suspend fun xtreamSeries(category: String, offset: Int, limit: Int, filter: String): XtreamSeriesPageDto
    suspend fun episodes(seriesKey: String, season: Int?, offset: Int, limit: Int): EpisodePageDto
    suspend fun xtreamSeriesDetail(seriesId: String): XtreamSeriesDetailDto
    suspend fun search(query: String): SearchResultsDto
    suspend fun nowAiring(tvgIds: List<String>): List<ProgrammeDto>
    suspend fun guideIds(tvgIds: List<String>): List<String>
    suspend fun favorites(): List<FavoriteDto>
    suspend fun favoritesResolved(): FavoritesResolvedDto
    suspend fun addFavorite(contentId: String)
    suspend fun removeFavorite(contentId: String)
    suspend fun resume(): List<ResumePointDto>
    suspend fun content(contentId: String): ChannelDto
    suspend fun guide(contentId: String): List<GuideEntryDto>
}

private class RegistryHubCatalogBackend(
    private val source: SourceId.Hub,
    private val registry: HubRegistry,
) : HubCatalogBackend {
    private suspend fun client() = registry.clientFor(source.hubId)
        ?: throw HubUnauthorizedException("hub_missing", "Hub connection is not available")

    override val baseUrl: String
        get() = cachedBaseUrl ?: throw IllegalStateException("Hub has not been loaded")

    @Volatile
    private var cachedBaseUrl: String? = null

    private suspend fun <T> call(block: suspend com.buco7854.opentv.hub.HubApi.(HubCredentials) -> T): T {
        val client = client()
        cachedBaseUrl = client.baseUrl
        return client.call(block)
    }

    override suspend fun capabilities() =
        call { playlistCapabilities(it, source.playlistId) }
    override suspend fun detail() =
        call { playlist(it, source.playlistId) }
    override suspend fun edit() =
        call { playlistEdit(it, source.playlistId) }
    override suspend fun update(request: PlaylistUpdateRequest) {
        call { updatePlaylist(it, source.playlistId, request) }
    }
    override suspend fun startRefresh(force: Boolean) =
        call { startPlaylistRefresh(it, source.playlistId, force) }
    override suspend fun refreshStatus(refreshId: String) =
        call { playlistRefreshStatus(it, source.playlistId, refreshId) }
    override suspend fun deleteInfo() =
        call { playlistDeleteInfo(it, source.playlistId) }
    override suspend fun delete() {
        call { deletePlaylist(it, source.playlistId) }
    }
    override suspend fun account(force: Boolean) =
        call { playlistAccount(it, source.playlistId, force) }
    override suspend fun clearProgress() =
        call { clearPlaylistProgress(it, source.playlistId) }
    override suspend fun setGroupKind(groupTitle: String, kind: Int?) =
        call { setPlaylistGroupKind(it, source.playlistId, groupTitle, kind) }
    override suspend fun groups(kind: Int) = call { groups(it, source.playlistId, kind) }
    override suspend fun channels(kind: Int, group: String, offset: Int, limit: Int, filter: String) =
        call { channels(it, source.playlistId, kind, group, offset, limit, filter) }
    override suspend fun seriesGroups(group: String, offset: Int, limit: Int, filter: String) =
        call { seriesGroups(it, source.playlistId, group, offset, limit, filter) }
    override suspend fun xtreamSeries(category: String, offset: Int, limit: Int, filter: String) =
        call { xtreamSeries(it, source.playlistId, category, offset, limit, filter) }
    override suspend fun episodes(seriesKey: String, season: Int?, offset: Int, limit: Int) =
        call { episodes(it, source.playlistId, seriesKey, season, offset, limit) }
    override suspend fun xtreamSeriesDetail(seriesId: String) =
        call { xtreamSeriesDetail(it, source.playlistId, seriesId) }
    override suspend fun search(query: String) = call { search(it, source.playlistId, query) }
    override suspend fun nowAiring(tvgIds: List<String>) =
        call { nowAiring(it, source.playlistId, tvgIds) }
    override suspend fun guideIds(tvgIds: List<String>) =
        call { guideIds(it, source.playlistId, tvgIds) }
    override suspend fun favorites() = call { favorites(it, source.playlistId) }
    override suspend fun favoritesResolved() = call { favoritesResolved(it, source.playlistId) }
    override suspend fun addFavorite(contentId: String) =
        call { addFavorite(it, source.playlistId, contentId) }
    override suspend fun removeFavorite(contentId: String) =
        call { removeFavorite(it, source.playlistId, contentId) }
    override suspend fun resume() = call { resume(it) }
    override suspend fun content(contentId: String) = call { content(it, contentId) }
    override suspend fun guide(contentId: String) = call { contentGuide(it, contentId) }
}

private fun HubPlaylistCapabilities.toCatalogCapabilities(): PlaylistCapabilities =
    PlaylistCapabilities(
        operations.mapNotNull { (operation, availability) ->
            val mappedOperation = when (operation) {
                WirePlaylistOperation.REFRESH -> PlaylistOperation.REFRESH
                WirePlaylistOperation.EDIT -> PlaylistOperation.EDIT
                WirePlaylistOperation.DELETE -> PlaylistOperation.DELETE
                WirePlaylistOperation.CLEAR_WATCH_PROGRESS ->
                    PlaylistOperation.CLEAR_WATCH_PROGRESS
                WirePlaylistOperation.CORRECT_CATEGORY_TYPE ->
                    PlaylistOperation.CORRECT_CATEGORY_TYPE
                WirePlaylistOperation.VIEW_PROVIDER_ACCOUNT ->
                    PlaylistOperation.VIEW_PROVIDER_ACCOUNT
                else -> null
            } ?: return@mapNotNull null
            val mappedAvailability = when (availability) {
                HubPlaylistOperation.InApp -> PlaylistOperationAvailability.InApp
                is HubPlaylistOperation.Browser ->
                    PlaylistOperationAvailability.Browser(availability.url)
            }
            mappedOperation to mappedAvailability
        }.toMap(),
    )

private fun PlaylistEditDto.toCatalogEditForm() = PlaylistEditForm(
    id = id,
    name = name,
    mode = when (mode) {
        "xtream" -> PlaylistEditMode.XTREAM
        "url" -> PlaylistEditMode.M3U_URL
        "file" -> PlaylistEditMode.FILE
        else -> throw IllegalArgumentException("Unknown playlist edit mode: $mode")
    },
    fields = fields.mapTo(linkedSetOf(), ::playlistEditField),
    storedFields = storedFields.mapTo(linkedSetOf(), ::playlistEditField),
)

private fun playlistEditField(field: String): PlaylistEditField = when (field) {
    WirePlaylistEditField.NAME -> PlaylistEditField.NAME
    WirePlaylistEditField.SERVER -> PlaylistEditField.SERVER
    WirePlaylistEditField.USERNAME -> PlaylistEditField.USERNAME
    WirePlaylistEditField.PASSWORD -> PlaylistEditField.PASSWORD
    WirePlaylistEditField.URL -> PlaylistEditField.URL
    WirePlaylistEditField.EPG_URL -> PlaylistEditField.EPG_URL
    WirePlaylistEditField.CONTENT -> PlaylistEditField.CONTENT
    else -> throw IllegalArgumentException("Unknown playlist edit field: $field")
}

private fun PlaylistEditUpdate.toWire() = PlaylistUpdateRequest(
    name = name,
    server = server,
    username = username,
    password = password,
    url = url,
    epgUrl = epgUrl,
    content = content,
)

private fun PlaylistRefreshResultDto.toCatalogRefreshResult() = PlaylistRefreshResult(
    catalogChanged = catalogChanged,
    epg = when (epgStatus) {
        PlaylistEpgRefreshStatus.SUCCEEDED -> PlaylistEpgRefreshOutcome.SUCCEEDED
        PlaylistEpgRefreshStatus.FAILED -> PlaylistEpgRefreshOutcome.FAILED
        PlaylistEpgRefreshStatus.NOT_CONFIGURED -> PlaylistEpgRefreshOutcome.NOT_CONFIGURED
        else -> throw IllegalArgumentException("Unknown EPG refresh status: $epgStatus")
    },
    lastRefreshedMs = playlist.lastRefreshedMs,
    channelCount = playlist.channelCount,
)

private fun PlaylistDeleteInfoDto.toCatalogDeleteInfo() =
    PlaylistDeleteInfo(id, name, warning)

private fun AccountInfoDto.toCatalogAccount() = ProviderAccountInfo(
    activeConnections = activeConnections,
    maxConnections = maxConnections,
    status = status,
    expiresAtMs = expiresAtMs,
    isTrial = isTrial,
    createdAtMs = createdAtMs,
    timezone = timezone,
    fetchedAtMs = fetchedAtMs,
    stale = stale,
)

private const val REFRESH_POLL_INTERVAL_MS = 500L
private const val MAX_FAVORITE_DECORATION_CHANNELS = 1_000

private fun ChannelDto.toCatalogItem(progress: Float?, imageUrl: String?) = CatalogItem(
    ref = ContentRef.HubContent(contentId),
    title = name,
    imageUrl = imageUrl,
    kind = kind,
    group = groupTitle,
    seriesKey = seriesKey,
    season = season,
    episode = episode,
    durationSecs = durationSecs,
    tvgId = tvgId,
    airDate = airDate,
    catchupDays = catchupDays,
    hasCatchup = hasCatchup,
    hasGuide = xtreamStreamId != null,
    progress = progress,
)

private fun ProgrammeDto.toCatalogProgramme() =
    CatalogProgramme(tvgId, title, description, startMs, endMs)

private fun SeriesHitDto.toCatalogItem(imageUrl: String?) = CatalogItem(
    ref = ContentRef.HubContent(contentId),
    title = seriesKey,
    imageUrl = imageUrl,
    kind = ChannelKind.SERIES,
    group = groupTitle,
    seriesKey = xtreamSeriesId?.let { "xs:$it" } ?: seriesKey,
    seriesId = xtreamSeriesId,
    count = count,
)
