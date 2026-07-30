package com.buco7854.opentv.ui.browse

import com.buco7854.opentv.core.model.ChannelKind
import com.buco7854.opentv.source.CatalogGroup
import com.buco7854.opentv.source.CatalogItem
import com.buco7854.opentv.source.CatalogLoadError
import com.buco7854.opentv.source.CatalogResult
import com.buco7854.opentv.source.ContentRef
import com.buco7854.opentv.source.Page
import com.buco7854.opentv.source.SourceId
import com.buco7854.opentv.ui.CatalogGatewayFake
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BrowseViewModelTest {
    @Test
    fun `local source preserves gateway group ordering and counts`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val gateway = CatalogGatewayFake(SourceId.LocalPlaylist(7)).apply {
                groupsResult = CatalogResult.Success(
                    listOf(
                        CatalogGroup("News", 12),
                        CatalogGroup("Sports", 4),
                    )
                )
            }

            val viewModel = BrowseViewModel(gateway.source, gateway)
            advanceUntilIdle()

            assertEquals(
                listOf(CatalogGroup("News", 12), CatalogGroup("Sports", 4)),
                viewModel.catalog.value.groups,
            )
            assertEquals(16, viewModel.liveCount.value)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `hub unavailable results are surfaced and not left loading`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val unreachable = CatalogGatewayFake(SourceId.Hub(3, 9)).apply {
                groupsResult = CatalogResult.Unreachable
            }
            val unreachableViewModel = BrowseViewModel(unreachable.source, unreachable)
            advanceUntilIdle()
            assertEquals(
                CatalogLoadError.Unreachable,
                unreachableViewModel.catalog.value.error,
            )
            assertEquals(false, unreachableViewModel.catalog.value.loading)

            val signedOut = CatalogGatewayFake(SourceId.Hub(4, 10)).apply {
                groupsResult = CatalogResult.SignedOut
            }
            val signedOutViewModel = BrowseViewModel(signedOut.source, signedOut)
            advanceUntilIdle()
            assertEquals(CatalogLoadError.SignedOut, signedOutViewModel.catalog.value.error)
            assertEquals(false, signedOutViewModel.catalog.value.loading)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `hub listing requests sequential server offsets`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val gateway = CatalogGatewayFake(SourceId.Hub(3, 9)).apply {
                groupsResult = CatalogResult.Success(listOf(CatalogGroup("All", 75)))
                channelPage = { offset, limit ->
                    val count = if (offset == 0) 50 else 25
                    CatalogResult.Success(
                        Page(
                            List(count) { index -> item(offset + index) },
                            total = 75,
                        )
                    )
                }
            }
            val viewModel = BrowseViewModel(gateway.source, gateway)
            advanceUntilIdle()

            viewModel.group.value = "All"
            advanceUntilIdle()
            viewModel.loadMore()
            advanceUntilIdle()

            assertEquals(listOf(0 to 50, 50 to 50), gateway.channelRequests)
            assertEquals(75, viewModel.catalog.value.items.size)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `route seed keeps its group when it also changes the initial tab`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val gateway = CatalogGatewayFake(SourceId.Hub(3, 9)).apply {
                groupsResult = CatalogResult.Success(listOf(CatalogGroup("Movies", 1)))
            }
            val viewModel = BrowseViewModel(gateway.source, gateway)

            viewModel.seedFromRoute(ChannelKind.MOVIE, "Movies")
            advanceUntilIdle()

            assertEquals(ChannelKind.MOVIE, viewModel.tab.value)
            assertEquals("Movies", viewModel.group.value)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `slow groups from an old tab cannot replace the current tab`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val old = CompletableDeferred<CatalogResult<List<CatalogGroup>>>()
            val gateway = CatalogGatewayFake(SourceId.Hub(3, 9)).apply {
                groupsBlock = { kind ->
                    if (kind == ChannelKind.LIVE) {
                        withContext(NonCancellable) { old.await() }
                    } else {
                        CatalogResult.Success(listOf(CatalogGroup("Movies", 2)))
                    }
                }
            }
            val viewModel = BrowseViewModel(gateway.source, gateway)
            runCurrent()

            viewModel.tab.value = ChannelKind.MOVIE
            runCurrent()
            assertEquals(listOf(CatalogGroup("Movies", 2)), viewModel.catalog.value.groups)

            old.complete(CatalogResult.Success(listOf(CatalogGroup("Stale live", 1))))
            advanceUntilIdle()

            assertEquals(listOf(CatalogGroup("Movies", 2)), viewModel.catalog.value.groups)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `slow category success cannot erase a seeded listing failure`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val groups = CompletableDeferred<CatalogResult<List<CatalogGroup>>>()
            val failure = IllegalStateException("listing failed")
            val gateway = CatalogGatewayFake(SourceId.Hub(3, 9)).apply {
                groupsBlock = {
                    withContext(NonCancellable) { groups.await() }
                }
                channelPage = { _, _ -> CatalogResult.Failed(failure) }
            }
            val viewModel = BrowseViewModel(gateway.source, gateway)
            viewModel.seedFromRoute(ChannelKind.LIVE, "News")
            runCurrent()
            assertEquals(failure, (viewModel.catalog.value.error as CatalogLoadError.Failed).cause)

            groups.complete(CatalogResult.Success(listOf(CatalogGroup("News", 1))))
            advanceUntilIdle()

            assertEquals(failure, (viewModel.catalog.value.error as CatalogLoadError.Failed).cause)
            assertEquals(false, viewModel.catalog.value.loading)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `a failed now airing refresh cannot blank a loaded hub catalog`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val groups = listOf(CatalogGroup("News", 3))
            val gateway = CatalogGatewayFake(SourceId.Hub(3, 9)).apply {
                groupsResult = CatalogResult.Success(groups)
            }
            val viewModel = BrowseViewModel(gateway.source, gateway)
            advanceUntilIdle()
            assertEquals(groups, viewModel.catalog.value.groups)

            // The browse screen refreshes this on a timer; one lost poll must
            // not replace the catalog the user is reading.
            gateway.nowAiringResult = CatalogResult.Unreachable
            viewModel.reloadNowAiring()
            advanceUntilIdle()

            assertEquals(null, viewModel.catalog.value.error)
            assertEquals(groups, viewModel.catalog.value.groups)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun item(index: Int) = CatalogItem(
        ref = ContentRef.HubContent("content-$index"),
        title = "Channel $index",
        imageUrl = null,
        kind = ChannelKind.LIVE,
        group = "All",
    )
}
