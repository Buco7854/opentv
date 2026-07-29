package com.buco7854.opentv.ui

import com.buco7854.opentv.source.CatalogDetail
import com.buco7854.opentv.source.CatalogGateway
import com.buco7854.opentv.source.CatalogGroup
import com.buco7854.opentv.source.CatalogGuideEntry
import com.buco7854.opentv.source.CatalogItem
import com.buco7854.opentv.source.CatalogProgramme
import com.buco7854.opentv.source.CatalogResult
import com.buco7854.opentv.source.CatalogResumePoint
import com.buco7854.opentv.source.CatalogSearchResult
import com.buco7854.opentv.source.ContentRef
import com.buco7854.opentv.source.Page
import com.buco7854.opentv.source.SourceId
import com.buco7854.opentv.source.SourceTraits

internal class CatalogGatewayFake(
    override val source: SourceId,
    val sourceTraits: SourceTraits = SourceTraits(
        hasXtreamSeries = false,
        hasGuide = true,
        hasAccountPanel = false,
        favoritesAreServerSide = source is SourceId.Hub,
        resumeIsServerSide = source is SourceId.Hub,
        supportsRefresh = true,
        supportsSourceEditing = source is SourceId.LocalPlaylist,
        usesXtreamCredentials = false,
        usesM3uUrl = source is SourceId.LocalPlaylist,
        isFileImport = false,
    ),
) : CatalogGateway {
    override suspend fun traits(): SourceTraits = sourceTraits

    var groupsResult: CatalogResult<List<CatalogGroup>> =
        CatalogResult.Success(emptyList())
    var groupsBlock: suspend (Int) -> CatalogResult<List<CatalogGroup>> = { groupsResult }
    var searchResult: CatalogResult<CatalogSearchResult> =
        CatalogResult.Success(CatalogSearchResult())
    var searchBlock: suspend (String) -> CatalogResult<CatalogSearchResult> = { searchResult }
    var channelPage: suspend (Int, Int) -> CatalogResult<Page<CatalogItem>> =
        { _, _ -> CatalogResult.Success(Page(emptyList(), 0)) }
    var guideBlock: suspend (ContentRef) -> CatalogResult<List<CatalogGuideEntry>> = {
        CatalogResult.Success(emptyList())
    }
    val channelRequests = mutableListOf<Pair<Int, Int>>()

    override suspend fun groups(kind: Int) = groupsBlock(kind)

    override suspend fun channels(
        kind: Int,
        group: String,
        offset: Int,
        limit: Int,
        filter: String,
    ): CatalogResult<Page<CatalogItem>> {
        channelRequests += offset to limit
        return channelPage(offset, limit)
    }

    override suspend fun seriesGroups(
        group: String,
        offset: Int,
        limit: Int,
        filter: String,
    ) = channels(2, group, offset, limit, filter)

    override suspend fun xtreamSeries(
        category: String,
        offset: Int,
        limit: Int,
        filter: String,
    ) = channels(2, category, offset, limit, filter)

    override suspend fun episodes(
        seriesKey: String,
        season: Int?,
        offset: Int,
        limit: Int,
    ) = CatalogResult.Success(Page<CatalogItem>(emptyList(), 0))

    override suspend fun search(query: String) = searchBlock(query)

    override suspend fun nowAiring() =
        CatalogResult.Success<Map<String, CatalogProgramme>>(emptyMap())

    override suspend fun guideIds() = CatalogResult.Success<Set<String>>(emptySet())

    override suspend fun favorites(offset: Int, limit: Int) =
        CatalogResult.Success(Page<CatalogItem>(emptyList(), 0))

    override suspend fun resumePoints() =
        CatalogResult.Success<List<CatalogResumePoint>>(emptyList())

    override suspend fun guideFor(ref: ContentRef) = guideBlock(ref)

    override suspend fun detail(ref: ContentRef) =
        CatalogResult.Success<CatalogDetail?>(null)

    override suspend fun isFavorite(ref: ContentRef) = CatalogResult.Success(false)

    override suspend fun toggleFavorite(ref: ContentRef) = CatalogResult.Success(true)
}
