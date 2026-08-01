package com.buco7854.opentv.ui.search

import com.buco7854.opentv.core.model.ChannelKind
import com.buco7854.opentv.source.CatalogItem
import com.buco7854.opentv.source.CatalogLoadError
import com.buco7854.opentv.source.CatalogResult
import com.buco7854.opentv.source.CatalogSearchResult
import com.buco7854.opentv.source.ContentRef
import com.buco7854.opentv.source.SourceId
import com.buco7854.opentv.ui.CatalogGatewayFake
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    @Test
    fun `opening search for an absent local source initializes on Main immediate`() = runTest {
        Dispatchers.setMain(Dispatchers.Unconfined)
        var viewModel: SearchViewModel? = null
        try {
            val absentSource = CatalogGatewayFake(SourceId.LocalPlaylist(404))

            viewModel = SearchViewModel(absentSource.source, absentSource)

            assertEquals(emptySet<String>(), viewModel.favoriteKeys.value)
            assertEquals(emptySet<String>(), viewModel.guideIds.value)
        } finally {
            viewModel?.viewModelScope?.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `opening search for a hub without a session is a signed out state`() = runTest {
        Dispatchers.setMain(Dispatchers.Unconfined)
        var viewModel: SearchViewModel? = null
        try {
            val missingSession = CatalogGatewayFake(SourceId.Hub(404, 7)).apply {
                favoriteBlock = { _, _ -> CatalogResult.SignedOut }
                guideIdsResult = CatalogResult.SignedOut
            }

            viewModel = SearchViewModel(missingSession.source, missingSession)

            assertEquals(CatalogLoadError.SignedOut, viewModel.state.value.error)
        } finally {
            viewModel?.viewModelScope?.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `local search keeps live movie and collapsed series groupings`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val expected = CatalogSearchResult(
                live = listOf(item("live", ChannelKind.LIVE)),
                movies = listOf(item("movie", ChannelKind.MOVIE)),
                series = listOf(
                    item("series", ChannelKind.SERIES).copy(
                        seriesKey = "The Show",
                        count = 3,
                    )
                ),
            )
            val gateway = CatalogGatewayFake(SourceId.LocalPlaylist(8)).apply {
                searchResult = CatalogResult.Success(expected)
            }
            val viewModel = SearchViewModel(gateway.source, gateway)

            viewModel.query.value = "show"
            advanceTimeBy(250)
            runCurrent()

            assertEquals(expected, viewModel.state.value.results)
            assertEquals(null, viewModel.state.value.error)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `hub signed out and unreachable searches surface typed states`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val signedOut = CatalogGatewayFake(SourceId.Hub(1, 2)).apply {
                searchResult = CatalogResult.SignedOut
            }
            val signedOutViewModel = SearchViewModel(signedOut.source, signedOut)
            signedOutViewModel.query.value = "news"
            advanceTimeBy(250)
            advanceUntilIdle()
            assertEquals(CatalogLoadError.SignedOut, signedOutViewModel.state.value.error)

            val unreachable = CatalogGatewayFake(SourceId.Hub(1, 3)).apply {
                searchResult = CatalogResult.Unreachable
            }
            val unreachableViewModel = SearchViewModel(unreachable.source, unreachable)
            unreachableViewModel.query.value = "news"
            advanceTimeBy(250)
            advanceUntilIdle()
            assertEquals(
                CatalogLoadError.Unreachable,
                unreachableViewModel.state.value.error,
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `clearing the query clears the previous load failure`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val gateway = CatalogGatewayFake(SourceId.Hub(1, 2)).apply {
                searchResult = CatalogResult.Unreachable
            }
            val viewModel = SearchViewModel(gateway.source, gateway)
            viewModel.query.value = "news"
            advanceTimeBy(250)
            advanceUntilIdle()
            assertEquals(CatalogLoadError.Unreachable, viewModel.state.value.error)

            viewModel.query.value = ""
            advanceTimeBy(250)
            advanceUntilIdle()

            assertEquals(null, viewModel.state.value.error)
            assertEquals(false, viewModel.state.value.loading)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `local search failure keeps the legacy empty result state`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val gateway = CatalogGatewayFake(SourceId.LocalPlaylist(8)).apply {
                searchResult = CatalogResult.Failed(IllegalStateException("database"))
            }
            val viewModel = SearchViewModel(gateway.source, gateway)

            viewModel.query.value = "news"
            advanceTimeBy(250)
            advanceUntilIdle()

            assertEquals(CatalogSearchResult(), viewModel.state.value.results)
            assertEquals(null, viewModel.state.value.error)
            assertEquals(false, viewModel.state.value.loading)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `a non cancellable old search cannot replace a newer query result`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val old = CompletableDeferred<CatalogResult<CatalogSearchResult>>()
            val fresh = CatalogSearchResult(movies = listOf(item("fresh", ChannelKind.MOVIE)))
            val gateway = CatalogGatewayFake(SourceId.Hub(1, 2)).apply {
                searchBlock = { query ->
                    if (query == "old") withContext(NonCancellable) { old.await() }
                    else CatalogResult.Success(fresh)
                }
            }
            val viewModel = SearchViewModel(gateway.source, gateway)

            viewModel.query.value = "old"
            advanceTimeBy(250)
            runCurrent()
            viewModel.query.value = "new"
            advanceTimeBy(250)
            runCurrent()
            assertEquals(fresh, viewModel.state.value.results)

            old.complete(
                CatalogResult.Success(
                    CatalogSearchResult(live = listOf(item("stale", ChannelKind.LIVE))),
                ),
            )
            advanceUntilIdle()

            assertEquals(fresh, viewModel.state.value.results)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `panel and m3u series with colliding legacy keys keep distinct row identities`() {
        val panel = CatalogItem(
            ref = ContentRef.LocalUrl("x:123", 0),
            title = "Panel series",
            imageUrl = null,
            kind = ChannelKind.SERIES,
            group = "Shows",
            seriesKey = "xs:123",
            seriesId = "123",
        )
        val m3u = panel.copy(
            title = "M3U series",
            seriesKey = "x:123",
            seriesId = null,
        )

        org.junit.Assert.assertNotEquals(searchItemKey(panel), searchItemKey(m3u))
    }

    private fun item(key: String, kind: Int) = CatalogItem(
        ref = ContentRef.LocalUrl("https://example.test/$key", key.hashCode().toLong()),
        title = key,
        imageUrl = null,
        kind = kind,
        group = "Group",
    )
}
