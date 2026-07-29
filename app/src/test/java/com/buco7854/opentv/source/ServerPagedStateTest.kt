package com.buco7854.opentv.source

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerPagedStateTest {
    @Test
    fun sequentialPagingAppendsAndStopsAtTotal() = runTest {
        val calls = mutableListOf<Pair<Int, Int>>()
        val pager = ServerPagedState(this, pageSize = 2) { offset, limit ->
            calls += offset to limit
            when (offset) {
                0 -> CatalogResult.Success(Page(listOf("a", "b"), 3))
                else -> CatalogResult.Success(Page(listOf("c"), 3))
            }
        }
        advanceUntilIdle()
        assertEquals(listOf("a", "b"), pager.state.value.items)

        pager.loadMore()
        advanceUntilIdle()
        assertEquals(listOf("a", "b", "c"), pager.state.value.items)
        assertEquals(3, pager.state.value.total)

        pager.loadMore()
        advanceUntilIdle()
        assertEquals(listOf(0 to 2, 2 to 2), calls)
    }

    @Test
    fun errorCanBeRetriedWithoutLosingTheExistingPage() = runTest {
        var calls = 0
        val pager = ServerPagedState(this) { _, _ ->
            calls++
            if (calls == 1) CatalogResult.Unreachable
            else CatalogResult.Success(Page(listOf(1, 2), 2))
        }
        advanceUntilIdle()
        assertEquals(CatalogLoadError.Unreachable, pager.state.value.error)
        assertFalse(pager.state.value.loading)

        pager.retry()
        advanceUntilIdle()
        assertEquals(listOf(1, 2), pager.state.value.items)
        assertNull(pager.state.value.error)
    }

    @Test
    fun refreshDiscardsAStaleInFlightResult() = runTest {
        val first = CompletableDeferred<CatalogResult<Page<String>>>()
        var calls = 0
        val pager = ServerPagedState(this) { _, _ ->
            calls++
            if (calls == 1) withContext(NonCancellable) { first.await() }
            else CatalogResult.Success(Page(listOf("fresh"), 1))
        }
        runCurrent()
        assertTrue(pager.state.value.loading)

        pager.refresh()
        runCurrent()
        assertEquals(listOf("fresh"), pager.state.value.items)

        first.complete(CatalogResult.Success(Page(listOf("stale"), 1)))
        advanceUntilIdle()
        assertEquals(listOf("fresh"), pager.state.value.items)
    }

    @Test
    fun activeRequestCancellationIsRenderedAsAFailureInsteadOfStayingLoading() = runTest {
        val timeout = CancellationException("request timeout")
        val pager = ServerPagedState<Int>(this) { _, _ -> throw timeout }

        advanceUntilIdle()

        assertFalse(pager.state.value.loading)
        assertSame(timeout, (pager.state.value.error as CatalogLoadError.Failed).cause)
    }

    @Test
    fun emptyPageStopsPagingWhenTheReportedTotalIsTooLarge() = runTest {
        var calls = 0
        val pager = ServerPagedState(this, pageSize = 2) { _, _ ->
            calls++
            CatalogResult.Success(Page(emptyList<Int>(), total = 100))
        }
        advanceUntilIdle()

        repeat(3) {
            pager.loadMore()
            advanceUntilIdle()
        }

        assertEquals(1, calls)
        assertFalse(pager.state.value.loading)
    }

    @Test
    fun shortIntermediatePageDoesNotDiscardTheRemainderAdvertisedByTotal() = runTest {
        val offsets = mutableListOf<Int>()
        val pager = ServerPagedState(this, pageSize = 3) { offset, _ ->
            offsets += offset
            CatalogResult.Success(
                when (offset) {
                    0 -> Page(listOf("a"), total = 3)
                    1 -> Page(listOf("b", "c"), total = 3)
                    else -> Page(emptyList(), total = 3)
                },
            )
        }
        advanceUntilIdle()

        pager.loadMore()
        advanceUntilIdle()

        assertEquals(listOf(0, 1), offsets)
        assertEquals(listOf("a", "b", "c"), pager.state.value.items)
    }

    @Test
    fun duplicateAtAChangingPageBoundaryIsNotRenderedTwice() = runTest {
        val offsets = mutableListOf<Int>()
        val pager = ServerPagedState(
            scope = this,
            pageSize = 2,
            keyOf = { it },
        ) { offset, _ ->
            offsets += offset
            CatalogResult.Success(
                when (offset) {
                    0 -> Page(listOf("a", "b"), total = 5)
                    2 -> Page(listOf("b", "c"), total = 5)
                    else -> Page(listOf("d"), total = 5)
                },
            )
        }
        advanceUntilIdle()

        pager.loadMore()
        advanceUntilIdle()
        pager.loadMore()
        advanceUntilIdle()

        assertEquals(listOf(0, 2, 4), offsets)
        assertEquals(listOf("a", "b", "c", "d"), pager.state.value.items)
    }

    @Test
    fun shrinkingCatalogRestartsFromZeroInsteadOfSkippingABoundaryItem() = runTest {
        val offsets = mutableListOf<Int>()
        var firstGeneration = true
        val pager = ServerPagedState(
            scope = this,
            pageSize = 2,
            keyOf = { it },
        ) { offset, _ ->
            offsets += offset
            when {
                firstGeneration && offset == 0 ->
                    CatalogResult.Success(Page(listOf("a", "b"), 4))
                firstGeneration -> {
                    firstGeneration = false
                    CatalogResult.Success(Page(listOf("d"), 3))
                }
                offset == 0 -> CatalogResult.Success(Page(listOf("b", "c"), 3))
                else -> CatalogResult.Success(Page(listOf("d"), 3))
            }
        }
        advanceUntilIdle()

        pager.loadMore()
        advanceUntilIdle()
        assertEquals(listOf("b", "c"), pager.state.value.items)
        pager.loadMore()
        advanceUntilIdle()

        assertEquals(listOf(0, 2, 0, 2), offsets)
        assertEquals(listOf("b", "c", "d"), pager.state.value.items)
    }
}
