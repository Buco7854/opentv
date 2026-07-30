package com.buco7854.opentv.source

import com.buco7854.opentv.contract.ChannelDto
import com.buco7854.opentv.contract.ChannelPageDto
import com.buco7854.opentv.contract.EpisodePageDto
import com.buco7854.opentv.contract.FavoriteDto
import com.buco7854.opentv.contract.FavoritesResolvedDto
import com.buco7854.opentv.contract.GroupCountDto
import com.buco7854.opentv.contract.GuideEntryDto
import com.buco7854.opentv.contract.ProgrammeDto
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

class HubCatalogGateway internal constructor(
    override val source: SourceId.Hub,
    private val backend: HubCatalogBackend,
) : CatalogGateway {
    constructor(source: SourceId.Hub, registry: HubRegistry) :
        this(source, RegistryHubCatalogBackend(source, registry))

    override suspend fun traits(): SourceTraits {
        val capabilities = backend.capabilities().toCatalogCapabilities()
        return SourceTraits(
            hasXtreamSeries =
                PlaylistOperation.CORRECT_CATEGORY_TYPE !in capabilities.operations,
            hasGuide = true,
            hasAccountPanel =
                PlaylistOperation.VIEW_PROVIDER_ACCOUNT in capabilities.operations,
            favoritesAreServerSide = true,
            resumeIsServerSide = true,
            supportsRefresh = PlaylistOperation.REFRESH in capabilities.operations,
            supportsSourceEditing = false,
            usesXtreamCredentials = false,
            usesM3uUrl = false,
            isFileImport = false,
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

    override suspend fun nowAiring(): CatalogResult<Map<String, CatalogProgramme>> = hubCall {
        backend.nowAiring().associate {
            it.tvgId to CatalogProgramme(it.tvgId, it.title, it.description, it.startMs, it.endMs)
        }
    }

    override suspend fun guideIds(): CatalogResult<Set<String>> = hubCall {
        backend.guideIds().toSet()
    }

    override suspend fun favorites(offset: Int, limit: Int): CatalogResult<Page<CatalogItem>> =
        hubCall {
            val progress = progress()
            val nowAiring = backend.nowAiring().associateBy { it.tvgId }
            val guideIds = backend.guideIds().toSet()
            val resolved = backend.favoritesResolved()
            val items = buildList {
                addAll(resolved.live.map {
                    it.toCatalogItem(progress[it.contentId], image(it.logo)).copy(
                        hasGuide = it.xtreamStreamId != null || it.tvgId in guideIds,
                        nowAiring = nowAiring[it.tvgId]?.toCatalogProgramme(),
                    )
                })
                addAll(resolved.movies.map {
                    it.toCatalogItem(progress[it.contentId], image(it.logo))
                })
                addAll(resolved.series.map { it.toCatalogItem(image(it.logo)) })
            }
            Page(items.drop(offset).take(limit), items.size)
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
}

private fun ContentRef.hubContentId(): String =
    (this as? ContentRef.HubContent)?.contentId
        ?: throw IllegalArgumentException("A hub source requires a hub content reference")

internal interface HubCatalogBackend {
    val baseUrl: String
    suspend fun capabilities(): HubPlaylistCapabilities
    suspend fun clearProgress()
    suspend fun setGroupKind(groupTitle: String, kind: Int?)
    suspend fun groups(kind: Int): List<GroupCountDto>
    suspend fun channels(kind: Int, group: String, offset: Int, limit: Int, filter: String): ChannelPageDto
    suspend fun seriesGroups(group: String, offset: Int, limit: Int, filter: String): SeriesGroupPageDto
    suspend fun xtreamSeries(category: String, offset: Int, limit: Int, filter: String): XtreamSeriesPageDto
    suspend fun episodes(seriesKey: String, season: Int?, offset: Int, limit: Int): EpisodePageDto
    suspend fun xtreamSeriesDetail(seriesId: String): XtreamSeriesDetailDto
    suspend fun search(query: String): SearchResultsDto
    suspend fun nowAiring(): List<ProgrammeDto>
    suspend fun guideIds(): List<String>
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
    override suspend fun nowAiring() = call { nowAiring(it, source.playlistId) }
    override suspend fun guideIds() = call { guideIds(it, source.playlistId) }
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
