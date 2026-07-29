package com.buco7854.opentv.source

import com.buco7854.opentv.core.model.HubSource
import com.buco7854.opentv.core.model.Playlist
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AggregatedFavoritesTest {
    @Test
    fun aggregatesLocalAndHubFavoritesIntoOrderedSections() = runTest {
        val localSource = SourceId.LocalPlaylist(1)
        val hubSource = SourceId.Hub(9, 20)
        val gateways = mapOf(
            localSource to FakeCatalogGateway(localSource, listOf(item("local", "Local"))),
            hubSource to FakeCatalogGateway(hubSource, listOf(item("hub", "Hub"))),
        )
        val favorites = aggregator(
            locals = MutableStateFlow(listOf(playlist(1, "Local playlist"))),
            hubs = MutableStateFlow(listOf(hub(9, "Home server"))),
            hubPlaylists = { CatalogResult.Success(listOf(HubPlaylist(20, "Hub playlist"))) },
            gateways = gateways,
        )

        runCurrent()

        assertEquals(listOf(localSource, hubSource), favorites.state.value.sections.map { it.source })
        assertEquals(
            listOf("Local playlist", "Hub playlist"),
            favorites.state.value.sections.map { it.title },
        )
        assertEquals(2, favorites.state.value.totalCount)
        assertTrue(favorites.state.value.hasMultipleSources)
    }

    @Test
    fun unreachableHubDoesNotBlockTheLocalSection() = runTest {
        val discovery = CompletableDeferred<CatalogResult<List<HubPlaylist>>>()
        val localSource = SourceId.LocalPlaylist(1)
        val favorites = aggregator(
            locals = MutableStateFlow(listOf(playlist(1, "Local"))),
            hubs = MutableStateFlow(listOf(hub(9, "Offline hub"))),
            hubPlaylists = { discovery.await() },
            gateways = mapOf(
                localSource to FakeCatalogGateway(localSource, listOf(item("local", "Ready")))
            ),
        )

        runCurrent()
        assertEquals(listOf("Ready"), favorites.state.value.sections.first().items.map { it.title })
        assertTrue(favorites.state.value.loading)

        discovery.complete(CatalogResult.Unreachable)
        runCurrent()

        val sections = favorites.state.value.sections
        assertEquals(2, sections.size)
        assertEquals(listOf("Ready"), sections[0].items.map { it.title })
        assertEquals(SourceId.HubConnection(9), sections[1].source)
        assertSame(CatalogLoadError.Unreachable, sections[1].error)
        assertFalse(favorites.state.value.loading)
    }

    @Test
    fun signedOutHubIsDistinctFromAnUnreachableHub() = runTest {
        val signedOut = aggregator(
            locals = MutableStateFlow(emptyList()),
            hubs = MutableStateFlow(listOf(hub(1, "Signed out"))),
            hubPlaylists = { CatalogResult.SignedOut },
        )
        val unreachable = aggregator(
            locals = MutableStateFlow(emptyList()),
            hubs = MutableStateFlow(listOf(hub(2, "Offline"))),
            hubPlaylists = { CatalogResult.Unreachable },
        )

        runCurrent()

        assertSame(CatalogLoadError.SignedOut, signedOut.state.value.sections.single().error)
        assertSame(CatalogLoadError.Unreachable, unreachable.state.value.sections.single().error)
    }

    @Test
    fun emptySourcesDisappearButFailedSourcesRemain() = runTest {
        val empty = SourceId.LocalPlaylist(1)
        val failed = SourceId.LocalPlaylist(2)
        val failure = IllegalStateException("broken")
        val favorites = aggregator(
            locals = MutableStateFlow(listOf(playlist(1, "Empty"), playlist(2, "Broken"))),
            hubs = MutableStateFlow(emptyList()),
            gateways = mapOf(
                empty to FakeCatalogGateway(empty, emptyList()),
                failed to FakeCatalogGateway(failed, CatalogResult.Failed(failure)),
            ),
        )

        runCurrent()

        val section = favorites.state.value.sections.single()
        assertEquals(failed, section.source)
        assertSame(failure, (section.error as CatalogLoadError.Failed).cause)
    }

    @Test
    fun oneContributingSourceHidesSectionHeaders() = runTest {
        val source = SourceId.LocalPlaylist(1)
        val favorites = aggregator(
            locals = MutableStateFlow(listOf(playlist(1, "Only"), playlist(2, "Empty"))),
            hubs = MutableStateFlow(emptyList()),
            gateways = mapOf(
                source to FakeCatalogGateway(source, listOf(item("one", "One"))),
                SourceId.LocalPlaylist(2) to
                    FakeCatalogGateway(SourceId.LocalPlaylist(2), emptyList()),
            ),
        )

        runCurrent()

        assertEquals(1, favorites.state.value.sections.size)
        assertFalse(favorites.state.value.hasMultipleSources)
    }

    @Test
    fun refreshingHubDiscoveryKeepsContributingSectionsStableWhileTheyReload() = runTest {
        val localSource = SourceId.LocalPlaylist(1)
        val hubSource = SourceId.Hub(9, 20)
        val rediscovery = CompletableDeferred<CatalogResult<List<HubPlaylist>>>()
        val favoriteReload = CompletableDeferred<CatalogResult<Page<CatalogItem>>>()
        var discoveries = 0
        var hubFavoriteLoads = 0
        val hubGateway = FakeCatalogGateway(hubSource, listOf(item("hub", "Hub"))).apply {
            favoriteBlock = { _, _ ->
                hubFavoriteLoads++
                if (hubFavoriteLoads == 1) {
                    CatalogResult.Success(Page(listOf(item("hub", "Hub")), 1))
                } else {
                    favoriteReload.await()
                }
            }
        }
        val favorites = aggregator(
            locals = MutableStateFlow(listOf(playlist(1, "Local"))),
            hubs = MutableStateFlow(listOf(hub(9, "Hub"))),
            hubPlaylists = {
                discoveries++
                if (discoveries == 1) {
                    CatalogResult.Success(listOf(HubPlaylist(20, "Remote")))
                } else {
                    rediscovery.await()
                }
            },
            gateways = mapOf(
                localSource to FakeCatalogGateway(localSource, listOf(item("local", "Local"))),
                hubSource to hubGateway,
            ),
        )
        runCurrent()
        assertTrue(favorites.state.value.hasMultipleSources)

        favorites.refresh()
        runCurrent()

        assertTrue(favorites.state.value.hasMultipleSources)
        assertEquals(
            listOf("Local", "Hub"),
            favorites.state.value.sections.flatMap { it.items }.map { it.title },
        )

        rediscovery.complete(CatalogResult.Success(listOf(HubPlaylist(20, "Remote"))))
        runCurrent()
        assertTrue(favorites.state.value.hasMultipleSources)
        assertEquals(
            listOf("Local", "Hub"),
            favorites.state.value.sections.flatMap { it.items }.map { it.title },
        )
        favoriteReload.complete(CatalogResult.Success(Page(listOf(item("hub", "Hub")), 1)))
        runCurrent()
    }

    @Test
    fun identicalItemsFromTwoSourcesAreNotMerged() = runTest {
        val first = SourceId.LocalPlaylist(1)
        val second = SourceId.LocalPlaylist(2)
        val duplicate = CatalogItem(
            ref = ContentRef.LocalUrl("same-url", 7),
            title = "Same title",
            imageUrl = null,
            kind = 0,
            group = "News",
        )
        val favorites = aggregator(
            locals = MutableStateFlow(listOf(playlist(1, "First"), playlist(2, "Second"))),
            hubs = MutableStateFlow(emptyList()),
            gateways = mapOf(
                first to FakeCatalogGateway(first, listOf(duplicate)),
                second to FakeCatalogGateway(second, listOf(duplicate)),
            ),
        )

        runCurrent()

        assertEquals(2, favorites.state.value.sections.size)
        assertEquals(2, favorites.state.value.totalCount)
        assertEquals(listOf(duplicate, duplicate), favorites.state.value.sections.flatMap { it.items })
    }

    @Test
    fun retryReloadsOnlyTheRequestedSection() = runTest {
        val first = SourceId.LocalPlaylist(1)
        val second = SourceId.LocalPlaylist(2)
        val firstGateway = FakeCatalogGateway(first, CatalogResult.Unreachable)
        val secondGateway = FakeCatalogGateway(second, listOf(item("steady", "Steady")))
        val favorites = aggregator(
            locals = MutableStateFlow(listOf(playlist(1, "First"), playlist(2, "Second"))),
            hubs = MutableStateFlow(emptyList()),
            gateways = mapOf(first to firstGateway, second to secondGateway),
        )
        runCurrent()
        firstGateway.favoriteResult =
            CatalogResult.Success(Page(listOf(item("recovered", "Recovered")), 1))

        favorites.retry(first)
        runCurrent()

        assertEquals(2, firstGateway.favoriteCalls)
        assertEquals(1, secondGateway.favoriteCalls)
        assertEquals(
            listOf("Recovered", "Steady"),
            favorites.state.value.sections.flatMap { it.items }.map { it.title },
        )
    }

    @Test
    fun addingAndRemovingAConfiguredSourceUpdatesState() = runTest {
        val locals = MutableStateFlow(listOf(playlist(1, "First")))
        val first = SourceId.LocalPlaylist(1)
        val second = SourceId.LocalPlaylist(2)
        val favorites = aggregator(
            locals = locals,
            hubs = MutableStateFlow(emptyList()),
            gateways = mapOf(
                first to FakeCatalogGateway(first, listOf(item("one", "One"))),
                second to FakeCatalogGateway(second, listOf(item("two", "Two"))),
            ),
        )
        runCurrent()
        assertEquals(listOf(first), favorites.state.value.sections.map { it.source })

        locals.value = listOf(playlist(1, "First"), playlist(2, "Second"))
        runCurrent()
        assertEquals(listOf(first, second), favorites.state.value.sections.map { it.source })

        locals.value = listOf(playlist(2, "Second"))
        runCurrent()
        assertEquals(listOf(second), favorites.state.value.sections.map { it.source })
    }

    @Test
    fun toggleRoutesToOneGatewayAndUpdatesOnlyItsSection() = runTest {
        val first = SourceId.LocalPlaylist(1)
        val second = SourceId.LocalPlaylist(2)
        val removed = item("remove", "Remove")
        val firstGateway = FakeCatalogGateway(first, listOf(removed)).apply {
            afterToggle = emptyList()
        }
        val secondGateway = FakeCatalogGateway(second, listOf(item("keep", "Keep")))
        val favorites = aggregator(
            locals = MutableStateFlow(listOf(playlist(1, "First"), playlist(2, "Second"))),
            hubs = MutableStateFlow(emptyList()),
            gateways = mapOf(first to firstGateway, second to secondGateway),
        )
        runCurrent()

        favorites.toggleFavorite(first, removed.ref)

        assertEquals(listOf(removed.ref), firstGateway.toggleCalls)
        assertTrue(secondGateway.toggleCalls.isEmpty())
        assertEquals(2, firstGateway.favoriteCalls)
        assertEquals(1, secondGateway.favoriteCalls)
        assertEquals(listOf(second), favorites.state.value.sections.map { it.source })
        assertEquals(listOf("Keep"), favorites.state.value.sections.single().items.map { it.title })
    }

    @Test
    fun exactFavoriteWritesDoNotInvertStateDuringRemoveAndUndo() = runTest {
        val source = SourceId.LocalPlaylist(1)
        val favorite = item("one", "One")
        val gateway = FakeCatalogGateway(source, listOf(favorite))
        val favorites = aggregator(
            locals = MutableStateFlow(listOf(playlist(1, "Local"))),
            hubs = MutableStateFlow(emptyList()),
            gateways = mapOf(source to gateway),
        )
        runCurrent()

        favorites.setFavorite(source, favorite.ref, favorite = false)
        favorites.setFavorite(source, favorite.ref, favorite = false)
        favorites.setFavorite(source, favorite.ref, favorite = true)

        assertEquals(listOf(false, false, true), gateway.setCalls)
        assertEquals(listOf(favorite), favorites.state.value.sections.single().items)
    }

    @Test
    fun olderMutationFailureCannotReplaceANewerSuccessfulRefresh() = runTest {
        val source = SourceId.LocalPlaylist(1)
        val favorite = item("one", "One")
        val mutation = CompletableDeferred<CatalogResult<Boolean>>()
        val gateway = FakeCatalogGateway(source, listOf(favorite)).apply {
            setBlock = { _, _ -> withContext(NonCancellable) { mutation.await() } }
        }
        val favorites = aggregator(
            locals = MutableStateFlow(listOf(playlist(1, "Local"))),
            hubs = MutableStateFlow(emptyList()),
            gateways = mapOf(source to gateway),
        )
        runCurrent()

        val write = launch { favorites.setFavorite(source, favorite.ref, false) }
        runCurrent()
        favorites.refresh()
        runCurrent()
        mutation.complete(CatalogResult.Failed(IllegalStateException("stale")))
        write.join()
        runCurrent()

        val section = favorites.state.value.sections.single()
        assertEquals(listOf(favorite), section.items)
        assertEquals(null, section.error)
        assertEquals(false, section.loading)
    }

    @Test
    fun changingPageBoundaryDoesNotDuplicateAFavoriteOrStallTheOffset() = runTest {
        val source = SourceId.LocalPlaylist(1)
        val rows = List(52) { item("$it", "Item $it") }
        val gateway = FakeCatalogGateway(source, emptyList()).apply {
            favoriteBlock = { offset, _ ->
                CatalogResult.Success(
                    when (offset) {
                        0 -> Page(rows.take(50), 52)
                        50 -> Page(listOf(rows[49], rows[50]), 53)
                        else -> Page(listOf(rows[51]), 53)
                    },
                )
            }
        }
        val favorites = aggregator(
            locals = MutableStateFlow(listOf(playlist(1, "Local"))),
            hubs = MutableStateFlow(emptyList()),
            gateways = mapOf(source to gateway),
        )
        runCurrent()

        assertEquals(listOf(0, 50, 52), gateway.favoriteOffsets)
        assertEquals(rows, favorites.state.value.sections.single().items)
    }

    private fun TestScope.aggregator(
        locals: MutableStateFlow<List<Playlist>>,
        hubs: MutableStateFlow<List<HubSource>>,
        hubPlaylists: suspend (HubSource) -> CatalogResult<List<HubPlaylist>> = {
            CatalogResult.Success(emptyList())
        },
        gateways: Map<SourceId, FakeCatalogGateway> = emptyMap(),
    ) = AggregatedFavorites(
        scope = backgroundScope,
        localPlaylists = locals,
        hubSources = hubs,
        hubPlaylists = hubPlaylists,
        gatewayFor = { checkNotNull(gateways[it]) { "No gateway for $it" } },
    )
}

private fun playlist(id: Long, name: String) = Playlist(id = id, name = name, url = null)

private fun hub(id: Long, name: String) =
    HubSource(id = id, name = name, baseUrl = "https://hub-$id.example", addedMs = id)

private fun item(id: String, title: String) = CatalogItem(
    ref = ContentRef.HubContent(id),
    title = title,
    imageUrl = null,
    kind = 0,
    group = "Group",
)

private class FakeCatalogGateway(
    override val source: SourceId,
    initialResult: CatalogResult<Page<CatalogItem>>,
) : CatalogGateway {
    constructor(source: SourceId, items: List<CatalogItem>) :
        this(source, CatalogResult.Success(Page(items, items.size)))

    private val sourceTraits = SourceTraits(
        hasXtreamSeries = false,
        hasGuide = false,
        hasAccountPanel = false,
        favoritesAreServerSide = source is SourceId.Hub,
        resumeIsServerSide = source is SourceId.Hub,
        supportsRefresh = false,
        supportsSourceEditing = false,
        usesXtreamCredentials = false,
        usesM3uUrl = false,
        isFileImport = false,
    )
    override suspend fun traits(): SourceTraits = sourceTraits
    var favoriteResult = initialResult
    var favoriteBlock: (suspend (Int, Int) -> CatalogResult<Page<CatalogItem>>)? = null
    var afterToggle: List<CatalogItem>? = null
    var favoriteCalls = 0
    val favoriteOffsets = mutableListOf<Int>()
    val toggleCalls = mutableListOf<ContentRef>()
    val setCalls = mutableListOf<Boolean>()
    var setBlock: (suspend (ContentRef, Boolean) -> CatalogResult<Boolean>)? = null

    override suspend fun favorites(offset: Int, limit: Int): CatalogResult<Page<CatalogItem>> {
        favoriteCalls++
        favoriteOffsets += offset
        favoriteBlock?.let { return it(offset, limit) }
        return favoriteResult
    }

    override suspend fun toggleFavorite(ref: ContentRef): CatalogResult<Boolean> {
        toggleCalls += ref
        afterToggle?.let { favoriteResult = CatalogResult.Success(Page(it, it.size)) }
        return CatalogResult.Success(false)
    }

    override suspend fun setFavorite(ref: ContentRef, favorite: Boolean): CatalogResult<Boolean> {
        setCalls += favorite
        setBlock?.let { return it(ref, favorite) }
        favoriteResult = CatalogResult.Success(Page(if (favorite) listOf(item("one", "One")) else emptyList(), if (favorite) 1 else 0))
        return CatalogResult.Success(favorite)
    }

    override suspend fun groups(kind: Int) = CatalogResult.Success(emptyList<CatalogGroup>())
    override suspend fun channels(kind: Int, group: String, offset: Int, limit: Int, filter: String) =
        CatalogResult.Success(Page(emptyList<CatalogItem>(), 0))

    override suspend fun seriesGroups(group: String, offset: Int, limit: Int, filter: String) =
        CatalogResult.Success(Page(emptyList<CatalogItem>(), 0))

    override suspend fun xtreamSeries(category: String, offset: Int, limit: Int, filter: String) =
        CatalogResult.Success(Page(emptyList<CatalogItem>(), 0))

    override suspend fun episodes(seriesKey: String, season: Int?, offset: Int, limit: Int) =
        CatalogResult.Success(Page(emptyList<CatalogItem>(), 0))

    override suspend fun search(query: String) = CatalogResult.Success(CatalogSearchResult())
    override suspend fun nowAiring() =
        CatalogResult.Success(emptyMap<String, CatalogProgramme>())

    override suspend fun guideIds() = CatalogResult.Success(emptySet<String>())
    override suspend fun resumePoints() =
        CatalogResult.Success(emptyList<CatalogResumePoint>())

    override suspend fun guideFor(ref: ContentRef) =
        CatalogResult.Success(emptyList<CatalogGuideEntry>())

    override suspend fun detail(ref: ContentRef) =
        CatalogResult.Success<CatalogDetail?>(null)

    override suspend fun isFavorite(ref: ContentRef) = CatalogResult.Success(false)
}
