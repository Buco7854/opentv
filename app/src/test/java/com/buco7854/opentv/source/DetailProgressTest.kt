package com.buco7854.opentv.source

import com.buco7854.opentv.ui.CatalogGatewayFake
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DetailProgressTest {
    @Test
    fun `first local detail resume reads Room because channel detail has no embedded progress`() = runTest {
        val source = SourceId.LocalPlaylist(4)
        val ref = ContentRef.LocalUrl("https://media.example/movie.ts", channelId = 91)
        val gateway = CatalogGatewayFake(source).apply {
            resumeResult = CatalogResult.Success(listOf(point(ref, 30_000, 120_000)))
        }
        val tracker = DetailProgressTracker(
            source,
            gateway,
            backgroundScope,
            CatalogProgressUpdates(),
        )

        tracker.onResumed()
        runCurrent()

        assertEquals(1, gateway.resumeRequests)
        assertEquals(.25f, tracker.state.value.progressFor(item(ref, progress = null)))
    }

    @Test
    fun `returning to retained movie detail refreshes progress from its hub`() = runTest {
        val source = SourceId.Hub(hubId = 7, playlistId = 11)
        val ref = ContentRef.HubContent("movie-42")
        val gateway = CatalogGatewayFake(source).apply {
            resumeResult = CatalogResult.Success(listOf(point(ref, 60_000, 100_000)))
        }
        val tracker = DetailProgressTracker(
            source,
            gateway,
            backgroundScope,
            CatalogProgressUpdates(),
        )
        val item = item(ref, progress = null)

        runCurrent()
        tracker.onResumed()
        runCurrent()
        assertEquals(1, gateway.resumeRequests)
        assertEquals(.6f, tracker.state.value.progressFor(item))

        gateway.resumeResult = CatalogResult.Success(listOf(point(ref, 80_000, 100_000)))
        tracker.onResumed() // Same ViewModel becomes visible after the player is popped.
        runCurrent()

        assertEquals(2, gateway.resumeRequests)
        assertEquals(.8f, tracker.state.value.progressFor(item))
    }

    @Test
    fun `successful refresh can clear stale movie progress`() = runTest {
        val source = SourceId.Hub(hubId = 7, playlistId = 11)
        val ref = ContentRef.HubContent("finished-movie")
        val gateway = CatalogGatewayFake(source)
        val tracker = DetailProgressTracker(
            source,
            gateway,
            backgroundScope,
            CatalogProgressUpdates(),
        )

        tracker.onResumed()
        runCurrent()

        assertNull(tracker.state.value.progressFor(item(ref, progress = .85f)))
    }

    @Test
    fun `local episode progress follows its stable url across catalog row ids`() = runTest {
        val source = SourceId.LocalPlaylist(4)
        val resumeRef = ContentRef.LocalUrl("https://media.example/e1.ts", channelId = 0)
        val detailRef = ContentRef.LocalUrl("https://media.example/e1.ts", channelId = 91)
        val gateway = CatalogGatewayFake(source).apply {
            resumeResult = CatalogResult.Success(listOf(point(resumeRef, 30_000, 120_000)))
        }
        val tracker = DetailProgressTracker(
            source,
            gateway,
            backgroundScope,
            CatalogProgressUpdates(),
        )

        tracker.onResumed()
        runCurrent()

        assertEquals(.25f, tracker.state.value.progressFor(item(detailRef, progress = null)))
    }

    @Test
    fun `completed player write wins when resume refresh started before the save`() = runTest {
        val source = SourceId.Hub(hubId = 7, playlistId = 11)
        val ref = ContentRef.HubContent("episode-9")
        val refreshStarted = CompletableDeferred<Unit>()
        val finishRefresh = CompletableDeferred<CatalogResult<List<CatalogResumePoint>>>()
        val gateway = CatalogGatewayFake(source).apply {
            resumeBlock = {
                refreshStarted.complete(Unit)
                finishRefresh.await()
            }
        }
        val updates = CatalogProgressUpdates()
        val tracker = DetailProgressTracker(source, gateway, backgroundScope, updates)

        runCurrent()
        tracker.onResumed()
        refreshStarted.await()

        // The detail refresh saw the old server value, but the player's final write
        // completed while that response was in flight.
        updates.publish(source, ref, .7f)
        runCurrent()
        finishRefresh.complete(CatalogResult.Success(listOf(point(ref, 20_000, 100_000))))
        runCurrent()

        assertEquals(.7f, tracker.state.value.progressFor(item(ref, progress = .1f)))

        // The override is only a race guard. A later source-authoritative refresh must
        // supersede it, including a change made from another device.
        gateway.resumeBlock = {
            CatalogResult.Success(listOf(point(ref, 80_000, 100_000)))
        }
        tracker.refresh()
        runCurrent()
        assertEquals(.8f, tracker.state.value.progressFor(item(ref, progress = .1f)))
    }

    @Test
    fun `player update cannot leak between sources with the same content id`() = runTest {
        val source = SourceId.Hub(hubId = 7, playlistId = 11)
        val otherPlaylist = SourceId.Hub(hubId = 7, playlistId = 12)
        val ref = ContentRef.HubContent("episode-9")
        val updates = CatalogProgressUpdates()
        val tracker = DetailProgressTracker(
            source,
            CatalogGatewayFake(source),
            backgroundScope,
            updates,
        )

        runCurrent()
        updates.publish(otherPlaylist, ref, .7f)
        runCurrent()

        assertEquals(.1f, tracker.state.value.progressFor(item(ref, progress = .1f)))
    }

    private fun item(ref: ContentRef, progress: Float?) = CatalogItem(
        ref = ref,
        title = "Title",
        imageUrl = null,
        kind = 1,
        group = null,
        progress = progress,
    )

    private fun point(
        ref: ContentRef,
        positionMs: Long,
        durationMs: Long,
    ) = CatalogResumePoint(ref, positionMs, durationMs, updatedMs = 1)
}
