package com.buco7854.opentv.source

import android.content.SharedPreferences
import com.buco7854.opentv.contract.ChannelDto
import com.buco7854.opentv.contract.ChannelListItemDto
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
import com.buco7854.opentv.contract.XtreamSeriesPageDto
import com.buco7854.opentv.contract.XtreamSeriesDetailDto
import com.buco7854.opentv.contract.XtreamSeriesDto
import com.buco7854.opentv.core.model.ChannelKind
import com.buco7854.opentv.core.model.HubSource
import com.buco7854.opentv.core.net.HttpRequestSpec
import com.buco7854.opentv.core.net.HttpResponseSpec
import com.buco7854.opentv.core.net.HttpTransport
import com.buco7854.opentv.core.repo.FavoriteRepository
import com.buco7854.opentv.core.storage.FavoriteStore
import com.buco7854.opentv.core.storage.HubSourceStore
import com.buco7854.opentv.core.storage.Storage
import com.buco7854.opentv.hub.HubApi
import com.buco7854.opentv.hub.HubRegistry
import com.buco7854.opentv.hub.HubSessionVault
import com.buco7854.opentv.hub.HubUnauthorizedException
import com.buco7854.opentv.hub.HubUnreachableException
import com.buco7854.opentv.hub.TokenCipher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HubCatalogGatewayTest {
    @Test
    fun pagingMapsContentIdentityImageCapabilityAndResumeProgress() = runTest {
        val backend = FakeHubBackend().apply {
            channelPage = ChannelPageDto(
                items = listOf(
                    ChannelListItemDto(
                        contentId = "stable-1",
                        id = 900,
                        name = "Movie",
                        logo = "opaque/token?x=1",
                        tvgId = null,
                        kind = 1,
                        xtreamStreamId = "22",
                        catchupDays = 0,
                        hasCatchup = false,
                    )
                ),
                total = 81,
                offset = 50,
                limit = 25,
            )
            resumeRows = listOf(ResumePointDto("stable-1", 30_000, 60_000, 9))
        }
        val gateway = HubCatalogGateway(SourceId.Hub(3, 7), backend)

        val page = gateway.channels(1, "Movies", 50, 25, "new").successValue()

        assertEquals(81, page.total)
        assertEquals(ContentRef.HubContent("stable-1"), page.items.single().ref)
        assertEquals(.5f, page.items.single().progress)
        assertTrue(page.items.single().hasGuide)
        assertEquals(
            "https://hub.example/api/v1/img?u=opaque%2Ftoken%3Fx%3D1",
            page.items.single().imageUrl,
        )
        assertEquals(ChannelRequest(1, "Movies", 50, 25, "new"), backend.channelRequest)
        assertEquals(1, backend.resumeCalls)
    }

    @Test
    fun favoritesAndResumeUseOnlyHubOwnedState() = runTest {
        val backend = FakeHubBackend().apply {
            resolved = FavoritesResolvedDto(live = listOf(channel("favorite-1", "Favorite")))
            favoriteRows = listOf(FavoriteDto("favorite-1", 7, "provider-secret", 1, 1))
            resumeRows = listOf(ResumePointDto("favorite-1", 20, 100, 4))
            guideRows = listOf("guide")
            nowRows = listOf(
                ProgrammeDto(1, 7, "guide", "Now", null, 10, 20),
            )
        }
        val gateway = HubCatalogGateway(SourceId.Hub(3, 7), backend)

        val favorites = gateway.favorites().successValue()
        assertEquals(listOf("Favorite"), favorites.items.map { it.title })
        assertEquals(ContentRef.HubContent("favorite-1"), favorites.items.single().ref)
        assertEquals(.2f, favorites.items.single().progress)
        assertTrue(favorites.items.single().hasGuide)
        assertEquals("Now", favorites.items.single().nowAiring?.title)
        assertEquals(1, backend.resolvedFavoriteCalls)

        val resume = gateway.resumePoints().successValue()
        assertEquals(ContentRef.HubContent("favorite-1"), resume.single().ref)
        assertEquals(.2f, resume.single().progress)

        assertTrue(gateway.isFavorite(ContentRef.HubContent("favorite-1")).successValue())
        assertFalse(gateway.toggleFavorite(ContentRef.HubContent("favorite-1")).successValue())
        assertEquals(listOf("favorite-1"), backend.removedFavorites)
        assertTrue(backend.localWriteAttempts.isEmpty())
    }

    @Test
    fun productionAdapterMutatesTheHubAndNeverTheLocalStore() = runTest {
        val store = ReadOnlyHubStore(HubSource(3, "Home", "https://hub.example", addedMs = 1))
        val transport = RecordingHubTransport()
        val vault = HubSessionVault(TestPrefs(), PlainCipher).apply { store(3, "session") }
        val registry = HubRegistry(store, HubApi(transport), vault)
        val gateway = HubCatalogGateway(SourceId.Hub(3, 7), registry)

        gateway.groups(ChannelKind.LIVE).successValue()
        gateway.channels(ChannelKind.LIVE, "Live").successValue()
        gateway.seriesGroups("Shows").successValue()
        gateway.xtreamSeries("Shows").successValue()
        gateway.episodes("series", null).successValue()
        gateway.search("news").successValue()
        val nowAiring = gateway.nowAiring().successValue()
        gateway.guideIds().successValue()
        gateway.favorites().successValue()
        gateway.resumePoints().successValue()
        gateway.guideFor(ContentRef.HubContent("stable-1")).successValue()
        gateway.detail(ContentRef.HubContent("stable-1")).successValue()
        gateway.seriesDetail(
            ContentRef.HubContent("series-content"),
            "series",
            null,
        ).successValue()
        gateway.seriesDetail(
            ContentRef.HubContent("xtream-content"),
            "xs:91",
            "91",
        ).successValue()
        gateway.isFavorite(ContentRef.HubContent("stable/1")).successValue()
        assertTrue(gateway.toggleFavorite(ContentRef.HubContent("stable/1")).successValue())
        assertFalse(
            gateway.setFavorite(ContentRef.HubContent("stable/1"), false).successValue(),
        )

        assertTrue(
            transport.seen.any {
                it.method == "PUT" &&
                    it.url == "https://hub.example/api/v1/playlists/7/favorites" &&
                    it.body == """{"contentId":"stable/1"}"""
            },
        )
        assertTrue(
            transport.seen.any {
                it.method == "DELETE" &&
                    it.url ==
                    "https://hub.example/api/v1/playlists/7/favorites?contentId=stable%2F1"
            },
        )
        assertEquals(
            CatalogProgramme("guide", "Now", "On air", 10, 20),
            nowAiring["guide"],
        )
        assertTrue(store.writeAttempts.isEmpty())
    }

    @Test
    fun hubGatewayCannotAcquireALocalFavoriteStore() {
        val forbidden = setOf(
            FavoriteRepository::class.java,
            FavoriteStore::class.java,
            Storage::class.java,
        )
        val collaborators = HubCatalogGateway::class.java.declaredConstructors
            .flatMap { it.parameterTypes.toList() }

        assertTrue(
            "HubCatalogGateway must keep favourites behind the hub API, but accepted " +
                collaborators.joinToString { it.simpleName },
            collaborators.none { candidate ->
                forbidden.any { local -> local.isAssignableFrom(candidate) }
            },
        )
    }

    @Test
    fun typedHubFailuresBecomeRenderReadyOutcomes() = runTest {
        val backend = FakeHubBackend()
        val gateway = HubCatalogGateway(SourceId.Hub(3, 7), backend)

        backend.failure = HubUnauthorizedException("expired", "gone")
        assertSame(CatalogResult.SignedOut, gateway.groups(0))

        backend.failure = HubUnreachableException("offline")
        assertSame(CatalogResult.Unreachable, gateway.groups(0))

        val other = IllegalStateException("bad response")
        backend.failure = other
        val result = gateway.groups(0) as CatalogResult.Failed
        assertSame(other, result.cause)
    }

    @Test
    fun hubTraitsAdvertiseRemoteOwnershipAndCapabilities() = runTest {
        val traits = HubCatalogGateway(SourceId.Hub(3, 7), FakeHubBackend()).traits()
        assertTrue(traits.hasXtreamSeries)
        assertTrue(traits.hasGuide)
        assertTrue(traits.hasAccountPanel)
        assertTrue(traits.favoritesAreServerSide)
        assertTrue(traits.resumeIsServerSide)
        assertTrue(traits.supportsRefresh)
        assertFalse(traits.supportsSourceEditing)
    }

    @Test
    fun detailJoinsServerOwnedResumeProgress() = runTest {
        val backend = FakeHubBackend().apply {
            resumeRows = listOf(ResumePointDto("movie-1", 30, 60, 4))
        }
        val gateway = HubCatalogGateway(SourceId.Hub(3, 7), backend)

        val detail = gateway.detail(ContentRef.HubContent("movie-1")).successValue()

        assertEquals(ContentRef.HubContent("movie-1"), detail?.item?.ref)
        assertEquals(.5f, detail?.item?.progress)
    }

    @Test
    fun m3uSeriesGroupsFallBackFromTheEmptyXtreamCatalog() = runTest {
        val backend = FakeHubBackend().apply {
            seriesGroupPage = SeriesGroupPageDto(
                items = listOf(
                    com.buco7854.opentv.contract.SeriesGroupDto(
                        contentId = "m3u-series",
                        seriesKey = "A Show",
                        count = 3,
                        logo = null,
                        groupTitle = "Drama",
                    )
                ),
                total = 1,
                offset = 0,
                limit = 50,
            )
        }
        val gateway = HubCatalogGateway(SourceId.Hub(3, 7), backend)

        val page = gateway.xtreamSeries("Drama", 0, 50, "").successValue()

        assertEquals(listOf(ContentRef.HubContent("m3u-series")), page.items.map { it.ref })
        assertEquals(listOf("A Show"), page.items.map { it.title })
    }

    @Test
    fun providerSeriesIdAboveJavascriptSafeIntegerReachesCatalogNavigationUnchanged() = runTest {
        val providerId = "9007199254740993"
        val backend = FakeHubBackend().apply {
            xtreamPage = XtreamSeriesPageDto(
                items = listOf(
                    com.buco7854.opentv.contract.XtreamSeriesListItemDto(
                        contentId = "precise-series",
                        seriesId = providerId,
                        name = "Precise",
                        cover = null,
                        genre = null,
                        rating = null,
                    ),
                ),
                total = 1,
                offset = 0,
                limit = 50,
            )
        }

        val item = HubCatalogGateway(SourceId.Hub(3, 7), backend)
            .xtreamSeries("Drama", 0, 50, "")
            .successValue()
            .items
            .single()

        assertEquals(providerId, item.seriesId)
        assertEquals("xs:$providerId", item.seriesKey)
    }

    @Test
    fun episodePagingPreservesTheServersCompleteSeasonList() = runTest {
        val backend = FakeHubBackend().apply {
            episodePage = EpisodePageDto(
                items = emptyList(),
                total = 80,
                offset = 0,
                limit = 50,
                seasons = listOf(1, 2, 4),
                seriesContentId = null,
            )
        }

        val page = HubCatalogGateway(SourceId.Hub(3, 7), backend)
            .episodes("show", null, 0, 50)
            .successValue()

        assertEquals(listOf(1, 2, 4), page.seasons)
        assertEquals(80, page.total)
    }

    @Test
    fun xtreamSeriesDetailUsesTheSeriesEndpointAndBindsItsContentIdentity() = runTest {
        val backend = FakeHubBackend().apply {
            xtreamDetail = XtreamSeriesDetailDto(
                series = XtreamSeriesDto(
                    contentId = "series-content",
                    playlistId = 7,
                    seriesId = "91",
                    name = "The Show",
                    categoryName = "Drama",
                    cover = "poster-cap",
                    plot = "Plot",
                    castNames = "Cast",
                    genre = "Drama",
                    rating = 8.5,
                    episodesFetchedAtMs = 1,
                ),
                episodes = emptyList(),
            )
        }
        val gateway = HubCatalogGateway(SourceId.Hub(3, 7), backend)

        val detail = gateway.seriesDetail(
            ContentRef.HubContent("series-content"),
            seriesKey = "xs:91",
            seriesId = "91",
        ).successValue()

        assertEquals("The Show", detail?.item?.title)
        assertEquals(ContentRef.HubContent("series-content"), detail?.item?.ref)
        assertEquals("Plot", detail?.description)
        assertEquals(0, backend.contentCalls)

        assertTrue(
            gateway.seriesDetail(
                ContentRef.HubContent("different-content"),
                seriesKey = "xs:91",
                seriesId = "91",
            ) is CatalogResult.Failed,
        )
    }

    @Test
    fun xtreamSeriesDetailDoesNotTurnAProviderEpisodeFailureIntoAnEmptySeries() = runTest {
        val backend = FakeHubBackend().apply {
            xtreamDetail = XtreamSeriesDetailDto(
                series = XtreamSeriesDto(
                    contentId = "series-content",
                    playlistId = 7,
                    seriesId = "91",
                    name = "The Show",
                    categoryName = "Drama",
                    cover = null,
                    plot = null,
                    castNames = null,
                    genre = null,
                    rating = null,
                    episodesFetchedAtMs = 0,
                ),
                episodes = emptyList(),
                error = "Couldn't load episodes",
            )
        }

        val result = HubCatalogGateway(SourceId.Hub(3, 7), backend).seriesDetail(
            ContentRef.HubContent("series-content"),
            seriesKey = "xs:91",
            seriesId = "91",
        )

        assertTrue(result is CatalogResult.Failed)
    }

    @Test
    fun m3uSeriesDetailUsesEpisodesAndRejectsAMismatchedStableIdentity() = runTest {
        val backend = FakeHubBackend().apply {
            episodePage = EpisodePageDto(
                items = emptyList(),
                total = 3,
                offset = 0,
                limit = 1,
                seasons = listOf(1),
                seriesContentId = "m3u-content",
                groupTitle = "Drama",
            )
        }
        val gateway = HubCatalogGateway(SourceId.Hub(3, 7), backend)

        val detail = gateway.seriesDetail(
            ContentRef.HubContent("m3u-content"),
            seriesKey = "A Show",
            seriesId = null,
        ).successValue()

        assertEquals("A Show", detail?.item?.title)
        assertEquals(3, detail?.item?.count)
        assertEquals(0, backend.contentCalls)
        assertTrue(
            gateway.seriesDetail(
                ContentRef.HubContent("other-content"),
                seriesKey = "A Show",
                seriesId = null,
            ) is CatalogResult.Failed,
        )
    }
}

private fun <T> CatalogResult<T>.successValue(): T =
    (this as CatalogResult.Success<T>).value

private data class ChannelRequest(
    val kind: Int,
    val group: String,
    val offset: Int,
    val limit: Int,
    val filter: String,
)

private fun channel(contentId: String, name: String) = ChannelDto(
    contentId = contentId,
    id = 1,
    playlistId = 7,
    name = name,
    logo = "poster-cap",
    groupTitle = "Movies",
    tvgId = "guide",
    kind = 1,
    seriesKey = null,
    season = null,
    episode = null,
    position = 0,
    xtreamStreamId = "9",
    catchupDays = 0,
    hasCatchup = false,
    description = "Description",
    durationSecs = 120,
    airDate = null,
)

private class FakeHubBackend : HubCatalogBackend {
    override val baseUrl = "https://hub.example"
    var channelPage = ChannelPageDto(emptyList(), 0, 0, 50)
    var resolved = FavoritesResolvedDto()
    var seriesGroupPage = SeriesGroupPageDto(emptyList(), 0, 0, 50)
    var xtreamPage = XtreamSeriesPageDto(emptyList(), 0, 0, 50)
    var episodePage = EpisodePageDto(emptyList(), 0, 0, 50, emptyList(), null)
    var xtreamDetail: XtreamSeriesDetailDto? = null
    var favoriteRows = emptyList<FavoriteDto>()
    var resumeRows = emptyList<ResumePointDto>()
    var nowRows = emptyList<ProgrammeDto>()
    var guideRows = emptyList<String>()
    var channelRequest: ChannelRequest? = null
    var failure: Throwable? = null
    var resumeCalls = 0
    var resolvedFavoriteCalls = 0
    var contentCalls = 0
    val removedFavorites = mutableListOf<String>()
    val addedFavorites = mutableListOf<String>()
    val localWriteAttempts = mutableListOf<String>()

    private fun maybeFail() {
        failure?.let { throw it }
    }

    override suspend fun groups(kind: Int): List<GroupCountDto> {
        maybeFail()
        return listOf(GroupCountDto("Group", 1))
    }

    override suspend fun channels(kind: Int, group: String, offset: Int, limit: Int, filter: String) =
        channelPage.also { channelRequest = ChannelRequest(kind, group, offset, limit, filter) }

    override suspend fun seriesGroups(group: String, offset: Int, limit: Int, filter: String) =
        seriesGroupPage

    override suspend fun xtreamSeries(category: String, offset: Int, limit: Int, filter: String) =
        xtreamPage

    override suspend fun episodes(seriesKey: String, season: Int?, offset: Int, limit: Int) =
        episodePage

    override suspend fun xtreamSeriesDetail(seriesId: String): XtreamSeriesDetailDto =
        checkNotNull(xtreamDetail)

    override suspend fun search(query: String) = SearchResultsDto()
    override suspend fun nowAiring(): List<ProgrammeDto> = nowRows
    override suspend fun guideIds(): List<String> = guideRows
    override suspend fun favorites() = favoriteRows

    override suspend fun favoritesResolved() =
        resolved.also { resolvedFavoriteCalls++ }

    override suspend fun addFavorite(contentId: String) {
        addedFavorites += contentId
    }

    override suspend fun removeFavorite(contentId: String) {
        removedFavorites += contentId
    }

    override suspend fun resume() =
        resumeRows.also { resumeCalls++ }

    override suspend fun content(contentId: String) =
        channel(contentId, "Detail").also { contentCalls++ }
    override suspend fun guide(contentId: String): List<GuideEntryDto> = emptyList()
}

private class ReadOnlyHubStore(private val source: HubSource) : HubSourceStore {
    val writeAttempts = mutableListOf<String>()
    override fun observeAll(): Flow<List<HubSource>> = flowOf(listOf(source))
    override suspend fun getAll() = listOf(source)
    override suspend fun get(id: Long) = source.takeIf { it.id == id }
    override suspend fun upsert(source: HubSource): Long = write("upsert")
    override suspend fun delete(id: Long) = write<Unit>("delete")
    override suspend fun updateIdentity(
        id: Long,
        userId: String?,
        username: String?,
        role: String?,
        seenMs: Long,
    ) = write<Unit>("updateIdentity")

    override suspend fun clearIdentity(id: Long) = write<Unit>("clearIdentity")

    private fun <T> write(name: String): T {
        writeAttempts += name
        error("Hub catalog must not write local storage: $name")
    }
}

private class RecordingHubTransport : HttpTransport {
    val seen = mutableListOf<HttpRequestSpec>()
    override suspend fun execute(request: HttpRequestSpec): HttpResponseSpec {
        seen += request
        if (request.method != "GET") {
            return HttpResponseSpec(204, emptyMap(), "")
        }
        val body = when {
            "/channels?" in request.url ->
                SERVER_JSON.encodeToString(ChannelPageDto(emptyList(), 0, 0, 50))
            "/series-groups?" in request.url ->
                SERVER_JSON.encodeToString(SeriesGroupPageDto(emptyList(), 0, 0, 50))
            "/xtream-series?" in request.url ->
                SERVER_JSON.encodeToString(XtreamSeriesPageDto(emptyList(), 0, 0, 50))
            "/series/" in request.url && "/episodes?" in request.url ->
                SERVER_JSON.encodeToString(
                    EpisodePageDto(
                        emptyList(),
                        0,
                        0,
                        50,
                        emptyList(),
                        "series-content",
                    ),
                )
            "/xseries/91" in request.url ->
                SERVER_JSON.encodeToString(
                    XtreamSeriesDetailDto(
                        XtreamSeriesDto(
                            "xtream-content",
                            7,
                            "91",
                            "Series",
                            "Shows",
                            null,
                            null,
                            null,
                            null,
                            null,
                            0,
                        ),
                        emptyList(),
                    ),
                )
            "/search?" in request.url -> SERVER_JSON.encodeToString(SearchResultsDto())
            "/now-airing" in request.url ->
                SERVER_JSON.encodeToString(
                    mapOf(
                        "guide" to ProgrammeDto(
                            id = 1,
                            playlistId = 7,
                            tvgId = "guide",
                            title = "Now",
                            description = "On air",
                            startMs = 10,
                            endMs = 20,
                        ),
                    ),
                )
            "/favorites/resolved" in request.url ->
                SERVER_JSON.encodeToString(FavoritesResolvedDto())
            "/content/stable-1/guide" in request.url -> "[]"
            "/content/stable-1" in request.url ->
                SERVER_JSON.encodeToString(channel("stable-1", "Detail"))
            else -> "[]"
        }
        return HttpResponseSpec(200, emptyMap(), body)
    }
}

private val SERVER_JSON = Json { encodeDefaults = true }

private object PlainCipher : TokenCipher {
    override fun encrypt(plain: ByteArray) = plain
    override fun decrypt(blob: ByteArray) = blob
}

private class TestPrefs : SharedPreferences {
    private val values = mutableMapOf<String, String?>()
    override fun getString(key: String, defValue: String?) = values[key] ?: defValue
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        override fun putString(key: String, value: String?) = apply { values[key] = value }
        override fun remove(key: String) = apply { values.remove(key) }
        override fun apply() = Unit
        override fun commit() = true
        override fun clear() = apply { values.clear() }
        override fun putStringSet(key: String, values: MutableSet<String>?) =
            unsupported<SharedPreferences.Editor>()
        override fun putInt(key: String, value: Int) = unsupported<SharedPreferences.Editor>()
        override fun putLong(key: String, value: Long) = unsupported<SharedPreferences.Editor>()
        override fun putFloat(key: String, value: Float) = unsupported<SharedPreferences.Editor>()
        override fun putBoolean(key: String, value: Boolean) =
            unsupported<SharedPreferences.Editor>()
    }
    override fun getAll(): MutableMap<String, *> = unsupported()
    override fun getStringSet(key: String, defValues: MutableSet<String>?) = unsupported<MutableSet<String>>()
    override fun getInt(key: String, defValue: Int) = unsupported<Int>()
    override fun getLong(key: String, defValue: Long) = unsupported<Long>()
    override fun getFloat(key: String, defValue: Float) = unsupported<Float>()
    override fun getBoolean(key: String, defValue: Boolean) = unsupported<Boolean>()
    override fun contains(key: String) = values.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    private fun <T> unsupported(): T = throw UnsupportedOperationException()
}
