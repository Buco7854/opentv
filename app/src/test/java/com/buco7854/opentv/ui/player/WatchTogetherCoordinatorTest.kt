package com.buco7854.opentv.ui.player

import com.buco7854.opentv.contract.RoomMemberDto
import com.buco7854.opentv.contract.SessionCommandDto
import com.buco7854.opentv.contract.SyncStateDto
import com.buco7854.opentv.contract.WatchIntentPeer
import com.buco7854.opentv.contract.WatchIntentResponse
import com.buco7854.opentv.hub.HubDuplicatePlaybackException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WatchTogetherCoordinatorTest {
    private var commandSequence = 0L

    private fun command(
        type: String,
        sequence: Long? = null,
        text: String? = null,
        peerId: String? = null,
        peerName: String? = null,
        requestId: String? = null,
        accepted: Boolean? = null,
        quiet: Boolean = false,
        sync: SyncStateDto? = null,
        members: List<RoomMemberDto>? = null,
        audioIndex: Int? = null,
        generation: Long? = if (type == "room-audio" || type == "room-go") 1 else null,
    ) = SessionCommandDto(
        type = type,
        sequence = sequence ?: ++commandSequence,
        text = text,
        peerId = peerId,
        peerName = peerName,
        requestId = requestId,
        accepted = accepted,
        quiet = quiet,
        sync = sync,
        members = members,
        audioIndex = audioIndex,
        generation = generation,
    )

    @Test
    fun adminPlaybackAndMessageCommandsApplyWithoutEchoingSync() = runTest {
        val hub = FakeWatchTogetherHub()
        val player = FakeWatchTogetherPlayer()
        val coordinator = coordinator(hub, player)
        coordinator.handleCommand(
            command(
                type = "room-state",
                members = listOf(
                    RoomMemberDto("self", "Me", host = false, controller = true),
                    RoomMemberDto("host", "Host", host = true, controller = true),
                ),
            ),
        )
        runCurrent()

        coordinator.handleCommand(command(type = "pause"))
        player.emitChanged(seek = false)
        coordinator.handleCommand(command(type = "play"))
        player.emitChanged(seek = false)
        coordinator.handleCommand(command(type = "message", text = "Maintenance soon"))
        runCurrent()

        assertEquals(1, player.pauseCalls)
        assertEquals(1, player.playCalls)
        assertTrue(player.paused.not())
        assertEquals("Maintenance soon", coordinator.state.value.notice?.text)
        assertTrue(hub.syncs.isEmpty())
    }

    @Test
    fun playbackCommandReceivedBeforeMediaAttachesIsNotDropped() = runTest {
        val coordinator = WatchTogetherCoordinator(
            hub = FakeWatchTogetherHub(),
            scope = backgroundScope,
            reloadAudio = { _, _ -> },
            clock = { testScheduler.currentTime },
        )
        coordinator.handleCommand(command(type = "pause", sequence = 1))
        val player = FakeWatchTogetherPlayer()

        coordinator.attachPlayer(player)

        assertTrue(player.paused)
        assertEquals(1, player.pauseCalls)
    }

    @Test
    fun roomSyncReceivedBeforeMediaAttachesRestoresTheVodPosition() = runTest {
        val coordinator = WatchTogetherCoordinator(
            hub = FakeWatchTogetherHub(),
            scope = backgroundScope,
            reloadAudio = { _, _ -> },
            clock = { testScheduler.currentTime },
        )
        coordinator.handleCommand(
            command(
                type = "sync",
                sequence = 1,
                sync = SyncStateDto(48_000, paused = true, seek = true),
            ),
        )
        val player = FakeWatchTogetherPlayer(positionMs = 0)

        coordinator.attachPlayer(player)

        assertEquals(listOf(48_000L), player.seeks)
        assertTrue(player.paused)
    }

    @Test
    fun laterPlaybackCommandDoesNotEraseAPendingRoomSeek() = runTest {
        val coordinator = WatchTogetherCoordinator(
            hub = FakeWatchTogetherHub(),
            scope = backgroundScope,
            reloadAudio = { _, _ -> },
            clock = { testScheduler.currentTime },
        )
        coordinator.handleCommand(
            command(
                type = "sync",
                sequence = 1,
                sync = SyncStateDto(48_000, paused = true, seek = true),
            ),
        )
        coordinator.handleCommand(command(type = "play", sequence = 2))
        val player = FakeWatchTogetherPlayer(positionMs = 0).apply { paused = true }

        coordinator.attachPlayer(player)

        assertEquals(listOf(48_000L), player.seeks)
        assertFalse(player.paused)
    }

    @Test
    fun joinAndControlCommandsPopulatePromptsResponsesAndActions() = runTest {
        val hub = FakeWatchTogetherHub()
        val player = FakeWatchTogetherPlayer()
        val coordinator = coordinator(hub, player)

        coordinator.handleCommand(
            command(
                type = "join-request",
                peerId = "peer-1",
                peerName = "Ari",
                requestId = "request-1",
            ),
        )
        assertEquals("request-1", coordinator.state.value.joinRequests.single().requestId)
        assertEquals(WatchTogetherNoticeKind.JOIN_REQUEST, coordinator.state.value.notice?.kind)

        coordinator.handleCommand(
            command(
                type = "join-request",
                peerId = "peer-2",
                peerName = "Bo",
                requestId = "request-2",
                quiet = true,
            ),
        )
        assertEquals(2, coordinator.state.value.joinRequests.size)
        assertEquals("Ari", coordinator.state.value.notice?.text)

        coordinator.answerJoin("request-1", accept = true)
        coordinator.answerJoin("request-2", accept = false)
        coordinator.handleCommand(command(type = "join-response", accepted = false))
        assertEquals(WatchTogetherNoticeKind.JOIN_DECLINED, coordinator.state.value.notice?.kind)
        coordinator.handleCommand(command(type = "join-response", accepted = true))
        assertEquals(
            listOf("request-1" to true, "request-2" to false),
            hub.joinAnswers,
        )
        assertEquals(WatchTogetherNoticeKind.JOINED, coordinator.state.value.notice?.kind)

        coordinator.handleCommand(
            command(type = "control-request", peerId = "peer-1", peerName = "Ari"),
        )
        assertEquals("peer-1", coordinator.state.value.controlRequests.single().peerId)
        coordinator.answerControl("peer-1", grant = true)
        coordinator.handleCommand(command(type = "control-response", accepted = true))
        assertEquals(WatchTogetherNoticeKind.CONTROL_GRANTED, coordinator.state.value.notice?.kind)
        coordinator.handleCommand(command(type = "control-response", accepted = false))
        assertEquals(listOf("peer-1" to true), hub.controlAnswers)
        assertEquals(WatchTogetherNoticeKind.CONTROL_DENIED, coordinator.state.value.notice?.kind)
    }

    @Test
    fun decliningARequiredJoinRefusesRatherThanPlayingAlone() = runTest {
        val hub = FakeWatchTogetherHub().apply {
            watchAloneFailure = HubDuplicatePlaybackException(
                "same_content_already_playing",
                "Already playing",
            )
            intentResponse = WatchIntentResponse(
                sameContent = listOf(
                    WatchIntentPeer("own-tv", "Living room", sameAccount = true),
                    WatchIntentPeer("someone-else", "Ari", sameAccount = false),
                ),
                full = false,
                limit = 2,
                requiresJoin = true,
            )
        }
        val player = FakeWatchTogetherPlayer()
        val starts = mutableListOf<Boolean>()
        val coordinator = WatchTogetherCoordinator(
            hub = hub,
            scope = backgroundScope,
            reloadAudio = { _, _ -> },
            onStartMedia = { starts += it },
            clock = { testScheduler.currentTime },
        )
        coordinator.attachPlayer(player)

        coordinator.checkIntent()

        // Only this account's own device can satisfy the requirement; another user's
        // session is not what is blocking us and offering it would mislead.
        assertEquals(listOf("own-tv"), coordinator.state.value.peers.map { it.id })
        assertTrue(coordinator.state.value.requiresJoin)
        assertTrue("media must stay closed while the choice is pending", starts.isEmpty())

        coordinator.watchAlone()
        runCurrent()

        assertEquals("the server is told, not guessed at", 1, hub.watchAloneCalls)
        assertTrue("the refusal must be visible", coordinator.state.value.duplicateRefused)
        assertFalse("a deleted lease cannot offer another action", coordinator.state.value.choosing)
        assertTrue("stale peers cannot remain actionable", coordinator.state.value.peers.isEmpty())
        assertTrue("declining must never start a second stream", starts.isEmpty())
    }

    @Test
    fun requiredJoinStartsSoloWhenTheDuplicateEndedBeforeTheServerCheck() = runTest {
        val hub = FakeWatchTogetherHub().apply {
            intentResponse = WatchIntentResponse(
                sameContent = listOf(WatchIntentPeer("own-tv", "Living room", sameAccount = true)),
                full = false,
                limit = 2,
                requiresJoin = true,
            )
        }
        val starts = mutableListOf<Boolean>()
        val coordinator = WatchTogetherCoordinator(
            hub = hub,
            scope = backgroundScope,
            reloadAudio = { _, _ -> },
            onStartMedia = { starts += it },
            clock = { testScheduler.currentTime },
        )

        coordinator.checkIntent()
        coordinator.watchAlone()
        runCurrent()

        assertEquals(1, hub.watchAloneCalls)
        assertEquals(listOf(false), starts)
        assertFalse(coordinator.state.value.requiresJoin)
        assertFalse(coordinator.state.value.duplicateRefused)
    }

    @Test
    fun failedJoinRequestRestoresTheChoiceAndClearsTransitioning() = runTest {
        val hub = FakeWatchTogetherHub().apply {
            intentResponse = WatchIntentResponse(
                listOf(WatchIntentPeer("peer-1", "Ari")),
                full = false,
                limit = 2,
            )
            requestJoinFailure = IllegalStateException("offline")
        }
        val coordinator = coordinator(hub, FakeWatchTogetherPlayer())
        coordinator.checkIntent()

        coordinator.askToJoin("peer-1")

        assertFalse(coordinator.state.value.transitioning)
        assertTrue(coordinator.state.value.choosing)
        assertEquals(WatchTogetherNoticeKind.ACTION_FAILED, coordinator.state.value.notice?.kind)
    }

    @Test
    fun failedRequiredAdmissionIsNotMisreportedAsADuplicateRefusal() = runTest {
        val hub = FakeWatchTogetherHub().apply {
            intentResponse = WatchIntentResponse(
                listOf(WatchIntentPeer("own-tv", "Living room", sameAccount = true)),
                full = false,
                limit = 2,
                requiresJoin = true,
            )
            watchAloneFailure = IllegalStateException("offline")
        }
        val coordinator = coordinator(hub, FakeWatchTogetherPlayer())
        coordinator.checkIntent()

        coordinator.watchAlone()
        runCurrent()

        assertTrue(coordinator.state.value.choosing)
        assertFalse(coordinator.state.value.duplicateRefused)
        assertFalse(coordinator.state.value.transitioning)
        assertEquals(WatchTogetherNoticeKind.ACTION_FAILED, coordinator.state.value.notice?.kind)
    }

    @Test
    fun optionalIntentOffersOwnAndOtherAccountPeers() = runTest {
        val hub = FakeWatchTogetherHub().apply {
            intentResponse = WatchIntentResponse(
                listOf(
                    WatchIntentPeer("own-tv", "Living room", sameAccount = true),
                    WatchIntentPeer("someone-else", "Ari", sameAccount = false),
                ),
                full = false,
                limit = 2,
                requiresJoin = false,
            )
        }
        val coordinator = coordinator(hub, FakeWatchTogetherPlayer())

        coordinator.checkIntent()

        assertEquals(
            listOf("own-tv", "someone-else"),
            coordinator.state.value.peers.map { it.id },
        )
    }

    @Test
    fun intentAndRoomActionsReachTheExpectedHubEndpoints() = runTest {
        val hub = FakeWatchTogetherHub().apply {
            intentResponse = WatchIntentResponse(listOf(WatchIntentPeer("peer-1", "Ari")), false, 2)
        }
        val player = FakeWatchTogetherPlayer()
        val coordinator = coordinator(hub, player)

        coordinator.checkIntent()
        assertTrue(coordinator.state.value.choosing)
        assertEquals("peer-1", coordinator.state.value.peers.single().id)
        coordinator.watchAlone()
        coordinator.askToJoin("peer-1")
        coordinator.requestControl()

        coordinator.handleCommand(
            command(
                type = "room-state",
                members = listOf(
                    RoomMemberDto("self", "Me", host = true, controller = true),
                    RoomMemberDto("peer-1", "Ari", host = false, controller = false),
                ),
            ),
        )
        coordinator.setControl("peer-1", grant = true)
        coordinator.kick("peer-1")
        assertTrue(coordinator.selectRoomAudio(2))
        coordinator.leave()

        assertEquals(listOf("peer-1"), hub.joinRequests)
        assertEquals(1, hub.controlRequests)
        assertEquals(listOf("peer-1" to true), hub.controlSets)
        assertEquals(listOf("peer-1"), hub.kicks)
        assertEquals(listOf(2), hub.roomAudio)
        assertEquals(1, hub.leaveCalls)
    }

    @Test
    fun roomWithoutARealSharedTransportKeepsTheProviderCapacityBlock() = runTest {
        val hub = FakeWatchTogetherHub().apply {
            intentResponse = WatchIntentResponse(
                listOf(WatchIntentPeer("peer-1", "Ari")),
                true,
                1,
            )
        }
        val coordinator = WatchTogetherCoordinator(
            hub = hub,
            scope = backgroundScope,
            reloadAudio = { _, _ -> },
            sharesRoomRead = { false },
            clock = { testScheduler.currentTime },
        )
        coordinator.checkIntent()
        assertTrue(coordinator.state.value.blocked)

        coordinator.handleCommand(
            command(
                type = "room-state",
                members = listOf(
                    RoomMemberDto("self", "Me", host = false, controller = false),
                    RoomMemberDto("peer-1", "Ari", host = true, controller = true),
                ),
            ),
        )

        assertTrue(coordinator.state.value.inRoom)
        assertTrue(coordinator.state.value.blocked)
    }

    @Test
    fun leavingARemuxRoomRebuildsSoloPlaybackAtTheCapturedPosition() = runTest {
        val hub = FakeWatchTogetherHub()
        val player = FakeWatchTogetherPlayer(positionMs = 31_000)
        val soloReloads = mutableListOf<Long>()
        val coordinator = WatchTogetherCoordinator(
            hub = hub,
            scope = backgroundScope,
            reloadAudio = { _, _ -> },
            reloadAfterLeave = { soloReloads += it },
            clock = { testScheduler.currentTime },
        ).also { it.attachPlayer(player) }
        coordinator.handleCommand(
            command(
                type = "room-state",
                members = listOf(
                    RoomMemberDto("self", "Me", host = false, controller = true),
                    RoomMemberDto("host", "Host", host = true, controller = true),
                ),
            ),
        )

        coordinator.leave()

        assertEquals(listOf(31_000L), soloReloads)
        assertEquals(1, player.pauseCalls)
        assertEquals(1, player.playCalls)
        assertFalse(coordinator.state.value.inRoom)
    }

    @Test
    fun roomStateReplacesTheRosterOnReconnect() = runTest {
        val coordinator = coordinator(FakeWatchTogetherHub(), FakeWatchTogetherPlayer())
        coordinator.handleCommand(
            command(
                type = "room-state",
                members = listOf(
                    RoomMemberDto("self", "Me", host = true, controller = true),
                    RoomMemberDto("old", "Old", host = false, controller = false),
                ),
            ),
        )

        val replacement = listOf(
            RoomMemberDto("self", "Me", host = false, controller = false),
            RoomMemberDto("new", "New host", host = true, controller = true),
        )
        coordinator.handleCommand(command(type = "room-state", members = replacement))

        assertEquals(replacement, coordinator.state.value.members)
        assertFalse(coordinator.state.value.canControl)
    }

    @Test
    fun remuxRoomAudioReloadsReportsReadyAndHoldsUntilRoomGo() = runTest {
        val hub = FakeWatchTogetherHub()
        val player = FakeWatchTogetherPlayer(positionMs = 42_000)
        val reloads = mutableListOf<Pair<Int, Long>>()
        val coordinator = coordinator(hub, player, reloads)
        putInRoom(coordinator)

        coordinator.handleCommand(command(type = "room-audio", audioIndex = 3))
        runCurrent()
        assertEquals(listOf(3 to 42_000L), reloads)
        assertTrue(coordinator.state.value.loading)
        assertTrue(player.paused)

        player.emitReady()
        runCurrent()
        assertEquals(1, hub.readyCalls)
        assertTrue(player.paused)

        coordinator.handleCommand(command(type = "room-go"))
        assertFalse(coordinator.state.value.loading)
        assertFalse(player.paused)
    }

    @Test
    fun directPlayingRoomSkipsTheReloadButStillReportsReady() = runTest {
        val hub = FakeWatchTogetherHub().apply { direct = true }
        val player = FakeWatchTogetherPlayer(positionMs = 7_000)
        val reloads = mutableListOf<Pair<Int, Long>>()
        val coordinator = coordinator(hub, player, reloads)
        putInRoom(coordinator)

        coordinator.handleCommand(command(type = "room-audio", audioIndex = 1))
        runCurrent()

        // Nothing to reload: in-band tracks are all present already...
        assertTrue(reloads.isEmpty())
        assertFalse(coordinator.state.value.loading)
        assertEquals(0, player.pauseCalls)
        // ...but the server waits for EVERY member before broadcasting room-go,
        // so silence here would strand the peers that must switch format.
        assertEquals(1, hub.readyCalls)
    }

    @Test
    fun directLiveRoomWaitsForTheRelaySourceBeforeReportingReady() = runTest {
        val hub = FakeWatchTogetherHub().apply { direct = true }
        val player = FakeWatchTogetherPlayer(positionMs = 7_000)
        val coordinator = WatchTogetherCoordinator(
            hub = hub,
            scope = backgroundScope,
            reloadAudio = { _, _ -> },
            onRoomMembershipChanged = { true },
            clock = { testScheduler.currentTime },
        ).also { it.attachPlayer(player) }
        coordinator.handleCommand(
            command(
                type = "room-state",
                members = listOf(
                    RoomMemberDto("self", "Me", host = true, controller = true),
                    RoomMemberDto("peer", "Peer", host = false, controller = true),
                ),
            ),
        )

        coordinator.handleCommand(command(type = "room-audio", audioIndex = 0))
        coordinator.handleCommand(command(type = "room-audio", audioIndex = 0))
        runCurrent()

        assertTrue(coordinator.state.value.loading)
        assertEquals(0, hub.readyCalls)
        player.emitReady()
        runCurrent()
        assertEquals(1, hub.readyCalls)
    }

    @Test
    fun aNewAudioBarrierCancelsTheSupersededReload() = runTest {
        val hub = FakeWatchTogetherHub()
        val player = FakeWatchTogetherPlayer(positionMs = 7_000)
        val firstStarted = CompletableDeferred<Unit>()
        val firstCancelled = CompletableDeferred<Unit>()
        val completed = mutableListOf<Int>()
        val coordinator = WatchTogetherCoordinator(
            hub = hub,
            scope = backgroundScope,
            reloadAudio = { index, _ ->
                if (index == 1) {
                    firstStarted.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        firstCancelled.complete(Unit)
                    }
                } else {
                    completed += index
                }
            },
            clock = { testScheduler.currentTime },
        ).also { it.attachPlayer(player) }
        putInRoom(coordinator)

        coordinator.handleCommand(command(type = "room-audio", audioIndex = 1))
        runCurrent()
        firstStarted.await()
        coordinator.handleCommand(command(type = "room-audio", audioIndex = 2, generation = 2))
        runCurrent()

        assertTrue(firstCancelled.isCompleted)
        assertEquals(listOf(2), completed)
    }

    @Test
    fun olderCommandSequencesAndStaleRoomGoAreIgnored() = runTest {
        val hub = FakeWatchTogetherHub()
        val player = FakeWatchTogetherPlayer(positionMs = 7_000)
        val coordinator = coordinator(hub, player)
        putInRoom(coordinator)

        coordinator.handleCommand(command(type = "pause", sequence = 10))
        coordinator.handleCommand(command(type = "play", sequence = 9))
        assertTrue(player.paused)

        coordinator.handleCommand(
            command(type = "room-audio", audioIndex = 1, generation = null, sequence = 11),
        )
        assertFalse(coordinator.state.value.loading)
        coordinator.handleCommand(
            command(type = "room-audio", audioIndex = 1, generation = 7, sequence = 12),
        )
        runCurrent()
        coordinator.handleCommand(
            command(type = "room-audio", audioIndex = 2, generation = 8, sequence = 13),
        )
        runCurrent()
        coordinator.handleCommand(command(type = "room-go", generation = 7, sequence = 14))
        assertTrue(coordinator.state.value.loading)

        coordinator.handleCommand(command(type = "room-go", generation = 8, sequence = 15))
        assertFalse(coordinator.state.value.loading)
    }

    @Test
    fun aReplacementLeaseAcceptsItsFreshCommandSequence() = runTest {
        val hub = FakeWatchTogetherHub()
        val player = FakeWatchTogetherPlayer()
        val coordinator = coordinator(hub, player)

        coordinator.handleCommand(
            command(
                type = "room-state",
                sequence = 9,
                members = listOf(
                    RoomMemberDto("self", "Me", host = true, controller = true),
                    RoomMemberDto("peer", "Peer", host = false, controller = false),
                ),
            ),
        )
        coordinator.handleCommand(command(type = "pause", sequence = 10))
        assertTrue(player.paused)
        assertTrue(coordinator.state.value.inRoom)

        hub.selfId = "replacement-lease"
        coordinator.handleCommand(command(type = "play", sequence = 1))

        assertFalse(player.paused)
        assertEquals(1, player.playCalls)
        assertFalse(coordinator.state.value.inRoom)
    }

    @Test
    fun readyRetriesTransientFailuresWithinTheBarrier() = runTest {
        val hub = FakeWatchTogetherHub().apply { readyFailuresRemaining = 2 }
        val player = FakeWatchTogetherPlayer(positionMs = 7_000)
        val coordinator = coordinator(hub, player)
        putInRoom(coordinator)
        coordinator.handleCommand(command(type = "room-audio", audioIndex = 1, generation = 4))
        runCurrent()

        player.emitReady()
        runCurrent()
        assertEquals(1, hub.readyCalls)
        advanceTimeBy(500)
        runCurrent()
        assertEquals(2, hub.readyCalls)
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(3, hub.readyCalls)
        assertEquals(listOf(4L, 4L, 4L), hub.readyGenerations)
    }

    @Test
    fun readyRetriesCannotOutliveTheirBarrierGeneration() = runTest {
        val hub = FakeWatchTogetherHub().apply { readyFailuresRemaining = Int.MAX_VALUE }
        val player = FakeWatchTogetherPlayer(positionMs = 7_000)
        val coordinator = coordinator(hub, player)
        putInRoom(coordinator)

        coordinator.handleCommand(command(type = "room-audio", audioIndex = 1, generation = 4))
        runCurrent()
        player.emitReady()
        runCurrent()
        assertEquals(listOf(4L), hub.readyGenerations)

        coordinator.handleCommand(command(type = "room-audio", audioIndex = 2, generation = 5))
        runCurrent()
        player.emitReady()
        runCurrent()
        advanceTimeBy(1_500)
        runCurrent()

        assertEquals(listOf(4L, 5L, 5L, 5L), hub.readyGenerations)
    }

    @Test
    fun readyRetryGivesUpAfterThreeAttempts() = runTest {
        val hub = FakeWatchTogetherHub().apply { readyFailuresRemaining = Int.MAX_VALUE }
        val player = FakeWatchTogetherPlayer(positionMs = 7_000)
        val coordinator = coordinator(hub, player)
        putInRoom(coordinator)
        coordinator.handleCommand(command(type = "room-audio", audioIndex = 1, generation = 5))
        runCurrent()

        player.emitReady()
        runCurrent()
        advanceTimeBy(1_500)
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(3, hub.readyCalls)
        assertEquals(listOf(5L, 5L, 5L), hub.readyGenerations)
        assertEquals(WatchTogetherNoticeKind.ACTION_FAILED, coordinator.state.value.notice?.kind)
    }

    @Test
    fun readyFloorDoesNotReleaseTheRoomBeforeTheReloadRequestCompletes() = runTest {
        val hub = FakeWatchTogetherHub()
        val player = FakeWatchTogetherPlayer(positionMs = 7_000)
        val reloadMayFinish = CompletableDeferred<Unit>()
        val coordinator = WatchTogetherCoordinator(
            hub = hub,
            scope = backgroundScope,
            reloadAudio = { _, _ -> reloadMayFinish.await() },
            clock = { testScheduler.currentTime },
        ).also { it.attachPlayer(player) }
        putInRoom(coordinator)

        coordinator.handleCommand(command(type = "room-audio", audioIndex = 1))
        runCurrent()
        advanceTimeBy(4_000)
        runCurrent()

        assertEquals(0, hub.readyCalls)
        reloadMayFinish.complete(Unit)
        runCurrent()
        player.emitReady()
        runCurrent()
        assertEquals(1, hub.readyCalls)
    }

    @Test
    fun barrierFailOpenResumesThePlayerAttachedAfterAConfigurationChange() = runTest {
        val hub = FakeWatchTogetherHub()
        val oldPlayer = FakeWatchTogetherPlayer(positionMs = 7_000)
        val coordinator = coordinator(hub, oldPlayer)
        putInRoom(coordinator)
        coordinator.handleCommand(command(type = "room-audio", audioIndex = 1))
        runCurrent()
        val recreatedPlayer = FakeWatchTogetherPlayer(positionMs = 7_000)

        coordinator.attachPlayer(recreatedPlayer)
        advanceTimeBy(12_000)
        runCurrent()

        assertFalse(coordinator.state.value.loading)
        assertEquals(0, oldPlayer.playCalls)
        assertEquals(1, recreatedPlayer.playCalls)
    }

    @Test
    fun roomAudioReceivedBeforeTheRosterWaitsForMembershipAndTheSharedSource() = runTest {
        val hub = FakeWatchTogetherHub().apply { direct = true }
        val player = FakeWatchTogetherPlayer(positionMs = 7_000)
        val coordinator = WatchTogetherCoordinator(
            hub = hub,
            scope = backgroundScope,
            reloadAudio = { _, _ -> },
            onRoomMembershipChanged = { true },
            clock = { testScheduler.currentTime },
        ).also { it.attachPlayer(player) }

        coordinator.handleCommand(command(type = "room-audio", audioIndex = 0))
        runCurrent()
        assertEquals(0, hub.readyCalls)
        assertFalse(coordinator.state.value.loading)

        putInRoom(coordinator)
        assertTrue(coordinator.state.value.loading)
        player.emitReady()
        runCurrent()
        assertEquals(1, hub.readyCalls)
    }

    @Test
    fun syncUsesWebThresholdsForAnchorsAndDeliberateSeeks() = runTest {
        val player = FakeWatchTogetherPlayer(positionMs = 10_000)
        val coordinator = coordinator(FakeWatchTogetherHub(), player)

        coordinator.handleCommand(
            command(
                type = "sync",
                sync = SyncStateDto(13_999, paused = false, seek = false),
            ),
        )
        assertTrue(player.seeks.isEmpty())
        coordinator.handleCommand(
            command(
                type = "sync",
                sync = SyncStateDto(14_001, paused = false, seek = false),
            ),
        )
        assertEquals(14_001L, player.seeks.single())

        player.positionMs = 20_000
        player.seeks.clear()
        coordinator.handleCommand(
            command(
                type = "sync",
                sync = SyncStateDto(20_750, paused = false, seek = true),
            ),
        )
        assertTrue(player.seeks.isEmpty())
        coordinator.handleCommand(
            command(
                type = "sync",
                sync = SyncStateDto(20_751, paused = false, seek = true),
            ),
        )
        assertEquals(20_751L, player.seeks.single())
    }

    @Test
    fun onlyControllersEmitLocalSync() = runTest {
        val hub = FakeWatchTogetherHub()
        val player = FakeWatchTogetherPlayer(positionMs = 9_000)
        val coordinator = coordinator(hub, player)
        coordinator.handleCommand(
            command(
                type = "room-state",
                members = listOf(
                    RoomMemberDto("self", "Me", host = false, controller = false),
                    RoomMemberDto("host", "Host", host = true, controller = true),
                ),
            ),
        )
        runCurrent()

        player.emitChanged(seek = false)
        runCurrent()
        assertTrue(hub.syncs.isEmpty())

        coordinator.handleCommand(
            command(
                type = "room-state",
                members = listOf(
                    RoomMemberDto("self", "Me", host = false, controller = true),
                    RoomMemberDto("host", "Host", host = true, controller = true),
                ),
            ),
        )
        player.emitChanged(seek = true)
        runCurrent()
        assertEquals(true, hub.syncs.single().seek)
    }

    @Test
    fun hostDoesNotAnchorAStalePositionWhileAnIncomingSeekIsSettling() = runTest {
        val hub = FakeWatchTogetherHub()
        val player = FakeWatchTogetherPlayer(positionMs = 10_000)
        val coordinator = coordinator(hub, player)
        coordinator.handleCommand(
            command(
                type = "room-state",
                members = listOf(
                    RoomMemberDto("self", "Me", host = true, controller = true),
                    RoomMemberDto("peer", "Peer", host = false, controller = true),
                ),
            ),
        )
        runCurrent()
        hub.syncs.clear()
        coordinator.handleCommand(
            command(
                type = "sync",
                sync = SyncStateDto(60_000, paused = false, seek = true),
            ),
        )

        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(hub.syncs.isEmpty())

        player.positionMs = 60_000
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(listOf(60_000L), hub.syncs.map(SyncStateDto::positionMs))
    }

    @Test
    fun nullIntentAlwaysClearsTheCheckingState() = runTest {
        val hub = FakeWatchTogetherHub().apply { intentResponse = null }
        val coordinator = coordinator(hub, FakeWatchTogetherPlayer())

        coordinator.checkIntent()

        assertFalse(coordinator.state.value.checking)
    }

    @Test
    fun slowIntentFailsOpenAfterFourSecondsAndStartsSoloMedia() = runTest {
        val hub = FakeWatchTogetherHub().apply {
            intentGate = CompletableDeferred()
        }
        val mediaStarts = mutableListOf<Boolean>()
        val coordinator = WatchTogetherCoordinator(
            hub = hub,
            scope = backgroundScope,
            reloadAudio = { _, _ -> },
            onStartMedia = { mediaStarts += it },
            clock = { testScheduler.currentTime },
        )

        backgroundScope.launch { coordinator.checkIntent() }
        runCurrent()
        advanceTimeBy(3_999)
        runCurrent()
        assertTrue(mediaStarts.isEmpty())

        advanceTimeBy(1)
        runCurrent()

        assertEquals(listOf(false), mediaStarts)
        assertFalse(coordinator.state.value.checking)
    }

    @Test
    fun timedOutIntentCannotLaterStartMediaAgain() = runTest {
        val gate = CompletableDeferred<Unit>()
        val hub = FakeWatchTogetherHub().apply { intentGate = gate }
        val mediaStarts = mutableListOf<Boolean>()
        val coordinator = WatchTogetherCoordinator(
            hub = hub,
            scope = backgroundScope,
            reloadAudio = { _, _ -> },
            onStartMedia = { mediaStarts += it },
            clock = { testScheduler.currentTime },
        )

        backgroundScope.launch { coordinator.checkIntent() }
        runCurrent()
        advanceTimeBy(4_000)
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertEquals(listOf(false), mediaStarts)
    }

    @Test
    fun roomEndedLeavesAndClearsTheRoomWithAVisibleReason() = runTest {
        val hub = FakeWatchTogetherHub()
        val coordinator = coordinator(hub, FakeWatchTogetherPlayer())
        coordinator.handleCommand(
            command(
                type = "room-state",
                members = listOf(RoomMemberDto("self", "Me", host = true, controller = true)),
            ),
        )

        coordinator.handleCommand(command(type = "room-ended"))

        assertEquals(1, hub.leaveCalls)
        assertTrue(coordinator.state.value.members.isEmpty())
        assertEquals(WatchTogetherNoticeKind.ROOM_ENDED, coordinator.state.value.notice?.kind)
    }

    private fun kotlinx.coroutines.test.TestScope.coordinator(
        hub: FakeWatchTogetherHub,
        player: FakeWatchTogetherPlayer,
        reloads: MutableList<Pair<Int, Long>> = mutableListOf(),
    ) = WatchTogetherCoordinator(
        hub = hub,
        scope = backgroundScope,
        reloadAudio = { index, position -> reloads += index to position },
        clock = { testScheduler.currentTime },
    ).also { it.attachPlayer(player) }

    private suspend fun putInRoom(coordinator: WatchTogetherCoordinator) {
        coordinator.handleCommand(
            command(
                type = "room-state",
                members = listOf(
                    RoomMemberDto("self", "Me", host = true, controller = true),
                    RoomMemberDto("peer", "Peer", host = false, controller = true),
                ),
            ),
        )
    }
}

private class FakeWatchTogetherHub : WatchTogetherHub {
    private val commandFlow = MutableSharedFlow<SessionCommandDto>(extraBufferCapacity = 16)
    override val commands: Flow<SessionCommandDto> = commandFlow
    override var selfId: String = "self"
    override var direct: Boolean = false
    var intentResponse: WatchIntentResponse? = WatchIntentResponse(emptyList(), false, 2)
    var intentGate: CompletableDeferred<Unit>? = null
    val joinRequests = mutableListOf<String>()
    var requestJoinFailure: Throwable? = null
    val joinAnswers = mutableListOf<Pair<String, Boolean>>()
    var controlRequests = 0
    val controlAnswers = mutableListOf<Pair<String, Boolean>>()
    val controlSets = mutableListOf<Pair<String, Boolean>>()
    val kicks = mutableListOf<String>()
    val roomAudio = mutableListOf<Int>()
    var readyCalls = 0
    val readyGenerations = mutableListOf<Long>()
    var readyFailuresRemaining = 0
    var leaveCalls = 0
    val syncs = mutableListOf<SyncStateDto>()

    override suspend fun intent(): WatchIntentResponse? {
        intentGate?.await()
        return intentResponse
    }
    override suspend fun requestJoin(peerId: String) {
        joinRequests += peerId
        requestJoinFailure?.let { throw it }
    }
    override suspend fun answerJoin(requestId: String, accept: Boolean) {
        joinAnswers += requestId to accept
    }
    override suspend fun requestControl() {
        controlRequests++
    }
    override suspend fun grantControl(peerId: String, grant: Boolean) {
        controlAnswers += peerId to grant
    }
    override suspend fun setControl(targetId: String, grant: Boolean) {
        controlSets += targetId to grant
    }
    override suspend fun kick(targetId: String) {
        kicks += targetId
    }
    override suspend fun setRoomAudio(audioTrackIndex: Int) {
        roomAudio += audioTrackIndex
    }
    var watchAloneCalls = 0
    var watchAloneFailure: Throwable? = null

    override suspend fun watchAlone() {
        watchAloneCalls++
        watchAloneFailure?.let { throw it }
    }

    override suspend fun ready(generation: Long) {
        readyCalls++
        readyGenerations += generation
        if (readyFailuresRemaining > 0) {
            readyFailuresRemaining--
            throw IllegalStateException("transient")
        }
    }
    override suspend fun leave() {
        leaveCalls++
    }
    override suspend fun sendSync(state: SyncStateDto) {
        syncs += state
    }
}

private class FakeWatchTogetherPlayer(
    override var positionMs: Long = 0,
) : WatchTogetherPlayer {
    private val eventFlow = MutableSharedFlow<WatchTogetherPlaybackEvent>(extraBufferCapacity = 16)
    override val events: Flow<WatchTogetherPlaybackEvent> = eventFlow
    override var paused: Boolean = false
    private var currentRate: Double = 1.0
    override val playbackRate: Double get() = currentRate
    override var isLive: Boolean = false
    var playCalls = 0
    var pauseCalls = 0
    val seeks = mutableListOf<Long>()

    override fun play() {
        playCalls++
        paused = false
    }
    override fun pause() {
        pauseCalls++
        paused = true
    }
    override fun seekTo(positionMs: Long) {
        seeks += positionMs
    }
    override fun setPlaybackRate(rate: Double) {
        currentRate = rate
    }
    fun emitChanged(seek: Boolean) {
        eventFlow.tryEmit(WatchTogetherPlaybackEvent.Changed(seek))
    }
    fun emitReady() {
        eventFlow.tryEmit(WatchTogetherPlaybackEvent.Ready)
    }
}
