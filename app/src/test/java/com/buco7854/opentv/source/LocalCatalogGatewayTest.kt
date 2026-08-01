package com.buco7854.opentv.source

import com.buco7854.opentv.core.model.Channel
import com.buco7854.opentv.core.model.ChannelKind
import com.buco7854.opentv.core.model.GroupCount
import com.buco7854.opentv.core.model.Playlist
import com.buco7854.opentv.core.model.Programme
import com.buco7854.opentv.core.model.ResumePoint
import com.buco7854.opentv.core.model.SeriesGroup
import com.buco7854.opentv.core.model.XtreamSeries
import com.buco7854.opentv.core.repo.GuideEntry
import com.buco7854.opentv.core.storage.ChannelListing
import com.buco7854.opentv.core.storage.ListingPage
import com.buco7854.opentv.core.storage.XtreamSeriesListing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCatalogGatewayTest {
    @Test
    fun gatewayConstructionDoesNotWaitForSlowPlaylistStorage() = runTest {
        val releaseStore = CompletableDeferred<Unit>()
        var storeReads = 0
        val gateway = LocalCatalogGateway(
            source = SourceId.LocalPlaylist(4),
            traitsProvider = {
                storeReads++
                releaseStore.await()
                localSourceTraits(playlist("https://provider.example/list.m3u", null))
            },
            backend = FakeLocalBackend(),
        )

        assertEquals(0, storeReads)
        val traits = async { gateway.traits() }
        runCurrent()
        assertEquals(1, storeReads)
        assertFalse(traits.isCompleted)

        var unrelatedCallerRan = false
        launch { unrelatedCallerRan = true }
        runCurrent()
        assertTrue(unrelatedCallerRan)

        releaseStore.complete(Unit)
        assertTrue(traits.await().usesM3uUrl)
    }

    @Test
    fun pagedReadsPreserveStoreRowsOrderingAndArguments() = runTest {
        val rows = listOf(listing(2, "second"), listing(1, "first"))
        val backend = FakeLocalBackend().apply {
            channelPage = ListingPage(rows, 9)
            seriesPage = ListingPage(
                listOf(
                    SeriesGroup("Zulu", 2, "z.png", "Drama"),
                    SeriesGroup("Alpha", 3, "a.png", "Drama"),
                ),
                7,
            )
            xtreamPage = ListingPage(
                listOf(
                    XtreamSeriesListing(4, 20, "Later", "l.png", "Drama", 8.0),
                    XtreamSeriesListing(4, 10, "Earlier", "e.png", "Comedy", 7.0),
                ),
                5,
            )
            episodePage = ListingPage(rows, 4)
            progressRows = mapOf("url-2" to .5f)
        }
        val gateway = gateway(backend)

        val channels = gateway.channels(ChannelKind.MOVIE, "Movies", 4, 2, "fi").value()
        assertEquals(listOf("second", "first"), channels.items.map { it.title })
        assertEquals(9, channels.total)
        assertEquals(.5f, channels.items.first().progress)
        assertEquals(ChannelCall(ChannelKind.MOVIE, "Movies", "fi", 2, 4), backend.channelCall)

        val series = gateway.seriesGroups("Drama", 3, 2, "show").value()
        assertEquals(listOf("Zulu", "Alpha"), series.items.map { it.title })
        assertEquals(SeriesCall("Drama", "show", 2, 3), backend.seriesCall)

        val panel = gateway.xtreamSeries("TV", 6, 2, "term").value()
        assertEquals(listOf("Later", "Earlier"), panel.items.map { it.title })
        assertEquals(listOf("TV", "TV"), panel.items.map { it.group })
        assertEquals(SeriesCall("TV", "term", 2, 6), backend.xtreamCall)

        val episodes = gateway.episodes("show", 2, 8, 2).value()
        assertEquals(listOf("second", "first"), episodes.items.map { it.title })
        assertEquals(EpisodeCall("show", 2, 2, 8), backend.episodeCall)
    }

    @Test
    fun nativeXtreamEpisodesAreFetchedBeforeThePagedRead() = runTest {
        val backend = FakeLocalBackend()
        gateway(backend).episodes("xs:91", null, 0, 50).value()
        assertEquals(listOf(91L), backend.ensuredSeries)
    }

    @Test
    fun localTraitsDescribeXtreamM3uAndFileSources() {
        val xtream = localSourceTraits(playlist(url = null, xtreamBase = "https://panel"))
        assertTrue(xtream.hasXtreamSeries)
        assertTrue(xtream.hasAccountPanel)
        assertTrue(xtream.supportsRefresh)
        assertTrue(xtream.usesXtreamCredentials)

        val m3u = localSourceTraits(playlist(url = "https://list/m3u", xtreamBase = null))
        assertFalse(m3u.hasXtreamSeries)
        assertTrue(m3u.supportsRefresh)
        assertTrue(m3u.usesM3uUrl)

        val file = localSourceTraits(playlist(url = null, xtreamBase = null))
        assertTrue(file.isFileImport)
        assertFalse(file.supportsRefresh)
        assertFalse(file.favoritesAreServerSide)
        assertFalse(file.resumeIsServerSide)
    }

    @Test
    fun missingPlaylistTraitsAndAnEmptySearchStayUsable() = runTest {
        val gateway = LocalCatalogGateway(
            source = SourceId.LocalPlaylist(404),
            traits = localSourceTraits(null),
            backend = FakeLocalBackend(),
        )

        assertNull(gateway.traits().title)
        assertTrue(gateway.search("news").value().isEmpty)
    }

    @Test
    fun staleNumericIdCannotResolveAChannelFromAnotherSource() = runTest {
        val wrong = channel(
            id = 77,
            playlistId = 99,
            url = "https://other.example/movie",
        )
        val backend = FakeLocalBackend().apply {
            channelResult = { ref -> wrong.takeIf { ref.channelId != 0L } }
            detailResult = { ref ->
                channelResult(ref)?.let { CatalogDetail(it.toCatalogItem(), it.description) }
            }
        }
        val gateway = gateway(backend)
        val ref = ContentRef.LocalUrl("https://local.example/movie", 77)

        assertNull(gateway.detail(ref).value())
        assertTrue(gateway.guideFor(ref) is CatalogResult.Failed)
        assertTrue(gateway.toggleFavorite(ref) is CatalogResult.Failed)
        assertTrue(backend.toggleCalls.isEmpty())
    }

    @Test
    fun zeroIdSeriesCannotBeConfusedWithAChannelWhoseUrlMatchesTheSeriesKey() = runTest {
        val collision = "https://looks-like-a-provider-url.example/show"
        val wrongChannel = channel(81, 4, collision)
        val backend = FakeLocalBackend().apply {
            channelResult = { wrongChannel }
            hasSeriesResult = { it == collision }
            seriesDetailResult = { key, _ ->
                CatalogDetail(
                    CatalogItem(
                        ref = ContentRef.LocalUrl(key, 0),
                        title = "The intended series",
                        imageUrl = null,
                        kind = ChannelKind.SERIES,
                        group = "Shows",
                        seriesKey = key,
                    ),
                )
            }
        }
        val gateway = gateway(backend)
        val ref = ContentRef.LocalUrl(collision, 0)

        val detail = gateway.seriesDetail(ref, collision, null).value()
        assertEquals("The intended series", detail?.item?.title)

        assertTrue(gateway.toggleFavorite(ref).value())
        assertEquals(listOf(collision to ChannelKind.SERIES), backend.toggleCalls)
    }

    @Test
    fun favoritesKeepLegacyKindOrderingAndLiveAffordances() {
        val programme = Programme(
            id = 1,
            playlistId = 4,
            tvgId = "guide",
            title = "Now",
            description = null,
            startMs = 10,
            endMs = 20,
        )
        val live = channel(1, 4, "live").copy(
            name = "Live",
            kind = ChannelKind.LIVE,
            tvgId = "guide",
        )
        val movie = channel(2, 4, "movie").copy(name = "Movie")
        val panel = XtreamSeries(
            playlistId = 4,
            seriesId = 7,
            name = "Panel",
            categoryName = "Shows",
            cover = null,
            plot = null,
            castNames = null,
            genre = null,
            rating = null,
            episodesFetchedAtMs = 0,
        )

        val items = assembleLocalFavorites(
            live = listOf(live),
            movies = listOf(movie),
            xtreamSeries = listOf(panel),
            m3uSeries = listOf(SeriesGroup("M3U", 2, null, "Shows")),
            progress = mapOf("live" to .25f, "movie" to .5f),
            guideIds = setOf("guide"),
            nowAiring = mapOf("guide" to programme),
        )

        assertEquals(listOf("Live", "Movie", "Panel", "M3U"), items.map { it.title })
        assertEquals(listOf(0, 1, 2, 2), items.map { it.kind })
        assertEquals(.25f, items[0].progress)
        assertTrue(items[0].hasGuide)
        assertEquals("Now", items[0].nowAiring?.title)
        assertEquals(.5f, items[1].progress)
    }

    @Test
    fun localResumeResultsCannotLeakRowsOwnedByAnotherSource() = runTest {
        val own = ResumePoint("own", 10, 20, 1)
        val other = ResumePoint("other", 30, 40, 2)

        val result = localResumePoints(listOf(own, other)) { it == "own" }

        assertEquals(listOf(own), result)
    }

    private fun gateway(backend: FakeLocalBackend) = LocalCatalogGateway(
        source = SourceId.LocalPlaylist(4),
        traits = localSourceTraits(playlist(null, null)),
        backend = backend,
    )
}

private fun playlist(url: String?, xtreamBase: String?) = Playlist(
    id = 4,
    name = "Local",
    url = url,
    xtreamBase = xtreamBase,
)

private fun listing(id: Long, title: String) = ChannelListing(
    id = id,
    playlistId = 4,
    name = title,
    url = "url-$id",
    logo = "$id.png",
    groupTitle = "Movies",
    tvgId = null,
    kind = ChannelKind.MOVIE,
    seriesKey = null,
    season = null,
    episode = null,
    xtreamStreamId = id,
    catchupDays = 0,
    catchupSource = null,
    durationSecs = 120,
    airDate = null,
)

private fun channel(id: Long, playlistId: Long, url: String) = Channel(
    id = id,
    playlistId = playlistId,
    name = "Wrong",
    url = url,
    logo = null,
    groupTitle = "Movies",
    tvgId = null,
    kind = ChannelKind.MOVIE,
    seriesKey = null,
    season = null,
    episode = null,
    position = 0,
    xtreamStreamId = null,
    catchupDays = 0,
    catchupSource = null,
    description = "Wrong source",
    durationSecs = 120,
    airDate = null,
)

private fun <T> CatalogResult<T>.value(): T =
    (this as CatalogResult.Success<T>).value

private data class ChannelCall(
    val kind: Int,
    val group: String,
    val filter: String,
    val limit: Int,
    val offset: Int,
)

private data class SeriesCall(
    val group: String,
    val filter: String,
    val limit: Int,
    val offset: Int,
)

private data class EpisodeCall(
    val key: String,
    val season: Int?,
    val limit: Int,
    val offset: Int,
)

private class FakeLocalBackend : LocalCatalogBackend {
    var channelPage = ListingPage<ChannelListing>(emptyList(), 0)
    var seriesPage = ListingPage<SeriesGroup>(emptyList(), 0)
    var xtreamPage = ListingPage<XtreamSeriesListing>(emptyList(), 0)
    var episodePage = ListingPage<ChannelListing>(emptyList(), 0)
    var progressRows: Map<String, Float> = emptyMap()
    var channelCall: ChannelCall? = null
    var seriesCall: SeriesCall? = null
    var xtreamCall: SeriesCall? = null
    var episodeCall: EpisodeCall? = null
    val ensuredSeries = mutableListOf<Long>()
    var channelResult: suspend (ContentRef.LocalUrl) -> Channel? = { null }
    var detailResult: suspend (ContentRef.LocalUrl) -> CatalogDetail? = { null }
    var seriesDetailResult: suspend (String, Long?) -> CatalogDetail? = { _, _ -> null }
    var hasSeriesResult: suspend (String) -> Boolean = { false }
    val toggleCalls = mutableListOf<Pair<String, Int>>()

    override suspend fun groups(kind: Int, xtreamSeries: Boolean) =
        listOf(GroupCount("One", 1))

    override suspend fun channels(
        kind: Int,
        group: String,
        filter: String,
        limit: Int,
        offset: Int,
    ) = channelPage.also { channelCall = ChannelCall(kind, group, filter, limit, offset) }

    override suspend fun seriesGroups(group: String, filter: String, limit: Int, offset: Int) =
        seriesPage.also { seriesCall = SeriesCall(group, filter, limit, offset) }

    override suspend fun xtreamSeries(category: String, filter: String, limit: Int, offset: Int) =
        xtreamPage.also { xtreamCall = SeriesCall(category, filter, limit, offset) }

    override suspend fun ensureEpisodes(seriesId: Long) {
        ensuredSeries += seriesId
    }

    override suspend fun episodes(seriesKey: String, season: Int?, limit: Int, offset: Int) =
        episodePage.also { episodeCall = EpisodeCall(seriesKey, season, limit, offset) }

    override suspend fun search(query: String): Pair<List<Channel>, List<XtreamSeries>> =
        emptyList<Channel>() to emptyList()

    override suspend fun progress() = progressRows
    override suspend fun nowAiring(): Map<String, Programme> = emptyMap()
    override suspend fun guideIds(): Set<String> = emptySet()
    override suspend fun favoriteItems(): List<CatalogItem> = emptyList()
    override suspend fun resumePoints(): List<ResumePoint> = emptyList()
    override suspend fun channel(ref: ContentRef.LocalUrl): Channel? = channelResult(ref)
    override suspend fun detail(ref: ContentRef.LocalUrl): CatalogDetail? = detailResult(ref)
    override suspend fun seriesDetail(seriesKey: String, seriesId: Long?): CatalogDetail? =
        seriesDetailResult(seriesKey, seriesId)
    override suspend fun guide(channel: Channel): List<GuideEntry> = emptyList()
    override suspend fun hasSeries(seriesKey: String): Boolean = hasSeriesResult(seriesKey)
    override suspend fun isFavorite(key: String): Boolean = false
    override suspend fun toggleFavorite(key: String, kind: Int): Boolean {
        toggleCalls += key to kind
        return true
    }

    override suspend fun setFavorite(key: String, kind: Int, favorite: Boolean) {
        toggleCalls += key to kind
    }
}
