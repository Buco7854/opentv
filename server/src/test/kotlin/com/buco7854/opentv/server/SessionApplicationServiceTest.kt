package com.buco7854.opentv.server

import com.buco7854.opentv.core.model.Channel
import com.buco7854.opentv.core.model.ChannelKind
import com.buco7854.opentv.core.repo.GuideEntry
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SessionApplicationServiceTest {
    private val nowMs = 40L * DAY_MS

    @Test
    fun `catch-up range must be an exact replayable guide programme inside the archive`() = runTest {
        val edgeStart = nowMs - DAY_MS
        val atEdge = guide(edgeStart, edgeStart + HOUR_MS)

        assertEquals(
            atEdge.endMs,
            requireReplayableCatchup(
                channel = channel(catchupDays = 1),
                startMs = atEdge.startMs,
                durationMs = atEdge.endMs - atEdge.startMs,
                nowMs = nowMs,
                loadGuide = { listOf(atEdge) },
            ),
        )

        val overlap = guide(edgeStart - 1, edgeStart + HOUR_MS)
        assertCatchupUnavailable(channel(catchupDays = 1), overlap)

        val outside = guide(edgeStart - HOUR_MS, edgeStart)
        assertCatchupUnavailable(channel(catchupDays = 1), outside)

        val notAdvertisedAsReplayable = guide(edgeStart, edgeStart + HOUR_MS, replayable = false)
        assertCatchupUnavailable(channel(catchupDays = 1), notAdvertisedAsReplayable)

        val differentGuideRange = guide(edgeStart + 1, edgeStart + HOUR_MS)
        val error = assertFailsWith<ResourceNotFound> {
            requireReplayableCatchup(
                channel = channel(catchupDays = 1),
                startMs = edgeStart,
                durationMs = HOUR_MS,
                nowMs = nowMs,
                loadGuide = { listOf(differentGuideRange) },
            )
        }
        assertEquals("catchup", error.resource)
    }

    @Test
    fun `zero or absent archive and overflowing ranges are typed as unavailable`() = runTest {
        val entry = guide(nowMs - HOUR_MS, nowMs)
        for (days in listOf(0, -1)) {
            var guideLoaded = false
            val error = assertFailsWith<ResourceNotFound> {
                requireReplayableCatchup(
                    channel = channel(catchupDays = days),
                    startMs = entry.startMs,
                    durationMs = HOUR_MS,
                    nowMs = nowMs,
                    loadGuide = {
                        guideLoaded = true
                        listOf(entry)
                    },
                )
            }
            assertEquals("catchup", error.resource)
            assertEquals(false, guideLoaded)
        }

        val overflow = assertFailsWith<ResourceNotFound> {
            requireReplayableCatchup(
                channel = channel(catchupDays = 1),
                startMs = Long.MAX_VALUE - 1,
                durationMs = 2,
                nowMs = Long.MAX_VALUE,
                loadGuide = { error("overflowing range must fail before loading the guide") },
            )
        }
        assertEquals("catchup", overflow.resource)
    }

    @Test
    fun `fall-back programmes remain distinct guide instants during validation`() = runTest {
        val firstStart = Instant.parse("2024-10-27T00:30:00Z").toEpochMilliseconds()
        val secondStart = Instant.parse("2024-10-27T01:30:00Z").toEpochMilliseconds()
        val rows = listOf(
            guide(firstStart, firstStart + HOUR_MS),
            guide(secondStart, secondStart + HOUR_MS),
        )
        val now = rows.last().endMs + HOUR_MS

        rows.forEach { row ->
            assertEquals(
                row.endMs,
                requireReplayableCatchup(
                    channel = channel(catchupDays = 1),
                    startMs = row.startMs,
                    durationMs = HOUR_MS,
                    nowMs = now,
                    loadGuide = { rows },
                ),
            )
        }
    }

    private suspend fun assertCatchupUnavailable(channel: Channel, entry: GuideEntry) {
        val error = assertFailsWith<ResourceNotFound> {
            requireReplayableCatchup(
                channel = channel,
                startMs = entry.startMs,
                durationMs = entry.endMs - entry.startMs,
                nowMs = nowMs,
                loadGuide = { listOf(entry) },
            )
        }
        assertEquals("catchup", error.resource)
    }

    private fun channel(catchupDays: Int) = Channel(
        playlistId = 1,
        name = "Archive",
        url = "https://provider.example/live/1.ts",
        logo = null,
        groupTitle = "Live",
        tvgId = "archive.example",
        kind = ChannelKind.LIVE,
        seriesKey = null,
        season = null,
        episode = null,
        position = 0,
        catchupDays = catchupDays,
        catchupSource = "https://provider.example/archive/{start}/{duration}.ts",
    )

    private fun guide(startMs: Long, endMs: Long, replayable: Boolean = true) = GuideEntry(
        title = "Programme",
        description = null,
        startMs = startMs,
        endMs = endMs,
        replayable = replayable,
    )

    private companion object {
        const val HOUR_MS = 60L * 60 * 1_000
        const val DAY_MS = 24 * HOUR_MS
    }
}
