package com.buco7854.opentv.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderConnectionsTest {
    private class MutableClock(var value: Long = 0) : ServerClock {
        override fun nowMs() = value
    }

    @Test
    fun sharedContentUsesOneProviderSeat() {
        val connections = ProviderConnections()
        assertTrue(connections.tryOpenStream("one", "provider", "movie", 1) {})
        assertTrue(connections.tryOpenStream("two", "provider", "movie", 1) {})
        assertEquals(1, connections.distinctStreams("provider", null))
        assertFalse(connections.tryOpenStream("three", "provider", "other", 1) {})
    }

    @Test
    fun streamEvictsLeastRecentlyUsedDownloadButNotViewer() {
        val clock = MutableClock()
        val connections = ProviderConnections(clock)
        val evicted = mutableListOf<String>()
        assertTrue(connections.tryOpenDownload("old", "provider", "download-1", 2) { evicted += "old" })
        clock.value = 10
        assertTrue(connections.tryOpenDownload("new", "provider", "download-2", 2) { evicted += "new" })

        assertTrue(connections.tryOpenStream("viewer", "provider", "live", 2) {})
        assertEquals(listOf("old"), evicted)
        assertTrue(connections.isOpen("viewer"))
    }
}
