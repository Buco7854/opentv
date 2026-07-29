package com.buco7854.opentv.source

import com.buco7854.opentv.core.model.Channel
import com.buco7854.opentv.core.model.ChannelKind
import com.buco7854.opentv.core.model.GroupCount
import com.buco7854.opentv.core.model.Playlist
import com.buco7854.opentv.core.model.Programme
import com.buco7854.opentv.core.model.ResumePoint
import com.buco7854.opentv.core.model.SeriesGroup
import com.buco7854.opentv.core.model.XtreamSeries
import com.buco7854.opentv.core.model.hasGuide
import com.buco7854.opentv.core.repo.EpgRepository
import com.buco7854.opentv.core.repo.FavoriteRef
import com.buco7854.opentv.core.repo.FavoriteRepository
import com.buco7854.opentv.core.repo.GuideEntry
import com.buco7854.opentv.core.repo.ResumeRepository
import com.buco7854.opentv.core.repo.XtreamRepository
import com.buco7854.opentv.core.repo.xtreamFavoriteKey
import com.buco7854.opentv.core.storage.ChannelListing
import com.buco7854.opentv.core.storage.ListingPage
import com.buco7854.opentv.core.storage.Storage
import com.buco7854.opentv.core.storage.XtreamSeriesListing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

class LocalCatalogGateway internal constructor(
    override val source: SourceId.LocalPlaylist,
    private val traitsProvider: suspend () -> SourceTraits,
    private val backend: LocalCatalogBackend,
) : CatalogGateway {
    internal constructor(
        source: SourceId.LocalPlaylist,
        traits: SourceTraits,
        backend: LocalCatalogBackend,
    ) : this(source, traitsProvider = { traits }, backend)

    constructor(
        source: SourceId.LocalPlaylist,
        storage: Storage,
        xtream: XtreamRepository,
        favorites: FavoriteRepository,
        resume: ResumeRepository,
        epg: EpgRepository,
    ) : this(
        source,
        traitsProvider = {
            localSourceTraits(storage.playlists.get(source.playlistId))
        },
        StorageLocalCatalogBackend(source.playlistId, storage, xtream, favorites, resume, epg),
    )

    override suspend fun traits(): SourceTraits = traitsProvider()

    override suspend fun groups(kind: Int): CatalogResult<List<CatalogGroup>> = localCall {
        val rows = backend.groups(kind, traits().hasXtreamSeries && kind == ChannelKind.SERIES)
        rows.map { CatalogGroup(it.groupTitle, it.count) }
    }

    override suspend fun channels(
        kind: Int,
        group: String,
        offset: Int,
        limit: Int,
        filter: String,
    ): CatalogResult<Page<CatalogItem>> = localCall {
        val progress = backend.progress()
        backend.channels(kind, group, filter, limit, offset).mapPage {
            it.toCatalogItem(progress[it.url])
        }
    }

    override suspend fun seriesGroups(
        group: String,
        offset: Int,
        limit: Int,
        filter: String,
    ): CatalogResult<Page<CatalogItem>> = localCall {
        backend.seriesGroups(group, filter, limit, offset).mapPage(SeriesGroup::toCatalogItem)
    }

    override suspend fun xtreamSeries(
        category: String,
        offset: Int,
        limit: Int,
        filter: String,
    ): CatalogResult<Page<CatalogItem>> = localCall {
        backend.xtreamSeries(category, filter, limit, offset)
            .mapPage { it.toCatalogItem().copy(group = category) }
    }

    override suspend fun episodes(
        seriesKey: String,
        season: Int?,
        offset: Int,
        limit: Int,
    ): CatalogResult<Page<CatalogItem>> = localCall {
        seriesKey.removePrefix("xs:")
            .takeIf { seriesKey.startsWith("xs:") }
            ?.toLongOrNull()
            ?.let { backend.ensureEpisodes(it) }
        val progress = backend.progress()
        backend.episodes(seriesKey, season, limit, offset).mapPage {
            it.toCatalogItem(progress[it.url])
        }
    }

    override suspend fun search(query: String): CatalogResult<CatalogSearchResult> = localCall {
        val (channels, panelSeries) = backend.search(query)
        val progress = backend.progress()
        val m3uSeries = channels.asSequence()
            .filter { it.kind == ChannelKind.SERIES }
            .filterNot { it.seriesKey?.startsWith("xs:") == true }
            .groupBy { it.seriesKey ?: it.name }
            .map { (key, episodes) ->
                CatalogItem(
                    ref = ContentRef.LocalUrl(key, 0),
                    title = key,
                    imageUrl = episodes.firstOrNull { it.logo != null }?.logo,
                    kind = ChannelKind.SERIES,
                    group = episodes.first().groupTitle,
                    seriesKey = key,
                    count = episodes.size,
                )
            }
        CatalogSearchResult(
            live = channels.filter { it.kind == ChannelKind.LIVE }
                .map { it.toCatalogItem(progress[it.url]) },
            movies = channels.filter { it.kind == ChannelKind.MOVIE }
                .map { it.toCatalogItem(progress[it.url]) },
            series = panelSeries.map(XtreamSeries::toCatalogItem) + m3uSeries,
        )
    }

    override suspend fun nowAiring(): CatalogResult<Map<String, CatalogProgramme>> = localCall {
        backend.nowAiring().mapValues { (_, programme) -> programme.toCatalogProgramme() }
    }

    override suspend fun guideIds(): CatalogResult<Set<String>> = localCall {
        backend.guideIds()
    }

    override suspend fun favorites(offset: Int, limit: Int): CatalogResult<Page<CatalogItem>> =
        localCall {
            val items = backend.favoriteItems()
            Page(items.drop(offset).take(limit), items.size)
        }

    override suspend fun resumePoints(): CatalogResult<List<CatalogResumePoint>> = localCall {
        backend.resumePoints().map {
            CatalogResumePoint(
                ref = ContentRef.LocalUrl(it.url, 0),
                positionMs = it.positionMs,
                durationMs = it.durationMs,
                updatedMs = it.updatedMs,
            )
        }
    }

    override suspend fun guideFor(ref: ContentRef): CatalogResult<List<CatalogGuideEntry>> =
        localCall {
            val local = ref as? ContentRef.LocalUrl
                ?: throw IllegalArgumentException("A local source requires a local content reference")
            val channel = localChannel(local)
                ?: throw NoSuchElementException("Local channel not found")
            backend.guide(channel).map(GuideEntry::toCatalogGuideEntry)
        }

    override suspend fun detail(ref: ContentRef): CatalogResult<CatalogDetail?> = localCall {
        val local = ref as? ContentRef.LocalUrl
            ?: throw IllegalArgumentException("A local source requires a local content reference")
        localChannel(local)?.let { channel ->
            CatalogDetail(channel.toCatalogItem(), description = channel.description)
        } ?: backend.detail(local.copy(channelId = 0))
    }

    override suspend fun seriesDetail(
        ref: ContentRef,
        seriesKey: String,
        seriesId: String?,
    ): CatalogResult<CatalogDetail?> = localCall {
        val local = ref as? ContentRef.LocalUrl
            ?: throw IllegalArgumentException("A local source requires a local content reference")
        require(local.channelId == 0L) { "A series reference must not identify a channel row" }
        val localSeriesId = seriesId?.toLongOrNull()?.takeIf { it > 0 }
        if (seriesId != null) {
            require(localSeriesId?.toString() == seriesId) {
                "Invalid Xtream series id"
            }
            require(local.url == xtreamFavoriteKey(localSeriesId) && seriesKey == "xs:$seriesId") {
                "Series identity does not match the requested Xtream series"
            }
        } else {
            require(local.url == seriesKey) {
                "Series identity does not match the requested series key"
            }
        }
        backend.seriesDetail(seriesKey, localSeriesId)
    }

    override suspend fun isFavorite(ref: ContentRef): CatalogResult<Boolean> = localCall {
        val (key, _) = favoriteIdentity(ref)
        backend.isFavorite(key)
    }

    override suspend fun toggleFavorite(ref: ContentRef): CatalogResult<Boolean> = localCall {
        val (key, kind) = favoriteIdentity(ref)
        backend.toggleFavorite(key, kind)
    }

    override suspend fun setFavorite(
        ref: ContentRef,
        favorite: Boolean,
    ): CatalogResult<Boolean> = localCall {
        val (key, kind) = favoriteIdentity(ref)
        backend.setFavorite(key, kind, favorite)
        favorite
    }

    private suspend fun favoriteIdentity(ref: ContentRef): Pair<String, Int> {
        val local = ref as? ContentRef.LocalUrl
            ?: throw IllegalArgumentException("A local source requires a local content reference")
        if (local.url.startsWith("x:") && local.url.removePrefix("x:").toLongOrNull() != null) {
            return local.url to ChannelKind.SERIES
        }
        if (local.channelId == 0L && backend.hasSeries(local.url)) {
            return local.url to ChannelKind.SERIES
        }
        localChannel(local)?.let { channel ->
            val key = if (channel.kind == ChannelKind.SERIES) {
                channel.seriesKey ?: channel.url
            } else {
                channel.url
            }
            return key to channel.kind
        }
        throw NoSuchElementException("Local content not found")
    }

    private suspend fun localChannel(ref: ContentRef.LocalUrl): Channel? {
        fun Channel.matchesReference() =
            playlistId == source.playlistId && url == ref.url

        return backend.channel(ref)?.takeIf { it.matchesReference() }
            ?: ref.channelId.takeIf { it != 0L }
                ?.let { backend.channel(ref.copy(channelId = 0)) }
                ?.takeIf { it.matchesReference() }
    }

    private suspend fun <T> localCall(block: suspend () -> T): CatalogResult<T> = try {
        CatalogResult.Success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        CatalogResult.Failed(error)
    }
}

internal fun localSourceTraits(playlist: Playlist?): SourceTraits {
    val isXtream = playlist?.url == null && playlist?.xtreamBase != null
    val isM3uUrl = playlist?.url != null
    val isFile = playlist != null && playlist.url == null && playlist.xtreamBase == null
    return SourceTraits(
        hasXtreamSeries = isXtream,
        hasGuide = true,
        hasAccountPanel = playlist?.xtreamBase != null,
        favoritesAreServerSide = false,
        resumeIsServerSide = false,
        supportsRefresh = isM3uUrl || playlist?.xtreamBase != null,
        supportsSourceEditing = true,
        usesXtreamCredentials = isXtream,
        usesM3uUrl = isM3uUrl,
        isFileImport = isFile,
    )
}

internal interface LocalCatalogBackend {
    suspend fun groups(kind: Int, xtreamSeries: Boolean): List<GroupCount>
    suspend fun channels(kind: Int, group: String, filter: String, limit: Int, offset: Int): ListingPage<ChannelListing>
    suspend fun seriesGroups(group: String, filter: String, limit: Int, offset: Int): ListingPage<SeriesGroup>
    suspend fun xtreamSeries(category: String, filter: String, limit: Int, offset: Int): ListingPage<XtreamSeriesListing>
    suspend fun ensureEpisodes(seriesId: Long)
    suspend fun episodes(seriesKey: String, season: Int?, limit: Int, offset: Int): ListingPage<ChannelListing>
    suspend fun search(query: String): Pair<List<Channel>, List<XtreamSeries>>
    suspend fun progress(): Map<String, Float>
    suspend fun nowAiring(): Map<String, Programme>
    suspend fun guideIds(): Set<String>
    suspend fun favoriteItems(): List<CatalogItem>
    suspend fun resumePoints(): List<ResumePoint>
    suspend fun channel(ref: ContentRef.LocalUrl): Channel?
    suspend fun detail(ref: ContentRef.LocalUrl): CatalogDetail?
    suspend fun seriesDetail(seriesKey: String, seriesId: Long?): CatalogDetail? =
        detail(ContentRef.LocalUrl(seriesKey, 0))
    suspend fun guide(channel: Channel): List<GuideEntry>
    suspend fun hasSeries(seriesKey: String): Boolean
    suspend fun isFavorite(key: String): Boolean
    suspend fun toggleFavorite(key: String, kind: Int): Boolean
    suspend fun setFavorite(key: String, kind: Int, favorite: Boolean)
}

private class StorageLocalCatalogBackend(
    private val playlistId: Long,
    private val storage: Storage,
    private val xtream: XtreamRepository,
    private val favorites: FavoriteRepository,
    private val resume: ResumeRepository,
    private val epg: EpgRepository,
) : LocalCatalogBackend {
    override suspend fun groups(kind: Int, xtreamSeries: Boolean): List<GroupCount> =
        if (xtreamSeries) storage.xtreamSeries.observeCategories(playlistId).first()
        else storage.channels.observeGroups(playlistId, kind).first()

    override suspend fun channels(kind: Int, group: String, filter: String, limit: Int, offset: Int) =
        storage.channels.pageInGroup(playlistId, kind, group, filter, limit, offset)

    override suspend fun seriesGroups(group: String, filter: String, limit: Int, offset: Int) =
        storage.channels.pageSeriesInGroup(playlistId, group, filter, limit, offset)

    override suspend fun xtreamSeries(category: String, filter: String, limit: Int, offset: Int) =
        storage.xtreamSeries.pageInCategory(playlistId, category, filter, limit, offset)

    override suspend fun ensureEpisodes(seriesId: Long) =
        xtream.ensureEpisodes(playlistId, seriesId)

    override suspend fun episodes(seriesKey: String, season: Int?, limit: Int, offset: Int) =
        storage.channels.pageEpisodes(playlistId, seriesKey, season, limit, offset)

    override suspend fun search(query: String) =
        storage.channels.search(playlistId, query) to storage.xtreamSeries.search(playlistId, query)

    override suspend fun progress(): Map<String, Float> = resume.progressByUrl.first()

    override suspend fun nowAiring(): Map<String, Programme> = epg.nowAiring(playlistId)

    override suspend fun guideIds(): Set<String> = epg.observeGuideIds(playlistId).first()

    override suspend fun favoriteItems(): List<CatalogItem> {
        val favoriteRows = storage.favorites.getAll(playlistId)
        val liveUrls = favoriteRows.filter { it.kind == ChannelKind.LIVE }
            .map { it.key }.take(900)
        val movieUrls = favoriteRows.filter { it.kind == ChannelKind.MOVIE }
            .map { it.key }.take(900)
        val m3uSeriesKeys = favoriteRows
            .filter { it.kind == ChannelKind.SERIES && !it.key.startsWith("x:") }
            .mapTo(mutableSetOf()) { it.key }
        val xtreamSeriesIds = favoriteRows
            .filter { it.key.startsWith("x:") }
            .mapNotNullTo(mutableSetOf()) { it.key.removePrefix("x:").toLongOrNull() }
        val progress = progress()
        val guideIds = guideIds()
        val nowAiring = nowAiring()
        return assembleLocalFavorites(
            live = if (liveUrls.isEmpty()) emptyList()
                else storage.channels.observeByUrls(playlistId, ChannelKind.LIVE, liveUrls).first(),
            movies = if (movieUrls.isEmpty()) emptyList()
                else storage.channels.observeByUrls(playlistId, ChannelKind.MOVIE, movieUrls).first(),
            xtreamSeries = if (xtreamSeriesIds.isEmpty()) emptyList()
                else storage.xtreamSeries.observeAll(playlistId).first()
                    .filter { it.seriesId in xtreamSeriesIds },
            m3uSeries = if (m3uSeriesKeys.isEmpty()) emptyList()
                else storage.channels.observeAllSeries(playlistId).first()
                    .filter { it.seriesKey in m3uSeriesKeys },
            progress = progress,
            guideIds = guideIds,
            nowAiring = nowAiring,
        )
    }

    override suspend fun resumePoints(): List<ResumePoint> =
        localResumePoints(storage.resume.getAll()) { url ->
            storage.channels.getByUrl(playlistId, url) != null
        }

    override suspend fun channel(ref: ContentRef.LocalUrl): Channel? =
        ref.channelId.takeIf { it != 0L }?.let { storage.channels.get(it) }
            ?.takeIf { it.playlistId == playlistId && it.url == ref.url }
            ?: storage.channels.getByUrl(playlistId, ref.url)

    override suspend fun detail(ref: ContentRef.LocalUrl): CatalogDetail? {
        channel(ref)?.let { channel ->
            return CatalogDetail(channel.toCatalogItem(), description = channel.description)
        }
        val seriesId = ref.url.removePrefix("x:")
            .takeIf { ref.url.startsWith("x:") }
            ?.toLongOrNull()
        if (seriesId != null) {
            val series = storage.xtreamSeries.get(playlistId, seriesId) ?: return null
            return CatalogDetail(
                item = series.toCatalogItem(),
                description = series.plot,
                cast = series.castNames,
            )
        }
        val first = storage.channels.observeEpisodes(playlistId, ref.url).first().firstOrNull()
            ?: return null
        return CatalogDetail(first.toCatalogItem().copy(title = ref.url), first.description)
    }

    override suspend fun seriesDetail(seriesKey: String, seriesId: Long?): CatalogDetail? {
        val resolvedSeriesId = seriesId ?: seriesKey.removePrefix("xs:")
            .takeIf { seriesKey.startsWith("xs:") }
            ?.toLongOrNull()
        if (resolvedSeriesId != null) {
            val series = storage.xtreamSeries.get(playlistId, resolvedSeriesId) ?: return null
            return CatalogDetail(
                item = series.toCatalogItem(),
                description = series.plot,
                cast = series.castNames,
            )
        }
        val first = storage.channels.observeEpisodes(playlistId, seriesKey).first().firstOrNull()
            ?: return null
        return CatalogDetail(
            item = first.toCatalogItem().copy(
                ref = ContentRef.LocalUrl(seriesKey, 0),
                title = seriesKey,
            ),
            description = first.description,
        )
    }

    override suspend fun guide(channel: Channel): List<GuideEntry> = xtream.guideFor(channel)

    override suspend fun hasSeries(seriesKey: String): Boolean =
        storage.channels.countEpisodes(playlistId, seriesKey) > 0

    override suspend fun isFavorite(key: String): Boolean = favorites.contains(playlistId, key)

    override suspend fun toggleFavorite(key: String, kind: Int): Boolean =
        favorites.toggle(playlistId, key, kind)

    override suspend fun setFavorite(key: String, kind: Int, favorite: Boolean) {
        if (favorite) {
            favorites.restoreAll(playlistId, listOf(FavoriteRef(key, kind)))
        } else {
            favorites.remove(playlistId, key)
        }
    }
}

internal fun assembleLocalFavorites(
    live: List<Channel>,
    movies: List<Channel>,
    xtreamSeries: List<XtreamSeries>,
    m3uSeries: List<SeriesGroup>,
    progress: Map<String, Float>,
    guideIds: Set<String>,
    nowAiring: Map<String, Programme>,
): List<CatalogItem> = buildList {
    addAll(live.map { channel ->
        channel.toCatalogItem(progress[channel.url]).copy(
            hasGuide = channel.hasGuide(guideIds),
            nowAiring = channel.tvgId?.let(nowAiring::get)?.toCatalogProgramme(),
        )
    })
    addAll(movies.map { channel -> channel.toCatalogItem(progress[channel.url]) })
    addAll(xtreamSeries.map(XtreamSeries::toCatalogItem))
    addAll(m3uSeries.map(SeriesGroup::toCatalogItem))
}

internal suspend fun localResumePoints(
    points: List<ResumePoint>,
    belongsToSource: suspend (String) -> Boolean,
): List<ResumePoint> {
    val owned = mutableListOf<ResumePoint>()
    for (point in points) {
        if (belongsToSource(point.url)) owned += point
    }
    return owned
}

internal fun ChannelListing.toCatalogItem(progress: Float?) = CatalogItem(
    ref = ContentRef.LocalUrl(url, id),
    title = name,
    imageUrl = logo,
    kind = kind,
    group = groupTitle,
    seriesKey = seriesKey,
    season = season,
    episode = episode,
    durationSecs = durationSecs,
    tvgId = tvgId,
    airDate = airDate,
    catchupDays = catchupDays,
    hasCatchup = catchupDays > 0 || catchupSource != null,
    hasGuide = xtreamStreamId != null,
    progress = progress,
)

internal fun Channel.toCatalogItem(progress: Float? = null) = CatalogItem(
    ref = ContentRef.LocalUrl(url, id),
    title = name,
    imageUrl = logo,
    kind = kind,
    group = groupTitle,
    seriesKey = seriesKey,
    season = season,
    episode = episode,
    durationSecs = durationSecs,
    tvgId = tvgId,
    airDate = airDate,
    catchupDays = catchupDays,
    hasCatchup = catchupDays > 0 || catchupSource != null,
    hasGuide = xtreamStreamId != null,
    progress = progress,
)

internal fun SeriesGroup.toCatalogItem() = CatalogItem(
    ref = ContentRef.LocalUrl(seriesKey, 0),
    title = seriesKey,
    imageUrl = logo,
    kind = ChannelKind.SERIES,
    group = groupTitle,
    seriesKey = seriesKey,
    count = count,
)

internal fun XtreamSeriesListing.toCatalogItem() = CatalogItem(
    ref = ContentRef.LocalUrl(xtreamFavoriteKey(seriesId), 0),
    title = name,
    imageUrl = cover,
    kind = ChannelKind.SERIES,
    group = null,
    seriesKey = "xs:$seriesId",
    seriesId = seriesId.toString(),
    genre = genre,
    rating = rating,
)

internal fun XtreamSeries.toCatalogItem() = CatalogItem(
    ref = ContentRef.LocalUrl(xtreamFavoriteKey(seriesId), 0),
    title = name,
    imageUrl = cover,
    kind = ChannelKind.SERIES,
    group = categoryName,
    seriesKey = "xs:$seriesId",
    seriesId = seriesId.toString(),
    genre = genre,
    rating = rating,
)

private fun Programme.toCatalogProgramme() =
    CatalogProgramme(tvgId, title, description, startMs, endMs)

private fun GuideEntry.toCatalogGuideEntry() =
    CatalogGuideEntry(title, description, startMs, endMs, replayable)

private fun <T, R> ListingPage<T>.mapPage(transform: (T) -> R): Page<R> =
    Page(items.map(transform), total)
