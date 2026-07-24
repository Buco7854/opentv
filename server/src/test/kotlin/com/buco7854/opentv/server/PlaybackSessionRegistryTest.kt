package com.buco7854.opentv.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlaybackSessionRegistryTest {
    private class MutableClock(var value: Long = 0) : ServerClock {
        override fun nowMs() = value
    }

    private fun heartbeat(id: String, name: String = id) =
        SessionHeartbeatDto(id = id, name = name, contentKey = "same")

    @Test
    fun acceptedJoinCreatesSharedRoomAndPromotesRemainingHost() {
        val sessions = PlaybackSessionRegistry()
        sessions.update("1.1.1.1", "ua", heartbeat("host"))
        sessions.update("2.2.2.2", "ua", heartbeat("guest"))

        assertTrue(sessions.answerJoin("host", "guest", "Host", "same", true))
        assertEquals("r-host", sessions.shareGroup("guest"))
        assertEquals(setOf("host", "guest"), sessions.roomMembers("host"))

        sessions.leaveRoom("host")
        val room = assertNotNull(sessions.roomOf("guest"))
        assertEquals(1, room.second)
        assertTrue(sessions.setRoomAudio("guest", 2))
    }

    @Test
    fun nonControllerCannotDriveRoom() {
        val sessions = PlaybackSessionRegistry()
        sessions.update("", "", heartbeat("host"))
        sessions.update("", "", heartbeat("guest"))
        sessions.answerJoin("host", "guest", "Host", "same", true)

        assertFalse(sessions.setRoomAudio("guest", 1))
        assertTrue(sessions.setRoomAudio("host", 1))
        assertEquals(1, sessions.roomAudio("guest"))
    }

    @Test
    fun staleSessionIsPrunedUsingInjectedClock() {
        val clock = MutableClock()
        val sessions = PlaybackSessionRegistry(clock, staleMs = 100)
        sessions.update("", "", heartbeat("old"))
        clock.value = 101

        assertTrue(sessions.active().isEmpty())
    }
}
