package com.buco7854.opentv.hub.playback

import com.buco7854.opentv.contract.ClientCapabilitiesDto
import com.buco7854.opentv.contract.HeartbeatResponseDto
import com.buco7854.opentv.contract.PlaybackCreateRequest
import com.buco7854.opentv.contract.PlaybackLeaseDto
import com.buco7854.opentv.contract.RemuxStartDto
import com.buco7854.opentv.contract.SessionCommandDto
import com.buco7854.opentv.contract.SessionHeartbeatDto
import com.buco7854.opentv.contract.SyncStateDto
import com.buco7854.opentv.contract.WebSocketAccessDto
import com.buco7854.opentv.hub.HubCapacityException
import com.buco7854.opentv.hub.HubForbiddenException
import com.buco7854.opentv.hub.HubGoneException
import com.buco7854.opentv.hub.HubNotFoundException
import com.buco7854.opentv.hub.HubUnauthorizedException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HubPlaybackControllerTest {

    @Test
    fun leaseCreationSendsSuppliedCapabilities() = runTest {
        val api = FakePlaybackApi()
        val controller = controller(api)
        val capabilities = ClientCapabilitiesDto(
            videoCodecs = listOf("h264", "hevc"),
            audioCodecs = listOf("aac", "eac3"),
        )

        controller.start("content-1", capabilities)

        assertEquals(capabilities, api.createRequests.single().capabilities)
        assertSame(HubPlaybackState.LeaseCreated, controller.state.value)
        assertTrue(api.remuxAudioRequests.isEmpty())
    }

    @Test
    fun noExtraTracksIsSuccessfulDirectPlayback() = runTest {
        val api = FakePlaybackApi()
        val controller = controller(api)

        controller.start("content-1", CAPABILITIES)
        controller.startMedia()

        assertEquals(
            HubPlaybackState.Playing(
                target = "https://hub.test/api/v1/stream?u=source&sid=lease-1&g=grant-1",
                direct = true,
                grant = "grant-1",
            ),
            controller.state.value,
        )
    }

    @Test
    fun directLivePlaybackSwitchesToTheRoomRelayAndBack() = runTest {
        val api = FakePlaybackApi()
        val controller = controller(api)
        controller.start("content-1", CAPABILITIES)
        controller.startMedia()

        controller.setLiveRoom(true)
        assertEquals(
            "https://hub.test/api/v1/relay?u=source&sid=lease-1&g=grant-1",
            (controller.state.value as HubPlaybackState.Playing).target,
        )

        controller.setLiveRoom(false)
        assertEquals(
            "https://hub.test/api/v1/stream?u=source&sid=lease-1&g=grant-1",
            (controller.state.value as HubPlaybackState.Playing).target,
        )
    }

    @Test
    fun roomMediaStartsDirectLivePlaybackOnTheSharedRelay() = runTest {
        val api = FakePlaybackApi()
        val controller = controller(api)
        controller.start("content-1", CAPABILITIES)

        controller.startMedia(inRoom = true)

        assertEquals(
            "https://hub.test/api/v1/relay?u=source&sid=lease-1&g=grant-1",
            (controller.state.value as HubPlaybackState.Playing).target,
        )
        assertEquals(listOf(0), api.remuxAudioRequests)
    }

    @Test
    fun hlsRoomUsesTheAdvertisedSharedPathAndStaysDirect() = runTest {
        val api = FakePlaybackApi().apply {
            leaseResult = LEASE.copy(
                streamUrl = "/api/v1/stream?u=h.source&sid=lease-1&g=grant-1",
                sharedHlsUrl = "/api/v1/shared-hls?u=h.source&sid=lease-1&g=grant-1",
            )
        }
        val controller = controller(api)
        controller.start("content-1", CAPABILITIES)

        assertTrue(controller.sharesLiveRoomRead())
        controller.startMedia(inRoom = true)

        val playing = controller.state.value as HubPlaybackState.Playing
        assertEquals(
            "https://hub.test/api/v1/shared-hls?u=h.source&sid=lease-1&g=grant-1",
            playing.target,
        )
        assertTrue(playing.direct)
    }

    @Test
    fun hlsFromAnOlderServerNeverGetsSentToTheTsRelayOrClaimsSharing() = runTest {
        val api = FakePlaybackApi().apply {
            leaseResult = LEASE.copy(
                streamUrl = "/api/v1/stream?u=h.source&sid=lease-1&g=grant-1",
                sharedHlsUrl = null,
            )
        }
        val controller = controller(api)
        controller.start("content-1", CAPABILITIES)

        assertFalse(controller.sharesLiveRoomRead())
        controller.startMedia(inRoom = true)

        assertEquals(
            "https://hub.test/api/v1/stream?u=h.source&sid=lease-1&g=grant-1",
            (controller.state.value as HubPlaybackState.Playing).target,
        )
    }

    @Test
    fun readyCarriesTheServerBarrierGeneration() = runTest {
        val api = FakePlaybackApi()
        val controller = controller(api)
        controller.start("content-1", CAPABILITIES)

        controller.ready(9)

        assertEquals(listOf(9L), api.readyGenerations)
    }

    @Test
    fun remuxStartAndAudioTrackRerequestReplaceTheTarget() = runTest {
        val api = FakePlaybackApi().apply {
            remuxResults[0] = remux("remux-0", 0)
            remuxResults[2] = remux("remux-2", 2)
        }
        val controller = controller(api)

        controller.start("content-1", CAPABILITIES)
        controller.startMedia()
        val switched = controller.requestRemux(2)

        assertEquals(listOf(0, 2), api.remuxAudioRequests)
        assertEquals(2, switched?.audio)
        assertEquals(listOf("remux-0"), api.stoppedRemuxIds)
        assertEquals(
            HubPlaybackState.Playing(
                "https://hub.test/api/v1/remux/remux-2/master.m3u8?sid=lease-1&g=grant-1",
                direct = false,
                grant = "grant-1",
                audioTracks = listOf("English", "French"),
                selectedAudioTrackIndex = 2,
            ),
            controller.state.value,
        )
    }

    @Test
    fun fallingBackToDirectPlaybackStopsTheSupersededRemux() = runTest {
        val api = FakePlaybackApi().apply {
            remuxResults[0] = remux("remux-0", 0)
        }
        val controller = controller(api)
        controller.start("content-1", CAPABILITIES)
        controller.startMedia()

        controller.requestRemux(1)

        assertEquals(listOf("remux-0"), api.stoppedRemuxIds)
        assertTrue((controller.state.value as HubPlaybackState.Playing).direct)
    }

    @Test
    fun revocationDuringRemuxCleanupCannotBeOverwrittenByStalePlayback() = runTest {
        val cleanupMayFinish = CompletableDeferred<Unit>()
        val api = FakePlaybackApi().apply {
            remuxResults[0] = remux("remux-0", 0)
            stopRemuxGate = cleanupMayFinish
        }
        val controller = controller(api)
        controller.start("content-1", CAPABILITIES)
        controller.startMedia()

        val switch = launch { controller.requestRemux(1) }
        runCurrent()
        controller.onMediaRequestFailed(410)
        cleanupMayFinish.complete(Unit)
        switch.join()

        assertSame(HubPlaybackState.Revoked, controller.state.value)
    }

    @Test
    fun heartbeatRunsEveryThreeSecondsAndDrainsCommands() = runTest {
        val command = SessionCommandDto(type = "pause")
        val api = FakePlaybackApi().apply {
            heartbeatResponses += HeartbeatResponseDto(listOf(command))
        }
        val controller = controller(api)
        val received = mutableListOf<SessionCommandDto>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            controller.commands.take(1).toList(received)
        }

        controller.start("content-1", CAPABILITIES)
        runCurrent()
        assertEquals(1, api.heartbeats.size)
        assertEquals(listOf(command), received)

        advanceTimeBy(2_999)
        runCurrent()
        assertEquals(1, api.heartbeats.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, api.heartbeats.size)
    }

    @Test
    fun grantRotatesProactivelyAndMedia403RevokesInsteadOfLooping() = runTest {
        val api = FakePlaybackApi().apply {
            refreshedGrants += HubMediaGrant("grant-2", 1_140_000)
        }
        val controller = controller(api)
        controller.start("content-1", CAPABILITIES)
        controller.startMedia()

        advanceTimeBy(539_999)
        runCurrent()
        assertEquals(0, api.refreshCalls)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, api.refreshCalls)
        assertEquals("grant-2", controller.currentGrant())
        assertEquals("grant-2", (controller.state.value as HubPlaybackState.Playing).grant)

        controller.onMediaRequestFailed(403)
        assertEquals(1, api.refreshCalls)
        assertSame(HubPlaybackState.Revoked, controller.state.value)
    }

    @Test
    fun missingMediaNeverEndsTheLeaseButGoneMediaAlwaysDoes() = runTest {
        val api = FakePlaybackApi()
        val controller = controller(api)
        controller.start("content-1", CAPABILITIES)
        controller.startMedia()

        controller.onMediaRequestFailed(404)

        assertTrue(controller.state.value is HubPlaybackState.Playing)
        assertTrue(api.endedLeaseIds.isEmpty())

        controller.onMediaRequestFailed(410)

        assertSame(HubPlaybackState.Revoked, controller.state.value)
        assertEquals(listOf("lease-1"), api.endedLeaseIds)
    }

    @Test
    fun repeatedGrantRotationFailuresBecomeVisibleAndStopRetrying() = runTest {
        val api = FakePlaybackApi().apply {
            refreshFailure = IllegalStateException("offline")
            endSuspendsBeforeRequest = true
        }
        val controller = controller(api)
        controller.start("content-1", CAPABILITIES)

        advanceTimeBy(540_000)
        runCurrent()
        repeat(4) {
            advanceTimeBy(3_000)
            runCurrent()
        }

        assertEquals(5, api.refreshCalls)
        assertTrue(controller.state.value is HubPlaybackState.Failed)
        assertEquals(listOf("lease-1"), api.endedLeaseIds)
        val heartbeatsAtFailure = api.heartbeats.size
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(5, api.refreshCalls)
        assertEquals(heartbeatsAtFailure, api.heartbeats.size)
    }

    @Test
    fun goneIsTerminalAndStopsHeartbeatButNotFoundDoesNot() = runTest {
        val goneApi = FakePlaybackApi().apply {
            heartbeatFailure = HubGoneException("playback_revoked", "revoked")
        }
        val gone = controller(goneApi)
        gone.start("content-1", CAPABILITIES)
        runCurrent()

        assertSame(HubPlaybackState.Revoked, gone.state.value)
        assertEquals(1, goneApi.heartbeats.size)
        advanceTimeBy(12_000)
        runCurrent()
        assertEquals(1, goneApi.heartbeats.size)

        val missingApi = FakePlaybackApi().apply {
            heartbeatFailure = HubNotFoundException("not_found", "missing segment")
        }
        val missing = controller(missingApi)
        missing.start("content-1", CAPABILITIES)
        missing.startMedia()
        runCurrent()
        assertTrue(missing.state.value is HubPlaybackState.Playing)
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(2, missingApi.heartbeats.size)
        assertFalse(missing.state.value is HubPlaybackState.Revoked)
    }

    @Test
    fun capacityAndUnauthorizedHaveDistinctStates() = runTest {
        val capacityApi = FakePlaybackApi().apply {
            remuxFailure = HubCapacityException("provider_capacity", "full", 7_000)
        }
        val capacity = controller(capacityApi)
        capacity.start("content-1", CAPABILITIES)
        capacity.startMedia()
        assertEquals(HubPlaybackState.AtCapacity(7_000), capacity.state.value)
        runCurrent()
        val heartbeatsAtCapacity = capacityApi.heartbeats.size
        advanceTimeBy(6_000)
        runCurrent()
        assertEquals(heartbeatsAtCapacity, capacityApi.heartbeats.size)

        val unauthorizedApi = FakePlaybackApi().apply {
            createFailure = HubUnauthorizedException("unauthorized", "signed out")
        }
        val unauthorized = controller(unauthorizedApi)
        unauthorized.start("content-1", CAPABILITIES)
        assertSame(HubPlaybackState.SignedOut, unauthorized.state.value)
    }

    @Test
    fun capacityDuringLeaseCreationHasNoLeaseForIntentOrMedia() = runTest {
        val api = FakePlaybackApi().apply {
            createFailure = HubCapacityException("provider_capacity", "full", 7_000)
        }
        val controller = controller(api)

        val created = controller.start("content-1", CAPABILITIES)
        controller.startMedia()

        assertFalse(created)
        assertEquals(HubPlaybackState.AtCapacity(7_000), controller.state.value)
        assertEquals(null, controller.leaseId())
        assertTrue(api.remuxAudioRequests.isEmpty())
    }

    @Test
    fun capacityPreflightAfterLeaseCreationReleasesTheLeaseImmediately() = runTest {
        val api = FakePlaybackApi()
        val controller = controller(api)
        controller.start("content-1", CAPABILITIES)

        controller.providerAtCapacity()

        assertEquals(HubPlaybackState.AtCapacity(null), controller.state.value)
        assertEquals(listOf("lease-1"), api.endedLeaseIds)
        controller.stop()
        assertEquals(listOf("lease-1"), api.endedLeaseIds)
    }

    @Test
    fun stopRetriesLeaseReleaseWhenATerminalCleanupIsCancelled() = runTest {
        val firstEndMayFinish = CompletableDeferred<Unit>()
        val api = FakePlaybackApi().apply { endGate = firstEndMayFinish }
        val controller = controller(api)
        controller.start("content-1", CAPABILITIES)

        val capacity = launch { controller.providerAtCapacity() }
        runCurrent()
        val stop = launch { controller.stop() }
        runCurrent()
        capacity.cancelAndJoin()
        stop.join()

        assertEquals(listOf("lease-1", "lease-1"), api.endedLeaseIds)
    }

    @Test
    fun forbiddenHeartbeatRevokesTheLeaseEvenWithoutASocket() = runTest {
        val api = FakePlaybackApi().apply {
            heartbeatFailure = HubForbiddenException("forbidden", "access removed")
            endSuspendsBeforeRequest = true
        }
        val controller = controller(api)

        controller.start("content-1", CAPABILITIES)
        runCurrent()

        assertSame(HubPlaybackState.Revoked, controller.state.value)
        assertEquals(listOf("lease-1"), api.endedLeaseIds)
        advanceTimeBy(12_000)
        runCurrent()
        assertEquals(1, api.heartbeats.size)
    }

    @Test
    fun httpSyncFallbackPreservesCommandOrder() = runTest {
        val firstMayFinish = CompletableDeferred<Unit>()
        val api = FakePlaybackApi().apply {
            syncGate = firstMayFinish
        }
        val controller = controller(api)
        controller.start("content-1", CAPABILITIES)

        val first = launch {
            controller.sendSync(SyncStateDto(10_000, paused = false, seek = true))
        }
        runCurrent()
        val second = launch {
            controller.sendSync(SyncStateDto(20_000, paused = true, seek = true))
        }
        runCurrent()

        assertEquals(listOf(10_000L), api.syncs.map(SyncStateDto::positionMs))
        firstMayFinish.complete(Unit)
        first.join()
        second.join()
        assertEquals(listOf(10_000L, 20_000L), api.syncs.map(SyncStateDto::positionMs))
    }

    @Test
    fun catchupCreatesANewLeaseWithModeAndWindow() = runTest {
        val api = FakePlaybackApi()
        val controller = controller(api)

        controller.startCatchUp(
            contentId = "content-1",
            catchupStartMs = 1_000_000,
            catchupDurationMs = 3_600_000,
            capabilities = CAPABILITIES,
        )
        controller.startMedia()

        val request = api.createRequests.single()
        assertEquals("catchup", request.mode)
        assertEquals(1_000_000L, request.catchupStartMs)
        assertEquals(3_600_000L, request.catchupDurationMs)
        assertTrue(api.remuxTimeshiftRequests.single())
    }

    @Test
    fun stopIsIdempotentDeletesLeaseAndCancelsTimers() = runTest {
        val api = FakePlaybackApi()
        val controller = controller(api)
        controller.start("content-1", CAPABILITIES)
        runCurrent()
        val beatsBeforeStop = api.heartbeats.size

        controller.stop()
        controller.stop()
        assertEquals(listOf("lease-1"), api.endedLeaseIds)

        advanceTimeBy(600_000)
        runCurrent()
        assertEquals(beatsBeforeStop, api.heartbeats.size)
        assertEquals(0, api.refreshCalls)
    }

    @Test
    fun stopRacingLeaseCreationEndsTheLateLeaseWithoutStartingRuntime() = runTest {
        val createMayFinish = CompletableDeferred<Unit>()
        val api = FakePlaybackApi().apply {
            createGate = createMayFinish
        }
        val controller = controller(api)
        val start = launch { controller.start("content-1", CAPABILITIES) }
        runCurrent()

        controller.stop()
        createMayFinish.complete(Unit)
        start.join()
        runCurrent()

        assertEquals(listOf("lease-1"), api.endedLeaseIds)
        assertTrue(api.heartbeats.isEmpty())
        advanceTimeBy(600_000)
        runCurrent()
        assertEquals(0, api.refreshCalls)
    }

    @Test
    fun stopIsSafeWhenServerAlreadyRemovedTheLease() = runTest {
        val api = FakePlaybackApi().apply {
            endFailure = HubGoneException("playback_revoked", "already gone")
        }
        val controller = controller(api)
        controller.start("content-1", CAPABILITIES)

        controller.stop()

        assertEquals(listOf("lease-1"), api.endedLeaseIds)
    }

    private fun kotlinx.coroutines.test.TestScope.controller(api: FakePlaybackApi) =
        HubPlaybackController(
            api = api,
            clock = { testScheduler.currentTime },
            scope = backgroundScope,
            snapshotProvider = {
                HubPlaybackSnapshot(
                    title = "News",
                    positionMs = 12_000,
                    durationMs = 60_000,
                    live = true,
                    direct = true,
                )
            },
        )

    private class FakePlaybackApi : HubPlaybackApi {
        override val baseUrl = "https://hub.test"
        val createRequests = mutableListOf<PlaybackCreateRequest>()
        val heartbeats = mutableListOf<SessionHeartbeatDto>()
        val heartbeatResponses = ArrayDeque<HeartbeatResponseDto>()
        val remuxResults = mutableMapOf<Int, RemuxStartDto>()
        val remuxAudioRequests = mutableListOf<Int>()
        val remuxTimeshiftRequests = mutableListOf<Boolean>()
        val stoppedRemuxIds = mutableListOf<String>()
        val refreshedGrants = ArrayDeque<HubMediaGrant>()
        val endedLeaseIds = mutableListOf<String>()
        var refreshCalls = 0
        var createFailure: Throwable? = null
        var leaseResult: PlaybackLeaseDto = LEASE
        var createGate: CompletableDeferred<Unit>? = null
        var heartbeatFailure: Throwable? = null
        var remuxFailure: Throwable? = null
        var refreshFailure: Throwable? = null
        var endFailure: Throwable? = null
        var endGate: CompletableDeferred<Unit>? = null
        var endSuspendsBeforeRequest = false
        var stopRemuxGate: CompletableDeferred<Unit>? = null
        var syncGate: CompletableDeferred<Unit>? = null
        val syncs = mutableListOf<SyncStateDto>()
        val readyGenerations = mutableListOf<Long>()

        override suspend fun createLease(request: PlaybackCreateRequest): PlaybackLeaseDto {
            createRequests += request
            createGate?.await()
            createFailure?.let { throw it }
            return leaseResult
        }

        override suspend fun heartbeat(
            leaseId: String,
            heartbeat: SessionHeartbeatDto,
        ): HeartbeatResponseDto {
            heartbeats += heartbeat
            heartbeatFailure?.let { throw it }
            return heartbeatResponses.removeFirstOrNull() ?: HeartbeatResponseDto()
        }

        override suspend fun webSocketAccess(leaseId: String) =
            WebSocketAccessDto("ws-token", 30_000)

        override suspend fun sync(leaseId: String, state: SyncStateDto) {
            syncs += state
            syncGate?.await()
        }

        override suspend fun ready(leaseId: String, generation: Long) {
            readyGenerations += generation
        }

        override suspend fun refreshMediaGrant(leaseId: String): HubMediaGrant {
            refreshCalls++
            refreshFailure?.let { throw it }
            return refreshedGrants.removeFirstOrNull()
                ?: HubMediaGrant("grant-${refreshCalls + 1}", 600_000L * (refreshCalls + 1))
        }

        override suspend fun startRemux(
            startUrl: String,
            audioTrackIndex: Int,
            timeshift: Boolean,
            mediaGrant: String,
        ): RemuxStartDto {
            remuxAudioRequests += audioTrackIndex
            remuxTimeshiftRequests += timeshift
            remuxFailure?.let { throw it }
            return remuxResults[audioTrackIndex]
                ?: throw HubNotFoundException("no_extra_tracks", "direct play")
        }

        override suspend fun stopRemux(
            leaseId: String,
            remuxId: String,
            mediaGrant: String,
        ) {
            stoppedRemuxIds += remuxId
            stopRemuxGate?.await()
        }

        override suspend fun endLease(leaseId: String) {
            if (endSuspendsBeforeRequest) yield()
            endedLeaseIds += leaseId
            val gate = endGate
            endGate = null
            gate?.await()
            endFailure?.let { throw it }
        }
    }

    private companion object {
        val CAPABILITIES = ClientCapabilitiesDto(listOf("h264"), listOf("aac"))
        val LEASE = PlaybackLeaseDto(
            id = "lease-1",
            contentId = "content-1",
            playlistId = 7,
            mediaGrant = "grant-1",
            mediaGrantExpiresAtMs = 600_000,
            streamUrl = "/api/v1/stream?u=source&sid=lease-1&g=grant-1",
            sharedHlsUrl = null,
            relayUrl = "/api/v1/relay?u=source&sid=lease-1&g=grant-1",
            remuxStartUrl = "/api/v1/remux/start?u=source&sid=lease-1&g=grant-1",
        )

        fun remux(id: String, audio: Int) = RemuxStartDto(
            id = id,
            playlistUrl = "/api/v1/remux/$id/master.m3u8?sid=lease-1&g=grant-1",
            audioTracks = listOf("English", "French"),
            audio = audio,
        )
    }
}
