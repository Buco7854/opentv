package com.buco7854.opentv.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class PlaybackSessionRegistryTest {
    private class MutableClock(var value: Long = 0) : ServerClock {
        override fun nowMs() = value
    }

    private fun actor(id: String) = Actor(id, "auth-$id", id, id, setOf("USER"), "PASSWORD", "BROWSER")

    private fun create(sessions: PlaybackSessionRegistry, id: String) =
        sessions.create(actor(id), 1, "same", "https://example.test/stream", "", "").id

    private fun join(sessions: PlaybackSessionRegistry, host: String, guest: String): Boolean {
        val requestId = sessions.requestJoin(host, guest, "Guest", "same") ?: return false
        return sessions.answerJoin(host, requestId, true)
    }

    @Test
    fun acceptedJoinCreatesSharedRoomAndPromotesRemainingHost() {
        val sessions = PlaybackSessionRegistry()
        val host = create(sessions, "host")
        val guest = create(sessions, "guest")

        assertTrue(join(sessions, host, guest))
        assertEquals("r-$host", sessions.shareGroup(guest))
        assertEquals(setOf(host, guest), sessions.roomMembers(host))

        sessions.leaveRoom(host)
        val room = assertNotNull(sessions.roomOf(guest))
        assertEquals(1, room.second)
        assertTrue(sessions.setRoomAudio(guest, 2))
    }

    @Test
    fun nonControllerCannotDriveRoom() {
        val sessions = PlaybackSessionRegistry()
        val host = create(sessions, "host")
        val guest = create(sessions, "guest")
        join(sessions, host, guest)

        assertFalse(sessions.setRoomAudio(guest, 1))
        assertTrue(sessions.setRoomAudio(host, 1))
        assertEquals(1, sessions.roomAudio(guest))
    }

    @Test
    fun staleSessionIsPrunedUsingInjectedClock() {
        val clock = MutableClock()
        val sessions = PlaybackSessionRegistry(clock, staleMs = 100)
        create(sessions, "old")
        clock.value = 101

        assertTrue(sessions.active().isEmpty())
    }

    @Test
    fun mediaGrantIsBoundToOwnerSessionAndRevokedWithLease() {
        val sessions = PlaybackSessionRegistry()
        val owner = actor("owner")
        val lease = sessions.create(
            owner, 1, "content", "https://example.test/stream", "", "",
        )
        val grants = PlaybackMediaGrants(sessions)
        val grant = grants.issue(owner, lease.id)

        grants.validateSource(owner, lease.id, grant.token, "https://example.test/stream")
        assertFailsWith<PlaybackRevokedException> {
            grants.validate(actor("attacker"), lease.id, grant.token)
        }
        assertFailsWith<PlaybackRevokedException> {
            sessions.owned(owner, "never-issued")
        }
        sessions.remove(lease.id)
        assertFailsWith<PlaybackRevokedException> {
            grants.validate(owner, lease.id, grant.token)
        }
        sessions.close()
    }

    @Test
    fun roomKickTombstonesOnlyTargetLease() {
        val sessions = PlaybackSessionRegistry()
        val hostActor = actor("host")
        val guestActor = actor("guest")
        val host = sessions.create(
            hostActor, 1, "same", "https://example.test/stream", "", "",
        )
        val guest = sessions.create(
            guestActor, 1, "same", "https://example.test/stream", "", "",
        )
        assertTrue(join(sessions, host.id, guest.id))

        assertTrue(sessions.kick(host.id, guest.id))

        assertFailsWith<PlaybackRevokedException> { sessions.owned(guestActor, guest.id) }
        assertEquals(host.id, sessions.owned(hostActor, host.id).id)
        sessions.close()
    }

    @Test
    fun sharedMediaResourceStopsOnlyAfterFinalViewerReleasesIt() {
        val sessions = PlaybackSessionRegistry()
        val firstActor = actor("first")
        val secondActor = actor("second")
        val first = sessions.create(
            firstActor, 1, "same", "https://example.test/stream", "", "",
        )
        val second = sessions.create(
            secondActor, 1, "same", "https://example.test/stream", "", "",
        )
        val grants = PlaybackMediaGrants(sessions)
        val firstGrant = grants.issue(firstActor, first.id)
        val secondGrant = grants.issue(secondActor, second.id)
        grants.bindResource(firstActor, first.id, "shared-remux")
        grants.bindResource(secondActor, second.id, "shared-remux")

        assertFalse(
            grants.releaseResource(firstActor, first.id, firstGrant.token, "shared-remux")
        )
        assertTrue(grants.hasAttachments("shared-remux"))
        assertTrue(
            grants.releaseResource(secondActor, second.id, secondGrant.token, "shared-remux")
        )
        assertFalse(grants.hasAttachments("shared-remux"))
        sessions.close()
    }

    @Test
    fun joinAnswerRequiresPendingRequestAndMovesPeerAtomicallyBetweenRooms() {
        val sessions = PlaybackSessionRegistry()
        val firstHost = create(sessions, "first-host")
        val secondHost = create(sessions, "second-host")
        val guest = create(sessions, "guest")

        assertFalse(sessions.answerJoin(firstHost, "invented", true))
        assertTrue(join(sessions, firstHost, guest))
        assertTrue(join(sessions, secondHost, guest))
        assertEquals("r-$secondHost", sessions.shareGroup(guest))
        assertEquals(setOf(firstHost), sessions.roomMembers(firstHost))
        assertEquals(setOf(secondHost, guest), sessions.roomMembers(secondHost))
        sessions.close()
    }

    @Test
    fun joinRequestExpiresAndCanOnlyBeConsumedOnce() {
        val clock = MutableClock()
        val sessions = PlaybackSessionRegistry(clock)
        val host = create(sessions, "host")
        val guest = create(sessions, "guest")
        val expired = assertNotNull(sessions.requestJoin(host, guest, "Guest", "same"))
        clock.value = 60_001
        assertFalse(sessions.answerJoin(host, expired, true))

        val current = assertNotNull(sessions.requestJoin(host, guest, "Guest", "same"))
        assertTrue(sessions.answerJoin(host, current, true))
        assertFalse(sessions.answerJoin(host, current, true))
        sessions.close()
    }
}
