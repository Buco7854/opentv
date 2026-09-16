package com.buco7854.opentv.server

import com.buco7854.opentv.contract.GroupCountDto
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class GroupsCacheTest {
    @Test
    fun `a cached key is served without reloading`() = runTest {
        var calls = 0
        val cache = GroupsCache(scope = backgroundScope) { _, _ ->
            calls++
            listOf(GroupCountDto("Live", 1))
        }

        cache.get(1, 0)
        cache.get(1, 0)
        cache.get(1, 0)

        assertEquals(1, calls)
    }

    @Test
    fun `distinct playlists and kinds are cached independently`() = runTest {
        val calls = mutableListOf<Pair<Long, Int>>()
        val cache = GroupsCache(scope = backgroundScope) { playlistId, kind ->
            calls += playlistId to kind
            listOf(GroupCountDto("Group $playlistId/$kind", 1))
        }

        val a = cache.get(1, 0)
        val b = cache.get(1, 1)
        val c = cache.get(2, 0)

        assertEquals(listOf(1L to 0, 1L to 1, 2L to 0), calls)
        assertEquals("Group 1/0", a.single().groupTitle)
        assertEquals("Group 1/1", b.single().groupTitle)
        assertEquals("Group 2/0", c.single().groupTitle)
    }

    @Test
    fun `the periodic refresh keeps a requested key up to date without a caller asking again`() =
        runTest {
            var count = 0
            val cache = GroupsCache(scope = backgroundScope, refreshIntervalMs = 1_000) { _, _ ->
                count++
                listOf(GroupCountDto("Live", count))
            }

            val first = cache.get(1, 0)
            assertEquals(1, first.single().count)

            advanceTimeBy(1_001)
            runCurrent()

            val second = cache.get(1, 0)
            assertEquals(2, second.single().count)
        }

    @Test
    fun `a failed background refresh keeps serving the last known-good value`() = runTest {
        var succeed = true
        val cache = GroupsCache(scope = backgroundScope, refreshIntervalMs = 1_000) { _, _ ->
            if (!succeed) throw IllegalStateException("upstream unavailable")
            listOf(GroupCountDto("Live", 1))
        }

        cache.get(1, 0)
        succeed = false
        advanceTimeBy(1_001)
        runCurrent()

        assertEquals(listOf(GroupCountDto("Live", 1)), cache.get(1, 0))
    }

    @Test
    fun `invalidating a playlist forces its next request to reload`() = runTest {
        var calls = 0
        val cache = GroupsCache(scope = backgroundScope) { _, _ ->
            calls++
            listOf(GroupCountDto("Live", calls))
        }

        cache.get(1, 0)
        cache.invalidate(1)
        cache.get(1, 0)

        assertEquals(2, calls)
    }

    @Test
    fun `invalidating one playlist leaves another playlist's cache untouched`() = runTest {
        var calls = 0
        val cache = GroupsCache(scope = backgroundScope) { _, _ ->
            calls++
            listOf(GroupCountDto("Live", calls))
        }

        cache.get(1, 0)
        cache.get(2, 0)
        cache.invalidate(1)
        cache.get(1, 0)
        cache.get(2, 0)

        assertEquals(3, calls)
    }
}
