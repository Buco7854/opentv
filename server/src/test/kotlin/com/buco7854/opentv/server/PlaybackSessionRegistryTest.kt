package com.buco7854.opentv.server

import com.buco7854.opentv.contract.SessionCommandDto
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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

    private fun create(
        sessions: PlaybackSessionRegistry,
        id: String,
        capabilities: MediaCapabilities = MediaCapabilities.BROWSER,
    ) = sessions.create(
        actor(id), 1, "same", "https://example.test/stream", "", "", capabilities,
    ).id

    private fun join(sessions: PlaybackSessionRegistry, host: String, guest: String): Boolean {
        val requestId = sessions.requestJoin(host, guest, "Guest", "same") ?: return false
        return sessions.answerJoin(host, requestId, true)
    }

    @Test
    fun acceptedJoinCreatesSharedRoomAndPromotesRemainingHost() {
        val sessions = PlaybackSessionRegistry(reapInBackground = false)
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
        val sessions = PlaybackSessionRegistry(reapInBackground = false)
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
        val sessions = PlaybackSessionRegistry(clock, staleMs = 100, reapInBackground = false)
        create(sessions, "old")
        clock.value = 101

        assertTrue(sessions.active().isEmpty())
    }

    @Test
    fun mediaActivityKeepsALeaseAliveWhenItsAuthenticatedHeartbeatIsDelayed() {
        val clock = MutableClock()
        val sessions = PlaybackSessionRegistry(clock, staleMs = 100, reapInBackground = false)
        val lease = create(sessions, "viewer")

        clock.value = 90
        sessions.touch(lease)
        clock.value = 150

        assertEquals(listOf(lease), sessions.active().map { it.id })
        sessions.close()
    }

    @Test
    fun invalidMediaGrantCannotKeepALeaseAlive() {
        val clock = MutableClock()
        val sessions = PlaybackSessionRegistry(clock, staleMs = 100, reapInBackground = false)
        val owner = actor("viewer")
        val lease = sessions.create(
            owner, 1, "same", "https://example.test/stream", "", "",
        )
        val grants = PlaybackMediaGrants(sessions, clock = clock::nowMs)

        clock.value = 90
        assertFailsWith<PlaybackRevokedException> {
            grants.validate(lease.id, "not-a-grant")
        }
        clock.value = 101

        assertTrue(sessions.active().isEmpty())
        sessions.close()
    }

    @Test
    fun staleReaperSnapshotCannotRemoveALeaseRevivedByAHeartbeat() {
        val clock = MutableClock()
        lateinit var sessions: PlaybackSessionRegistry
        lateinit var first: PlaybackSessionRegistry.Live
        lateinit var second: PlaybackSessionRegistry.Live
        var revivedId: String? = null
        val cleanup = object : PlaybackLeaseCleanup {
            override fun memberLeaving(leaseId: String) = Unit
            override fun shareGroupUnused(group: String) = Unit

            override fun leaseTerminated(leaseId: String, unusedShareGroup: String?) {
                if (revivedId != null) return
                val revived = if (leaseId == first.id) second else first
                val revivedActor = if (revived.id == first.id) actor("first") else actor("second")
                revivedId = revived.id
                sessions.update(
                    revivedActor,
                    "",
                    "",
                    revived.state.copy(id = revived.id),
                )
            }
        }
        sessions = PlaybackSessionRegistry(
            clock,
            staleMs = 100,
            cleanup = cleanup,
            reapInBackground = false,
        )
        first = sessions.create(
            actor("first"), 1, "same", "https://example.test/stream", "", "",
        )
        second = sessions.create(
            actor("second"), 1, "same", "https://example.test/stream", "", "",
        )
        clock.value = 101

        val active = sessions.active()
        assertEquals(revivedId, active.single().id)
        sessions.close()
    }

    @Test
    fun mediaGrantIsBoundToOwnerSessionAndRevokedWithLease() {
        val sessions = PlaybackSessionRegistry(reapInBackground = false)
        val owner = actor("owner")
        val lease = sessions.create(
            owner, 1, "content", "https://example.test/stream", "", "",
        )
        val grants = PlaybackMediaGrants(sessions)
        val grant = grants.issue(owner, lease.id)

        grants.validateSource(lease.id, grant.token, "https://example.test/stream")
        assertFailsWith<PlaybackRevokedException> {
            sessions.owned(owner, "never-issued")
        }
        sessions.remove(lease.id)
        assertFailsWith<PlaybackRevokedException> {
            grants.validate(lease.id, grant.token)
        }
        sessions.close()
    }

    @Test
    fun refreshingMediaGrantDoesNotInvalidateAnInFlightPreviousGrant() {
        val sessions = PlaybackSessionRegistry(reapInBackground = false)
        val owner = actor("owner")
        val lease = sessions.create(
            owner, 1, "content", "https://example.test/stream", "", "",
        )
        val grants = PlaybackMediaGrants(sessions)
        val previous = grants.issue(owner, lease.id)
        val replacement = grants.issue(owner, lease.id)

        assertEquals(lease.id, grants.validate(lease.id, previous.token).id)
        assertEquals(lease.id, grants.validate(lease.id, replacement.token).id)
        sessions.close()
    }

    @Test
    fun revokingAnAuthSessionTerminatesItsPlaybackLeaseAndGrant() {
        val sessions = PlaybackSessionRegistry(reapInBackground = false)
        val owner = actor("owner")
        val lease = sessions.create(
            owner, 1, "content", "https://example.test/stream", "", "",
        )
        val grants = PlaybackMediaGrants(sessions)
        val grant = grants.issue(owner, lease.id)

        sessions.terminateSession(owner.authSessionId)

        assertFailsWith<PlaybackRevokedException> {
            grants.validate(lease.id, grant.token)
        }
        sessions.close()
    }

    @Test
    fun roomKickDeliversNoticeThenTombstonesOnlyTargetLease() = runBlocking {
        val sessions = PlaybackSessionRegistry(
            kickNoticeGraceMs = 25,
            reapInBackground = false,
        )
        val hostActor = actor("host")
        val guestActor = actor("guest")
        val host = sessions.create(
            hostActor, 1, "same", "https://example.test/stream", "", "",
        )
        val guest = sessions.create(
            guestActor, 1, "same", "https://example.test/stream", "", "",
        )
        assertTrue(join(sessions, host.id, guest.id))
        sessions.drainCommands(host.id)
        sessions.drainCommands(guest.id)

        assertTrue(sessions.kick(host.id, guest.id))

        assertEquals("room-ended", sessions.drainCommands(guest.id).single().type)
        assertTrue(guest.id !in sessions.roomMembers(host.id))
        assertEquals(guest.id, sessions.owned(guestActor, guest.id).id)
        delay(100)
        assertFailsWith<PlaybackRevokedException> { sessions.owned(guestActor, guest.id) }
        assertEquals(host.id, sessions.owned(hostActor, host.id).id)
        sessions.close()
    }

    @Test
    fun closingDuringKickGraceRevokesImmediatelyAndCancelsTheTimer() = runBlocking {
        val terminated = mutableListOf<String>()
        val cleanup = object : PlaybackLeaseCleanup {
            override fun memberLeaving(leaseId: String) = Unit
            override fun shareGroupUnused(group: String) = Unit

            override fun leaseTerminated(leaseId: String, unusedShareGroup: String?) {
                terminated += leaseId
            }
        }
        val sessions = PlaybackSessionRegistry(
            cleanup = cleanup,
            kickNoticeGraceMs = 250,
            reapInBackground = false,
        )
        val host = create(sessions, "host")
        val guest = create(sessions, "guest")
        assertTrue(join(sessions, host, guest))

        assertTrue(sessions.kick(host, guest))
        assertFalse(sessions.kick(host, guest))
        sessions.close()
        delay(300)

        assertEquals(listOf(host, guest).sorted(), terminated.sorted())
        assertEquals(terminated.distinct().size, terminated.size)
    }

    @Test
    fun sharedReadCleanupFollowsHostHandoffKickLastLeaveAndLeaseRevocation() {
        val leaving = mutableListOf<String>()
        val unusedGroups = mutableListOf<String>()
        val terminated = mutableListOf<String>()
        val cleanup = object : PlaybackLeaseCleanup {
            override fun memberLeaving(leaseId: String) {
                leaving += leaseId
            }

            override fun shareGroupUnused(group: String) {
                unusedGroups += group
            }

            override fun leaseTerminated(leaseId: String, unusedShareGroup: String?) {
                terminated += leaseId
            }
        }
        val sessions = PlaybackSessionRegistry(cleanup = cleanup, reapInBackground = false)
        try {
            val originalHost = create(sessions, "host")
            val first = create(sessions, "first")
            val second = create(sessions, "second")
            assertTrue(join(sessions, originalHost, first))
            assertTrue(join(sessions, originalHost, second))
            val firstGroup = sessions.shareGroup(originalHost)
            sessions.drainCommands(first)
            sessions.drainCommands(second)

            sessions.leaveRoom(originalHost)

            assertEquals(listOf(originalHost), leaving)
            assertTrue(unusedGroups.isEmpty(), "host handoff tore down the remaining room")
            assertEquals(firstGroup, sessions.shareGroup(first))
            val roster = sessions.drainCommands(first)
                .last { it.type == "room-state" }
                .members.orEmpty()
            val newHost = roster.single { it.host }.id
            val kicked = roster.single { !it.host }.id

            assertTrue(sessions.kick(newHost, kicked))
            assertEquals(listOf(originalHost, kicked), leaving)
            assertTrue(unusedGroups.isEmpty(), "kick tore down the remaining host's read")

            sessions.leaveRoom(newHost)
            assertEquals(listOf(firstGroup), unusedGroups)
            assertEquals(newHost, sessions.shareGroup(newHost))

            val revocationHost = create(sessions, "revocation-host")
            val revoked = create(sessions, "revoked")
            assertTrue(join(sessions, revocationHost, revoked))
            val revocationGroup = sessions.shareGroup(revocationHost)

            sessions.remove(revoked)
            assertTrue(revoked in leaving)
            assertTrue(revoked in terminated)
            assertTrue(revocationGroup !in unusedGroups)
            assertEquals(revocationGroup, sessions.shareGroup(revocationHost))

            // Ending the final lease (the channel-switch/lease-revocation path) releases the
            // room group; a replacement lease starts with an independent solo share id.
            sessions.remove(revocationHost)
            assertTrue(revocationHost in leaving)
            assertTrue(revocationHost in terminated)
            assertTrue(revocationGroup in unusedGroups)
            val replacement = create(sessions, "replacement-channel")
            assertEquals(replacement, sessions.shareGroup(replacement))
        } finally {
            sessions.close()
        }
    }

    @Test
    fun sharedMediaResourceStopsOnlyAfterFinalViewerReleasesIt() {
        val sessions = PlaybackSessionRegistry(reapInBackground = false)
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
        grants.bindResource(first.id, "shared-remux")
        grants.bindResource(second.id, "shared-remux")

        assertFalse(
            grants.releaseResource(first.id, firstGrant.token, "shared-remux")
        )
        assertTrue(grants.hasAttachments("shared-remux"))
        assertTrue(
            grants.releaseResource(second.id, secondGrant.token, "shared-remux")
        )
        assertFalse(grants.hasAttachments("shared-remux"))
        sessions.close()
    }

    @Test
    fun resourceAttachmentCannotPublishAfterLeaseRevocationCleanup() {
        lateinit var grants: PlaybackMediaGrants
        val cleanup = object : PlaybackLeaseCleanup {
            override fun memberLeaving(leaseId: String) = Unit
            override fun shareGroupUnused(group: String) = Unit
            override fun leaseTerminated(leaseId: String, unusedShareGroup: String?) {
                grants.revokeLease(leaseId)
            }
        }
        val sessions = PlaybackSessionRegistry(cleanup = cleanup, reapInBackground = false)
        val owner = actor("owner")
        val lease = sessions.create(
            owner, 1, "same", "https://example.test/stream", "", "",
        )
        grants = PlaybackMediaGrants(sessions)
        val resourceLock = PlaybackMediaGrants::class.java
            .getDeclaredField("resourceLock")
            .also { it.isAccessible = true }
            .get(grants)
        val executor = Executors.newFixedThreadPool(2)
        val bindingThread = java.util.concurrent.atomic.AtomicReference<Thread>()
        val removalThread = java.util.concurrent.atomic.AtomicReference<Thread>()

        try {
            val (binding, removal) = synchronized(resourceLock) {
                val binding = executor.submit {
                    bindingThread.set(Thread.currentThread())
                    sessions.withLiveLease(lease.id) {
                        grants.bindResource(lease.id, "late-remux")
                    }
                }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
                while (bindingThread.get()?.state != Thread.State.BLOCKED &&
                    System.nanoTime() < deadline
                ) {
                    Thread.yield()
                }
                assertEquals(Thread.State.BLOCKED, bindingThread.get()?.state)

                val removal = executor.submit {
                    removalThread.set(Thread.currentThread())
                    sessions.remove(lease.id)
                }
                val removalDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
                while (removalThread.get()?.state != Thread.State.BLOCKED &&
                    System.nanoTime() < removalDeadline
                ) {
                    Thread.yield()
                }
                assertEquals(Thread.State.BLOCKED, removalThread.get()?.state)
                binding to removal
            }
            binding.get(1, TimeUnit.SECONDS)
            removal.get(1, TimeUnit.SECONDS)

            assertFalse(grants.hasAttachments("late-remux"))
        } finally {
            executor.shutdownNow()
            sessions.close()
        }
    }

    @Test
    fun joinAnswerRequiresPendingRequestAndMovesPeerAtomicallyBetweenRooms() {
        val sessions = PlaybackSessionRegistry(reapInBackground = false)
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
        val sessions = PlaybackSessionRegistry(clock, reapInBackground = false)
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

    @Test
    fun roomCapabilitiesIntersectOnJoinAndExpandOnLeave() {
        val sessions = PlaybackSessionRegistry(reapInBackground = false)
        val native = MediaCapabilities(
            video = setOf("h264", "hevc", "av1"),
            audio = MediaCapabilities.BROWSER.audio + setOf("ac3", "eac3"),
            selectsTracksInBand = true,
        )
        val host = create(sessions, "host", native)
        val guest = create(sessions, "guest")

        assertEquals(native, sessions.roomCapabilities(host))
        assertTrue(join(sessions, host, guest))
        assertEquals(MediaCapabilities.BROWSER, sessions.roomCapabilities(host))
        assertEquals(MediaCapabilities.BROWSER, sessions.roomCapabilities(guest))
        assertFalse(sessions.roomCapabilities(host).selectsTracksInBand)
        assertTrue(sessions.drainCommands(host).any { it.type == "room-audio" })
        sessions.drainCommands(guest)

        sessions.leaveRoom(guest)

        assertEquals(native, sessions.roomCapabilities(host))
        assertTrue(sessions.drainCommands(host).any { it.type == "room-audio" })
        sessions.close()
    }

    @Test
    fun roomOfTwoInBandClientsRetainsInBandSelectionAndStillChangesShareGroup() {
        val sessions = PlaybackSessionRegistry(reapInBackground = false)
        val native = MediaCapabilities(
            video = setOf("h264", "hevc"),
            audio = setOf("aac", "eac3"),
            selectsTracksInBand = true,
        )
        val host = create(sessions, "host", native)
        val guest = create(sessions, "guest", native)

        assertTrue(join(sessions, host, guest))

        assertEquals(native, sessions.roomCapabilities(host))
        assertEquals(native, sessions.roomCapabilities(guest))
        assertTrue(sessions.roomCapabilities(host).selectsTracksInBand)
        // The format is unchanged, but membership changes the remux share group:
        // both clients must reopen onto the room's one provider read.
        assertTrue(sessions.drainCommands(host).any { it.type == "room-audio" })
        assertTrue(sessions.drainCommands(guest).any { it.type == "room-audio" })
        sessions.close()
    }

    @Test
    fun hostHandoffRecomputesIntersectionAndSignalsFormatReload() {
        val sessions = PlaybackSessionRegistry(reapInBackground = false)
        val native = MediaCapabilities(
            video = setOf("h264", "hevc"),
            audio = MediaCapabilities.BROWSER.audio + "eac3",
            selectsTracksInBand = true,
        )
        val host = create(sessions, "host")
        val guest = create(sessions, "guest", native)
        assertTrue(join(sessions, host, guest))
        sessions.drainCommands(host)
        sessions.drainCommands(guest)

        sessions.leaveRoom(host)

        assertEquals(native, sessions.roomCapabilities(guest))
        assertTrue(sessions.drainCommands(guest).any { it.type == "room-audio" })
        assertTrue(sessions.setRoomAudio(guest, 2))
        sessions.close()
    }

    @Test
    fun memberJoiningDuringReloadIsIncludedInANewBarrier() {
        val sessions = PlaybackSessionRegistry(reapInBackground = false)
        val host = create(sessions, "host")
        val firstGuest = create(sessions, "first")
        val lateGuest = create(sessions, "late")
        assertTrue(join(sessions, host, firstGuest))
        sessions.drainCommands(host)
        sessions.drainCommands(firstGuest)

        assertTrue(sessions.setRoomAudio(host, 2))
        val generation = assertNotNull(
            sessions.drainCommands(host)
                .single { it.type == "room-audio" }
                .generation,
        )
        sessions.drainCommands(firstGuest)
        assertTrue(sessions.markReady(host, generation))

        assertTrue(join(sessions, host, lateGuest))

        val nextHost = sessions.drainCommands(host).single { it.type == "room-audio" }
        val nextFirst = sessions.drainCommands(firstGuest).single { it.type == "room-audio" }
        val nextLate = sessions.drainCommands(lateGuest).single { it.type == "room-audio" }
        assertTrue(assertNotNull(nextHost.generation) > generation)
        assertEquals(nextHost.generation, nextFirst.generation)
        assertEquals(nextHost.generation, nextLate.generation)
        sessions.close()
    }

    @Test
    fun leavingMemberCannotStrandTheRemainingReadyMembers() {
        val sessions = PlaybackSessionRegistry(reapInBackground = false)
        val host = create(sessions, "host")
        val guest = create(sessions, "guest")
        assertTrue(join(sessions, host, guest))
        sessions.drainCommands(host)
        sessions.drainCommands(guest)
        assertTrue(sessions.setRoomAudio(host, 1))
        val generation = assertNotNull(
            sessions.drainCommands(host).single { it.type == "room-audio" }.generation,
        )
        sessions.drainCommands(guest)
        assertTrue(sessions.markReady(host, generation))

        sessions.leaveRoom(guest)

        assertTrue(sessions.drainCommands(host).any { it.type == "room-go" })
        sessions.close()
    }

    @Test
    fun unreadyMemberCannotStrandTheRoomReloadBarrier() = runBlocking {
        val sessions = PlaybackSessionRegistry(staleMs = 25, reapInBackground = false)
        val host = create(sessions, "host")
        val guest = create(sessions, "guest")
        assertTrue(join(sessions, host, guest))
        val generation = assertNotNull(
            sessions.drainCommands(host).single { it.type == "room-audio" }.generation,
        )
        sessions.drainCommands(guest)
        assertTrue(sessions.markReady(host, generation))

        val roomGo = withTimeout(1_000) {
            while (true) {
                sessions.drainCommands(host).firstOrNull { it.type == "room-go" }?.let {
                    return@withTimeout it
                }
                delay(5)
            }
            error("unreachable")
        }

        assertEquals(generation, roomGo.generation)
        sessions.close()
    }

    @Test
    fun readyIsGenerationBoundAndIdempotentAndLeaveIsIdempotent() {
        val sessions = PlaybackSessionRegistry(reapInBackground = false)
        val host = create(sessions, "host")
        val guest = create(sessions, "guest")
        assertTrue(join(sessions, host, guest))
        val hostBarrier = sessions.drainCommands(host).single { it.type == "room-audio" }
        val guestBarrier = sessions.drainCommands(guest).single { it.type == "room-audio" }
        val generation = assertNotNull(hostBarrier.generation)
        assertEquals(generation, guestBarrier.generation)

        assertTrue(sessions.markReady(host, generation))
        assertTrue(sessions.markReady(host, generation))
        assertFalse(sessions.markReady(host, 0))
        assertFalse(sessions.markReady(host, -1))
        assertFalse(sessions.markReady(guest, generation - 1))
        assertTrue(sessions.drainCommands(host).none { it.type == "room-go" })
        assertTrue(sessions.drainCommands(guest).none { it.type == "room-go" })

        assertTrue(sessions.markReady(guest, generation))
        val hostGo = sessions.drainCommands(host).single { it.type == "room-go" }
        val guestGo = sessions.drainCommands(guest).single { it.type == "room-go" }
        assertEquals(generation, hostGo.generation)
        assertEquals(generation, guestGo.generation)
        assertTrue(sessions.markReady(guest, generation))
        assertTrue(sessions.drainCommands(host).isEmpty())

        sessions.leaveRoom(guest)
        sessions.leaveRoom(guest)
        assertEquals(setOf(host), sessions.roomMembers(host))
        sessions.close()
    }

    @Test
    fun everyEmittedCommandHasAPerLeaseIncreasingSequence() {
        val sessions = PlaybackSessionRegistry(reapInBackground = false)
        val host = create(sessions, "host")
        val guest = create(sessions, "guest")
        assertTrue(join(sessions, host, guest))
        assertTrue(sessions.setRoomAudio(host, 2))

        listOf(host, guest).forEach { member ->
            val sequences = sessions.drainCommands(member).map { assertNotNull(it.sequence) }
            assertTrue(sequences.isNotEmpty())
            assertTrue(sequences.all { it > 0 })
            assertEquals(sequences.sorted(), sequences)
            assertEquals(sequences.distinct(), sequences)
        }
        sessions.close()
    }

    @Test
    fun reconnectResendsAnActiveReloadBarrierAfterTheRoster() {
        val sessions = PlaybackSessionRegistry(reapInBackground = false)
        val host = create(sessions, "host")
        val guest = create(sessions, "guest")
        assertTrue(join(sessions, host, guest))
        sessions.drainCommands(host)
        sessions.drainCommands(guest)
        assertTrue(sessions.setRoomAudio(host, 2))
        sessions.drainCommands(guest)

        sessions.resendRoomState(guest)

        assertEquals(
            listOf("room-state", "room-audio"),
            sessions.drainCommands(guest).map { it.type },
        )
        sessions.close()
    }

    @Test
    fun reconnectReplaysACompletedBarrierAfterADelayedFallbackDrain() {
        val sessions = PlaybackSessionRegistry(reapInBackground = false)
        val host = create(sessions, "host")
        val guest = create(sessions, "guest")
        assertTrue(join(sessions, host, guest))
        sessions.drainCommands(host)
        sessions.drainCommands(guest)
        assertTrue(sessions.setRoomAudio(host, 2))
        val hostBarrier = sessions.drainCommands(host).single { it.type == "room-audio" }
        val guestBarrier = sessions.drainCommands(guest).single { it.type == "room-audio" }
        val generation = assertNotNull(hostBarrier.generation)
        assertEquals(generation, guestBarrier.generation)
        assertTrue(sessions.markReady(host, generation))
        assertTrue(sessions.markReady(guest, generation))
        sessions.drainCommands(host)

        val delayedFallback = sessions.drainCommands(guest).single { it.type == "room-go" }
        sessions.resendRoomState(guest)

        val reconnect = sessions.drainCommands(guest)
        assertEquals(listOf("room-state", "room-go"), reconnect.map { it.type })
        assertEquals(delayedFallback.generation, reconnect.last().generation)
        assertTrue(assertNotNull(reconnect.first().sequence) > assertNotNull(delayedFallback.sequence))
        sessions.close()
    }

    @Test
    fun commandDrainCannotSplitAnAtomicProtocolBatchAcrossTransports() {
        val sessions = PlaybackSessionRegistry(reapInBackground = false)
        val lease = create(sessions, "viewer")
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        var drained = emptyList<SessionCommandDto>()
        try {
            synchronized(sessions) {
                sessions.enqueue(
                    lease,
                    SessionCommandDto(
                        type = "room-state",
                        members = emptyList(),
                    ),
                )
                executor.execute {
                    started.countDown()
                    drained = sessions.drainCommands(lease)
                    finished.countDown()
                }
                assertTrue(started.await(1, TimeUnit.SECONDS))
                assertFalse(
                    finished.await(100, TimeUnit.MILLISECONDS),
                    "a heartbeat drain escaped the room protocol transaction",
                )
                sessions.enqueue(
                    lease,
                    SessionCommandDto(
                        type = "room-audio",
                        audioIndex = 0,
                        generation = 1,
                    ),
                )
            }
            assertTrue(finished.await(1, TimeUnit.SECONDS))
            assertEquals(listOf("room-state", "room-audio"), drained.map { it.type })
        } finally {
            executor.shutdownNow()
            sessions.close()
        }
    }
}
