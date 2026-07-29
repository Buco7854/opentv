package com.buco7854.opentv.ui.components

import com.buco7854.opentv.core.model.ChannelKind
import com.buco7854.opentv.source.CatalogGuideEntry
import com.buco7854.opentv.source.CatalogItem
import com.buco7854.opentv.source.CatalogResult
import com.buco7854.opentv.source.ContentRef
import com.buco7854.opentv.source.SourceId
import com.buco7854.opentv.ui.CatalogGatewayFake
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test

class GuideViewModelTest {
    @Test
    fun staleRetryForTheSameItemCannotReplaceTheNewerGuide() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val first = CompletableDeferred<CatalogResult<List<CatalogGuideEntry>>>()
            var calls = 0
            val gateway = CatalogGatewayFake(SourceId.Hub(1, 2)).apply {
                guideBlock = {
                    calls++
                    if (calls == 1) withContext(NonCancellable) { first.await() }
                    else CatalogResult.Success(listOf(entry("fresh")))
                }
            }
            val item = CatalogItem(
                ref = ContentRef.HubContent("channel"),
                title = "Channel",
                imageUrl = null,
                kind = ChannelKind.LIVE,
                group = "Live",
            )
            val viewModel = GuideViewModel(gateway.source, gateway, null)
            viewModel.show(item)
            runCurrent()

            viewModel.retry()
            runCurrent()
            assertEquals(listOf("fresh"), viewModel.state.value.entries?.map { it.title })

            first.complete(CatalogResult.Success(listOf(entry("stale"))))
            advanceUntilIdle()

            assertEquals(listOf("fresh"), viewModel.state.value.entries?.map { it.title })
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun entry(title: String) =
        CatalogGuideEntry(title, null, startMs = 1, endMs = 2, replayable = false)
}
