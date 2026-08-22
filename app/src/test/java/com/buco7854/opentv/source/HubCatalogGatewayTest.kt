package com.buco7854.opentv.source

import android.content.SharedPreferences
import com.buco7854.opentv.contract.AccountInfoDto
import com.buco7854.opentv.contract.ChannelDto
import com.buco7854.opentv.contract.ChannelListItemDto
import com.buco7854.opentv.contract.ChannelPageDto
import com.buco7854.opentv.contract.EpisodePageDto
import com.buco7854.opentv.contract.FavoriteDto
import com.buco7854.opentv.contract.FavoritesResolvedDto
import com.buco7854.opentv.contract.GroupCountDto
import com.buco7854.opentv.contract.GuideEntryDto
import com.buco7854.opentv.contract.MetadataDto
import com.buco7854.opentv.contract.ProgrammeDto
import com.buco7854.opentv.contract.PlaylistCapabilitiesDto
import com.buco7854.opentv.contract.PlaylistDeleteInfoDto
import com.buco7854.opentv.contract.PlaylistDetailDto
import com.buco7854.opentv.contract.PlaylistDto
import com.buco7854.opentv.contract.PlaylistEditDto
import com.buco7854.opentv.contract.PlaylistEditField as WirePlaylistEditField
import com.buco7854.opentv.contract.PlaylistEpgRefreshStatus
import com.buco7854.opentv.contract.PlaylistRefreshJobDto
import com.buco7854.opentv.contract.PlaylistRefreshJobStatus
import com.buco7854.opentv.contract.PlaylistOperationCapabilityDto
import com.buco7854.opentv.contract.PlaylistOperationExecution
import com.buco7854.opentv.contract.PlaylistRefreshResultDto
import com.buco7854.opentv.contract.PlaylistUpdateRequest
import com.buco7854.opentv.contract.PlaylistOperation as WirePlaylistOperation
import com.buco7854.opentv.contract.ResumePointDto
import com.buco7854.opentv.contract.SearchResultsDto
import com.buco7854.opentv.contract.SeriesGroupPageDto
import com.buco7854.opentv.contract.SeriesHitDto
import com.buco7854.opentv.contract.XtreamSeriesPageDto
import com.buco7854.opentv.contract.XtreamSeriesDetailDto
import com.buco7854.opentv.contract.XtreamSeriesDto
import com.buco7854.opentv.core.meta.CastMember
import com.buco7854.opentv.core.meta.decodeCast
import com.buco7854.opentv.core.meta.encodeCast
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
import com.buco7854.opentv.hub.HubPlaylistCapabilities
import com.buco7854.opentv.hub.HubPlaylistOperation
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
import org.junit.Assert.assertNull
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
            resolved = FavoritesResolvedDto(
                live = listOf(channel("favorite-1", "Favorite").copy(xtreamStreamId = null)),
            )
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
        assertEquals(listOf("guide"), backend.nowAiringTvgIds)
        assertEquals(listOf("guide"), backend.guideTvgIds)
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
    fun favoriteDecorationFailureDoesNotFailTheLoadedFavorites() = runTest {
        val backend = FakeHubBackend().apply {
            resolved = FavoritesResolvedDto(
                live = listOf(channel("favorite-1", "Favorite").copy(xtreamStreamId = null)),
            )
            decorationFailure = IllegalStateException("guide unavailable")
        }

        val favorites = HubCatalogGateway(SourceId.Hub(3, 7), backend)
            .favorites()
            .successValue()

        assertEquals(listOf("Favorite"), favorites.items.map { it.title })
        assertFalse(favorites.items.single().hasGuide)
        assertEquals(null, favorites.items.single().nowAiring)
    }

    @Test
    fun xtreamFavoriteKeepsTheEpisodeListingIdentityUsedByBrowse() = runTest {
        val backend = FakeHubBackend().apply {
            resolved = FavoritesResolvedDto(
                series = listOf(
                    SeriesHitDto(
                        contentId = "series-content",
                        seriesKey = "The Show",
                        count = 0,
                        logo = null,
                        groupTitle = "Drama",
                        xtreamSeriesId = "91",
                    ),
                ),
            )
        }
        val gateway = HubCatalogGateway(SourceId.Hub(3, 7), backend)

        val favorite = gateway.favorites().successValue().items.single()
        gateway.episodes(checkNotNull(favorite.seriesKey)).successValue()

        assertEquals("The Show", favorite.title)
        assertEquals("91", favorite.seriesId)
        assertEquals("xs:91", favorite.seriesKey)
        assertEquals("xs:91", backend.episodeSeriesKey)
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
        val nowAiring = gateway.nowAiring(setOf("guide")).successValue()
        gateway.guideIds(setOf("guide")).successValue()
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
        gateway.playlistCapabilities().successValue()
        gateway.clearWatchProgress().successValue()
        gateway.correctCategoryType("News & Sport", ChannelKind.MOVIE).successValue()

        assertTrue(
            transport.seen.any {
                it.method == "PUT" &&
                    it.url == "https://hub.example/api/v1/playlists/7/favorites" &&
                    it.body == """{"contentId":"stable/1"}"""
            },
        )
        assertTrue(
            transport.seen.any {
                it.method == "POST" &&
                    it.url == "https://hub.example/api/v1/playlists/7/clear-progress"
            },
        )
        assertTrue(
            transport.seen.any {
                it.method == "PUT" &&
                    it.url == "https://hub.example/api/v1/playlists/7/group-kind" &&
                    it.body == """{"groupTitle":"News & Sport","kind":1}"""
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
    fun productionAdapterMapsAMissingHubRowToSignedOut() = runTest {
        val store = ReadOnlyHubStore(HubSource(99, "Other", "https://hub.example", addedMs = 1))
        val registry = HubRegistry(
            store,
            HubApi(RecordingHubTransport()),
            HubSessionVault(TestPrefs(), PlainCipher),
        )

        val result = HubCatalogGateway(SourceId.Hub(3, 7), registry).search("news")

        assertSame(CatalogResult.SignedOut, result)
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
        assertTrue(traits.supportsSourceEditing)
        assertTrue(traits.usesXtreamCredentials)
    }

    @Test
    fun ordinaryUserTraitsUsePlaylistDetailInsteadOfAdministratorCapabilities() = runTest {
        val m3uBackend = FakeHubBackend().apply {
            operationCapabilities = HubPlaylistCapabilities(emptyMap())
            detailDto = detailDto(
                name = "Family M3U",
                mode = "url",
                isXtreamNative = false,
            )
        }
        val xtreamBackend = FakeHubBackend().apply {
            operationCapabilities = HubPlaylistCapabilities(emptyMap())
            detailDto = detailDto(
                name = "Native panel",
                mode = "xtream",
                isXtreamNative = true,
            )
        }

        val m3u = HubCatalogGateway(SourceId.Hub(3, 7), m3uBackend).traits()
        val xtream = HubCatalogGateway(SourceId.Hub(3, 8), xtreamBackend).traits()

        assertEquals("Family M3U", m3u.title)
        assertFalse(m3u.hasXtreamSeries)
        assertEquals("Native panel", xtream.title)
        assertTrue(xtream.hasXtreamSeries)
        assertEquals(0, m3uBackend.editCalls)
        assertEquals(0, xtreamBackend.editCalls)
    }

    @Test
    fun playlistCapabilitiesExposeNativeAdministrationCalls() = runTest {
        val backend = FakeHubBackend().apply {
            operationCapabilities = HubPlaylistCapabilities(
                mapOf(
                    WirePlaylistOperation.CLEAR_WATCH_PROGRESS to HubPlaylistOperation.InApp,
                    WirePlaylistOperation.CORRECT_CATEGORY_TYPE to HubPlaylistOperation.InApp,
                    WirePlaylistOperation.EDIT to HubPlaylistOperation.InApp,
                ),
            )
        }
        val gateway = HubCatalogGateway(SourceId.Hub(3, 7), backend)

        val capabilities = gateway.playlistCapabilities().successValue()
        assertSame(
            PlaylistOperationAvailability.InApp,
            capabilities[PlaylistOperation.CLEAR_WATCH_PROGRESS],
        )
        assertSame(
            PlaylistOperationAvailability.InApp,
            capabilities[PlaylistOperation.CORRECT_CATEGORY_TYPE],
        )
        assertSame(
            PlaylistOperationAvailability.InApp,
            capabilities[PlaylistOperation.EDIT],
        )

        gateway.clearWatchProgress().successValue()
        gateway.correctCategoryType("Documentaries", ChannelKind.MOVIE).successValue()
        assertEquals(1, backend.clearProgressCalls)
        assertEquals("Documentaries" to ChannelKind.MOVIE, backend.groupKind)
    }

    @Test
    fun nativePlaylistAdministrationMapsWriteOnlyFormsProgressDeleteAndAccount() = runTest {
        val backend = FakeHubBackend()
        val gateway = HubCatalogGateway(SourceId.Hub(3, 7), backend)

        val form = gateway.playlistEditForm().successValue()
        assertEquals(PlaylistEditMode.XTREAM, form.mode)
        assertEquals(
            setOf(
                PlaylistEditField.NAME,
                PlaylistEditField.SERVER,
                PlaylistEditField.USERNAME,
                PlaylistEditField.PASSWORD,
            ),
            form.fields,
        )
        assertTrue(PlaylistEditField.PASSWORD in form.storedFields)

        gateway.updatePlaylist(PlaylistEditUpdate(name = "Renamed")).successValue()
        assertEquals("Renamed", backend.updateRequest?.name)
        assertEquals(null, backend.updateRequest?.password)

        val progress = mutableListOf<PlaylistRefreshProgress>()
        val refreshed = gateway.refreshPlaylist(onProgress = progress::add).successValue()
        assertTrue(refreshed.catalogChanged)
        assertEquals(PlaylistEpgRefreshOutcome.SUCCEEDED, refreshed.epg)
        assertEquals(PlaylistRefreshProgress.Queued, progress[0])
        assertEquals(PlaylistRefreshProgress.Running, progress[1])
        assertEquals(
            PlaylistRefreshProgress.Finished(refreshed),
            progress.last(),
        )

        val deletion = gateway.playlistDeleteInfo().successValue()
        assertEquals("This cannot be undone.", deletion.warning)
        gateway.deletePlaylist().successValue()
        assertEquals(1, backend.deleteCalls)

        val account = gateway.providerAccount().successValue()
        assertEquals(2, account.maxConnections)
        assertEquals("Active", account.status)
    }

    @Test
    fun detailDoesNotWaitForTheResumeCollection() = runTest {
        val backend = FakeHubBackend().apply {
            resumeRows = listOf(ResumePointDto("movie-1", 30, 60, 4))
        }
        val gateway = HubCatalogGateway(SourceId.Hub(3, 7), backend)

        val detail = gateway.detail(ContentRef.HubContent("movie-1")).successValue()

        assertEquals(ContentRef.HubContent("movie-1"), detail?.item?.ref)
        assertNull(detail?.item?.progress)
        assertEquals(0, backend.resumeCalls)
        assertEquals(1, backend.contentCalls)
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

    @Test
    fun aFilmsCastComesFromTheServerBecauseNoLocalRowHoldsIt() = runTest {
        // A series carries its cast on the detail response; a film does not, and the
        // channel row a local playlist would read it from does not exist for a source
        // the server owns. Without this call every film on a server playlist loses its
        // cast and rating while every series beside it keeps them.
        val backend = FakeHubBackend().apply {
            vodInfo = MetadataDto(
                cacheKey = "xtreamvod:7:22",
                title = "The Film",
                year = "2011",
                overview = "A plot",
                rating = 7.5,
                castNames = "Alice Isaaz, Kevin Bago",
                castJson = encodeCast(listOf(CastMember("Alice Isaaz", "cast-token"))),
                posterUrl = "poster",
                infoLine = "Drama · 98 min",
                sourceId = "22",
                fetchedAtMs = 5,
            )
        }

        val gateway = HubCatalogGateway(SourceId.Hub(3, 7), backend)
        gateway.movieMetadata(ContentRef.HubContent("film-content"), enrich = false).successValue()
        val metadata = gateway
            .movieMetadata(ContentRef.HubContent("film-content"), enrich = true)
            .successValue()

        assertEquals("Alice Isaaz, Kevin Bago", metadata?.castNames)
        assertEquals(7.5, metadata?.rating)
        assertEquals("Drama · 98 min", metadata?.infoLine)
        assertEquals("https://hub.example/api/v1/img?u=poster", metadata?.posterUrl)
        assertEquals(
            "https://hub.example/api/v1/img?u=cast-token",
            decodeCast(metadata?.castJson).single().photo,
        )
        assertEquals(2, backend.vodInfoCalls)
        assertEquals(listOf(false, true), backend.vodInfoEnrichments)
        // The panel's own id is text and the local field means the metadata provider's
        // numeric id, so it is dropped rather than forced into a field of another meaning.
        assertNull(metadata?.sourceId)
    }

    @Test
    fun seriesMetadataTurnsServerImageCapabilitiesIntoAndroidImageUrls() = runTest {
        val backend = FakeHubBackend().apply {
            metadata = MetadataDto(
                cacheKey = "tv:The Show",
                title = "The Show",
                year = "2011",
                overview = null,
                rating = null,
                castNames = "Cast: Alice Isaaz",
                castJson = encodeCast(listOf(CastMember("Alice Isaaz", "person-token"))),
                posterUrl = "series-poster-token",
                infoLine = null,
                sourceId = "55",
                fetchedAtMs = 5,
            )
        }

        val metadata = HubCatalogGateway(SourceId.Hub(3, 7), backend)
            .seriesMetadata("The Show")
            .successValue()

        assertEquals(1, backend.metadataCalls)
        assertEquals("series", backend.metadataType)
        assertEquals("The Show", backend.metadataTitle)
        assertEquals(
            "https://hub.example/api/v1/img?u=person-token",
            decodeCast(metadata?.castJson).single().photo,
        )
        assertEquals(
            "https://hub.example/api/v1/img?u=series-poster-token",
            metadata?.posterUrl,
        )
    }

    @Test
    fun m3uSeriesWithNoEpisodesOpensEmptyRatherThanFailingTheIdentityCheck() = runTest {
        // The server mints a series identity from its episodes, so it reports none for a
        // series that currently has no episodes -- which is what a favourite looks like
        // while its playlist is being refreshed and the channel table has been replaced.
        // Reading that as a mismatched identity failed the screen outright, leaving a
        // favourited series with no episodes and therefore no play or download control.
        val backend = FakeHubBackend().apply {
            episodePage = EpisodePageDto(
                items = emptyList(),
                total = 0,
                offset = 0,
                limit = 1,
                seasons = emptyList(),
                seriesContentId = null,
            )
        }

        val detail = HubCatalogGateway(SourceId.Hub(3, 7), backend).seriesDetail(
            ContentRef.HubContent("m3u-content"),
            seriesKey = "A Show",
            seriesId = null,
        ).successValue()

        assertEquals("A Show", detail?.item?.title)
        assertEquals(0, detail?.item?.count)
        assertEquals(ContentRef.HubContent("m3u-content"), detail?.item?.ref)
    }
}

private fun <T> CatalogResult<T>.successValue(): T = when (this) {
    is CatalogResult.Success -> value
    is CatalogResult.Failed -> throw AssertionError("Expected success", cause)
    else -> error("Expected success, got $this")
}

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
    var episodeSeriesKey: String? = null
    var failure: Throwable? = null
    var decorationFailure: Throwable? = null
    var resumeCalls = 0
    var resolvedFavoriteCalls = 0
    var contentCalls = 0
    var vodInfoCalls = 0
    val vodInfoEnrichments = mutableListOf<Boolean>()
    var vodInfo: MetadataDto? = null
    var metadataCalls = 0
    var metadataType: String? = null
    var metadataTitle: String? = null
    var metadata: MetadataDto? = null
    var clearProgressCalls = 0
    var deleteCalls = 0
    var refreshStatusCalls = 0
    var groupKind: Pair<String, Int?>? = null
    var updateRequest: PlaylistUpdateRequest? = null
    var editCalls = 0
    var nowAiringTvgIds = emptyList<String>()
    var guideTvgIds = emptyList<String>()
    var detailDto = detailDto("Provider", "xtream", isXtreamNative = true)
    var editDto = PlaylistEditDto(
        id = 7,
        name = "Provider",
        mode = "xtream",
        fields = listOf(
            WirePlaylistEditField.NAME,
            WirePlaylistEditField.SERVER,
            WirePlaylistEditField.USERNAME,
            WirePlaylistEditField.PASSWORD,
        ),
        storedFields = listOf(
            WirePlaylistEditField.SERVER,
            WirePlaylistEditField.USERNAME,
            WirePlaylistEditField.PASSWORD,
        ),
    )
    var refreshDto = PlaylistRefreshResultDto(
        PlaylistDto(7, "Provider", "xtream", true, 123, 40),
        catalogChanged = true,
        epgStatus = PlaylistEpgRefreshStatus.SUCCEEDED,
    )
    var deleteInfoDto = PlaylistDeleteInfoDto(7, "Provider", "This cannot be undone.")
    var accountDto = AccountInfoDto(1, 2, "Active", null, false, null, null, 456, false)
    var operationCapabilities = HubPlaylistCapabilities(
        mapOf(
            WirePlaylistOperation.REFRESH to HubPlaylistOperation.InApp,
            WirePlaylistOperation.EDIT to HubPlaylistOperation.InApp,
            WirePlaylistOperation.DELETE to HubPlaylistOperation.InApp,
            WirePlaylistOperation.CLEAR_WATCH_PROGRESS to HubPlaylistOperation.InApp,
            WirePlaylistOperation.VIEW_PROVIDER_ACCOUNT to HubPlaylistOperation.InApp,
        ),
    )
    val removedFavorites = mutableListOf<String>()
    val addedFavorites = mutableListOf<String>()
    val localWriteAttempts = mutableListOf<String>()

    private fun maybeFail() {
        failure?.let { throw it }
    }

    override suspend fun capabilities() = operationCapabilities.also { maybeFail() }

    override suspend fun detail() = detailDto.also { maybeFail() }

    override suspend fun edit() = editDto.also {
        editCalls++
        maybeFail()
    }

    override suspend fun update(request: PlaylistUpdateRequest) {
        maybeFail()
        updateRequest = request
    }

    override suspend fun startRefresh(force: Boolean) =
        PlaylistRefreshJobDto("refresh-1", PlaylistRefreshJobStatus.QUEUED)
            .also { maybeFail() }

    override suspend fun refreshStatus(refreshId: String) =
        if (refreshStatusCalls++ == 0) {
            PlaylistRefreshJobDto(refreshId, PlaylistRefreshJobStatus.RUNNING)
        } else {
            PlaylistRefreshJobDto(
                refreshId,
                PlaylistRefreshJobStatus.SUCCEEDED,
                refreshDto,
            )
        }.also { maybeFail() }

    override suspend fun deleteInfo() = deleteInfoDto.also { maybeFail() }

    override suspend fun delete() {
        maybeFail()
        deleteCalls++
    }

    override suspend fun account(force: Boolean) = accountDto.also { maybeFail() }

    override suspend fun clearProgress() {
        maybeFail()
        clearProgressCalls++
    }

    override suspend fun setGroupKind(groupTitle: String, kind: Int?) {
        maybeFail()
        groupKind = groupTitle to kind
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
        episodePage.also { episodeSeriesKey = seriesKey }

    override suspend fun xtreamSeriesDetail(seriesId: String): XtreamSeriesDetailDto =
        checkNotNull(xtreamDetail)

    override suspend fun search(query: String) = SearchResultsDto()
    override suspend fun nowAiring(tvgIds: List<String>): List<ProgrammeDto> =
        nowRows.also {
            decorationFailure?.let { throw it }
            nowAiringTvgIds = tvgIds
        }
    override suspend fun guideIds(tvgIds: List<String>): List<String> =
        guideRows.also {
            decorationFailure?.let { throw it }
            guideTvgIds = tvgIds
        }
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
    override suspend fun vodInfo(contentId: String, enrich: Boolean): MetadataDto {
        vodInfoCalls++
        vodInfoEnrichments += enrich
        return vodInfo ?: throw IllegalStateException("no vod info configured")
    }

    override suspend fun metadata(type: String, title: String): MetadataDto {
        metadataCalls++
        metadataType = type
        metadataTitle = title
        return metadata ?: throw IllegalStateException("no metadata configured")
    }
    override suspend fun guide(contentId: String): List<GuideEntryDto> = emptyList()
}

private fun detailDto(
    name: String,
    mode: String,
    isXtreamNative: Boolean,
) = PlaylistDetailDto(
    playlist = PlaylistDto(7, name, mode, true, 123, 40),
    isXtreamNative = isXtreamNative,
    liveCount = 20,
    movieCount = 10,
    seriesCount = 10,
)

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
        val decorationPost = request.method == "POST" &&
            ("/now-airing" in request.url || "/guide-ids" in request.url)
        if (request.method != "GET" && !decorationPost) {
            return HttpResponseSpec(204, emptyMap(), "")
        }
        val body = when {
            "/capabilities" in request.url ->
                SERVER_JSON.encodeToString(
                    PlaylistCapabilitiesDto(
                        listOf(
                            PlaylistOperationCapabilityDto(
                                WirePlaylistOperation.CLEAR_WATCH_PROGRESS,
                                PlaylistOperationExecution.IN_APP,
                            ),
                            PlaylistOperationCapabilityDto(
                                WirePlaylistOperation.CORRECT_CATEGORY_TYPE,
                                PlaylistOperationExecution.IN_APP,
                            ),
                            PlaylistOperationCapabilityDto(
                                WirePlaylistOperation.EDIT,
                                PlaylistOperationExecution.BROWSER,
                                "/browse/7?manage=playlist",
                            ),
                        ),
                    ),
                )
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
            "/guide-ids" in request.url -> "[\"guide\"]"
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
