package com.buco7854.opentv.ui.player

import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.common.C
import com.buco7854.opentv.contract.ClientCapabilitiesDto
import com.buco7854.opentv.contract.RemuxStartDto
import com.buco7854.opentv.contract.RoomMemberDto
import com.buco7854.opentv.contract.SessionCommandDto
import com.buco7854.opentv.contract.WatchIntentPeer
import com.buco7854.opentv.contract.WatchIntentResponse
import com.buco7854.opentv.core.model.Channel
import com.buco7854.opentv.core.repo.GuideEntry
import com.buco7854.opentv.data.prefs.PlayerSettings
import com.buco7854.opentv.data.prefs.SubtitleStyle
import com.buco7854.opentv.hub.ReportedCapabilities
import com.buco7854.opentv.hub.playback.HubPlaybackSnapshot
import com.buco7854.opentv.hub.playback.HubPlaybackState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `bootstrap restores settings resume channel and now-next data`() = runTest(dispatcher) {
        val source = FakePlayerDataSource()
        val viewModel = PlayerViewModel(source, LOCAL_TARGET)

        advanceUntilIdle()

        assertEquals(PlayerBootstrap(source.settings.value, RESUME_POSITION_MS), viewModel.bootstrap.value)
        assertSame(source.channel, viewModel.channel.value)
        assertEquals(source.nowNext, viewModel.nowNext.value)
        assertEquals(listOf(LOCAL_TARGET), source.resumeRequests)
    }

    @Test
    fun `guide and catch-up operations remain behind the data source`() = runTest(dispatcher) {
        val source = FakePlayerDataSource()
        val viewModel = PlayerViewModel(source, LOCAL_TARGET)
        advanceUntilIdle()

        viewModel.loadGuide()
        advanceUntilIdle()

        assertEquals(source.guide, viewModel.guideEntries.value)
        assertEquals(source.catchupTarget, viewModel.catchupTargetFor(source.guide.single()))
        assertEquals(1, source.guideRequests)

        viewModel.loadGuide()
        advanceUntilIdle()
        assertEquals(1, source.guideRequests)
    }

    @Test
    fun `preference updates are serialized without losing previous changes`() = runTest(dispatcher) {
        val source = FakePlayerDataSource()
        val viewModel = PlayerViewModel(source, LOCAL_TARGET)
        advanceUntilIdle()
        val subtitleStyle = SubtitleStyle(scale = 1.4f, background = true, bold = true)

        viewModel.saveResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM)
        viewModel.saveSubtitleStyle(subtitleStyle)
        advanceUntilIdle()

        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_ZOOM, source.settings.value.resizeMode)
        assertEquals(subtitleStyle, source.settings.value.subtitleStyle)
        assertEquals(2, source.savedSettings.size)
    }

    @Test
    fun `progress operations preserve the player target identity`() = runTest(dispatcher) {
        val source = FakePlayerDataSource()
        val viewModel = PlayerViewModel(source, LOCAL_TARGET)
        advanceUntilIdle()

        viewModel.saveProgress(positionMs = 42_000, durationMs = 120_000)
        viewModel.clearProgress()

        assertEquals(ProgressSave(LOCAL_TARGET, 42_000, 120_000), source.progressSave)
        assertEquals(listOf(LOCAL_TARGET), source.clearedProgress)
    }

    @Test
    fun `hub target creates a lease with reported capabilities`() = runTest(dispatcher) {
        val playback = FakeHubPlayback()
        val reported = ReportedCapabilities(listOf("h264", "hevc"), listOf("aac"))

        PlayerViewModel(
            source = FakePlayerDataSource(),
            target = HUB_TARGET,
            hubPlaybackFactory = { playback },
            capabilityReporter = { reported },
        )
        advanceUntilIdle()

        assertEquals(
            ContentStart(
                HUB_TARGET.contentId,
                ClientCapabilitiesDto(
                    reported.videoCodecs,
                    reported.audioCodecs,
                    selectsTracksInBand = true,
                ),
            ),
            playback.contentStart,
        )
        assertNull(playback.catchUpStart)
        assertEquals(listOf("lease", "intent", "media:solo"), playback.startupEvents)
    }

    @Test
    fun `same-content peer is discovered at capacity before media startup`() =
        runTest(dispatcher) {
            val playback = FakeHubPlayback().apply {
                intentResponse = WatchIntentResponse(
                    sameContent = listOf(WatchIntentPeer("peer-1", "Ari")),
                    full = true,
                    limit = 1,
                )
            }
            val viewModel = hubViewModel(playback)

            advanceUntilIdle()

            assertEquals(listOf("lease", "intent"), playback.startupEvents)
            assertTrue(playback.mediaStarts.isEmpty())
            assertTrue(viewModel.watchTogetherState.value.choosing)
            assertTrue(viewModel.watchTogetherState.value.blocked)
            assertNull(viewModel.problem.value)

            viewModel.askToJoin("peer-1")
            advanceUntilIdle()
            playback.emit(
                SessionCommandDto(
                    type = "room-state",
                    sequence = 1,
                    members = listOf(
                        RoomMemberDto("self", "Me", host = false, controller = false),
                        RoomMemberDto("peer-1", "Ari", host = true, controller = true),
                    ),
                ),
            )
            advanceUntilIdle()

            assertEquals(listOf("peer-1"), playback.joinedPeers)
            assertEquals(listOf(true), playback.mediaStarts)
            assertEquals("media:room", playback.startupEvents.last())
        }

    @Test
    fun `full provider without peers surfaces capacity without starting media`() =
        runTest(dispatcher) {
            val playback = FakeHubPlayback().apply {
                intentResponse = WatchIntentResponse(emptyList(), full = true, limit = 1)
            }
            val viewModel = hubViewModel(playback)

            advanceUntilIdle()

            assertEquals(listOf("lease", "intent", "capacity"), playback.startupEvents)
            assertTrue(playback.mediaStarts.isEmpty())
            assertEquals(PlayerProblem.AT_CAPACITY, viewModel.problem.value)
        }

    @Test
    fun `revoked playback maps to a stopping problem`() = runTest(dispatcher) {
        val playback = FakeHubPlayback()
        val viewModel = hubViewModel(playback)
        advanceUntilIdle()

        playback.mutableState.value = HubPlaybackState.Revoked
        advanceUntilIdle()

        assertEquals(PlayerProblem.PLAYBACK_ENDED, viewModel.problem.value)
    }

    @Test
    fun `capacity and signed out outcomes remain distinct`() = runTest(dispatcher) {
        val playback = FakeHubPlayback()
        val viewModel = hubViewModel(playback)
        advanceUntilIdle()

        playback.mutableState.value = HubPlaybackState.AtCapacity(4_000)
        advanceUntilIdle()
        assertEquals(PlayerProblem.AT_CAPACITY, viewModel.problem.value)

        playback.mutableState.value = HubPlaybackState.SignedOut
        advanceUntilIdle()
        assertEquals(PlayerProblem.SIGNED_OUT, viewModel.problem.value)
    }

    @Test
    fun `404 during hub playback does not stop or auto-retry the lease`() = runTest(dispatcher) {
        val playback = FakeHubPlayback()
        val viewModel = hubViewModel(playback)
        advanceUntilIdle()

        assertFalse(viewModel.onMediaRequestFailed(404))
        advanceUntilIdle()

        assertEquals(listOf(404), playback.mediaFailures)
        assertEquals(0, playback.stopCalls)
        assertNull(viewModel.problem.value)
    }

    @Test
    fun `capability discovery failure leaves Preparing with a visible error`() =
        runTest(dispatcher) {
            val viewModel = PlayerViewModel(
                source = FakePlayerDataSource(),
                target = HUB_TARGET,
                hubPlaybackFactory = { FakeHubPlayback() },
                capabilityReporter = { error("codec query failed") },
            )

            advanceUntilIdle()

            assertEquals(PlayerProblem.FAILED, viewModel.problem.value)
        }

    @Test
    fun `closing the player stops the hub lease exactly once`() = runTest(dispatcher) {
        val playback = FakeHubPlayback()
        val viewModel = hubViewModel(playback)
        advanceUntilIdle()

        viewModel.closePlayer()
        viewModel.closePlayer()
        advanceUntilIdle()

        assertEquals(1, playback.stopCalls)
    }

    @Test
    fun `closing before startup runs cannot create a late lease`() = runTest(dispatcher) {
        val playback = FakeHubPlayback()
        val viewModel = hubViewModel(playback)

        viewModel.closePlayer()
        advanceUntilIdle()

        assertEquals(1, playback.stopCalls)
        assertNull(playback.contentStart)
        assertTrue(playback.mediaStarts.isEmpty())
    }

    @Test
    fun `hub catch-up uses the catch-up lease entry point`() = runTest(dispatcher) {
        val playback = FakeHubPlayback()
        val target = PlayerTarget.HubCatchUp(
            hubId = HUB_ID,
            playlistId = PLAYLIST_ID,
            contentId = CONTENT_ID,
            title = "Replay",
            startMs = 12_000,
            durationMs = 30_000,
        )

        PlayerViewModel(
            source = FakePlayerDataSource(),
            target = target,
            hubPlaybackFactory = { playback },
            capabilityReporter = { ReportedCapabilities(emptyList(), emptyList()) },
        )
        advanceUntilIdle()

        assertEquals(
            CatchUpStart(
                CONTENT_ID,
                12_000,
                30_000,
                ClientCapabilitiesDto(selectsTracksInBand = true),
            ),
            playback.catchUpStart,
        )
        assertNull(playback.contentStart)
    }

    @Test
    fun `local target never creates or touches hub playback`() = runTest(dispatcher) {
        var factoryCalls = 0
        val playback = FakeHubPlayback()
        val viewModel = PlayerViewModel(
            source = FakePlayerDataSource(),
            target = LOCAL_TARGET,
            hubPlaybackFactory = {
                factoryCalls++
                playback
            },
        )
        advanceUntilIdle()

        assertEquals(0, factoryCalls)
        assertFalse(viewModel.onMediaRequestFailed(404))
        viewModel.closePlayer()
        advanceUntilIdle()
        assertEquals(0, playback.stopCalls)
        assertNull(playback.contentStart)
        assertFalse(viewModel.selectHubAudioTrack(1, 25_000))
        assertTrue(playback.remuxRequests.isEmpty())
    }

    @Test
    fun `hub playback exposes direct and remux track modes`() = runTest(dispatcher) {
        val playback = FakeHubPlayback()
        val viewModel = hubViewModel(playback)
        advanceUntilIdle()

        playback.mutableState.value = HubPlaybackState.Playing(
            target = "https://hub.test/direct",
            direct = true,
            grant = "grant",
        )
        advanceUntilIdle()
        assertTrue(viewModel.hubDirect.value)
        assertNull(viewModel.hubAudioTracks.value)

        playback.mutableState.value = HubPlaybackState.Playing(
            target = "https://hub.test/remux-0.m3u8",
            direct = false,
            grant = "grant",
            audioTracks = listOf("English", "Français"),
            selectedAudioTrackIndex = 0,
        )
        advanceUntilIdle()
        assertFalse(viewModel.hubDirect.value)
        assertEquals(
            HubAudioTracks(listOf("English", "Français"), 0),
            viewModel.hubAudioTracks.value,
        )
    }

    @Test
    fun `remux audio selection requests the index and carries position to replacement media`() =
        runTest(dispatcher) {
            val playback = FakeHubPlayback()
            val viewModel = hubViewModel(playback, HUB_VOD_TARGET)
            advanceUntilIdle()
            playback.mutableState.value = HubPlaybackState.Playing(
                target = "https://hub.test/remux-0.m3u8",
                direct = false,
                grant = "grant",
                audioTracks = listOf("English", "Français"),
                selectedAudioTrackIndex = 0,
            )
            advanceUntilIdle()
            viewModel.updatePlaybackSnapshot(
                PlaybackUiState(
                    playing = true,
                    buffering = false,
                    positionMs = 47_250,
                    durationMs = 120_000,
                    isLive = false,
                )
            )
            playback.remuxResult = RemuxStartDto(
                id = "remux-1",
                playlistUrl = "https://hub.test/remux-1.m3u8",
                audioTracks = listOf("English", "Français"),
                audio = 1,
            )

            assertTrue(viewModel.selectHubAudioTrack(1, 47_250))
            advanceUntilIdle()

            assertEquals(listOf(1), playback.remuxRequests)
            assertEquals(
                PlayerMediaSource("https://hub.test/remux-1.m3u8", 47_250),
                viewModel.playbackSource.value,
            )
            assertEquals(
                HubAudioTracks(listOf("English", "Français"), 1),
                viewModel.hubAudioTracks.value,
            )
        }

    @Test
    fun `audio replacement uses a seek made while the remux request is in flight`() =
        runTest(dispatcher) {
            val playback = FakeHubPlayback()
            val remuxMayFinish = CompletableDeferred<Unit>()
            playback.remuxGate = remuxMayFinish
            val viewModel = hubViewModel(playback, HUB_VOD_TARGET)
            val player = PositionOnlyPlayer(47_250)
            viewModel.attachWatchTogetherPlayer(player)
            advanceUntilIdle()
            playback.mutableState.value = HubPlaybackState.Playing(
                target = "https://hub.test/remux-0.m3u8",
                direct = false,
                grant = "grant",
                audioTracks = listOf("English", "Français"),
                selectedAudioTrackIndex = 0,
            )
            advanceUntilIdle()
            viewModel.updatePlaybackSnapshot(
                PlaybackUiState(
                    playing = true,
                    buffering = false,
                    positionMs = 47_250,
                    durationMs = 120_000,
                    isLive = false,
                ),
            )
            playback.remuxResult = RemuxStartDto(
                id = "remux-1",
                playlistUrl = "https://hub.test/remux-1.m3u8",
                audioTracks = listOf("English", "Français"),
                audio = 1,
            )

            assertTrue(viewModel.selectHubAudioTrack(1, 47_250))
            dispatcher.scheduler.runCurrent()
            player.positionMs = 63_000
            remuxMayFinish.complete(Unit)
            advanceUntilIdle()

            assertEquals(
                PlayerMediaSource("https://hub.test/remux-1.m3u8", 63_000),
                viewModel.playbackSource.value,
            )
        }

    @Test
    fun `switching a live direct source to the room relay starts at its live edge`() =
        runTest(dispatcher) {
            val playback = FakeHubPlayback()
            val viewModel = hubViewModel(playback)
            val player = PositionOnlyPlayer(47_250)
            viewModel.attachWatchTogetherPlayer(player)
            advanceUntilIdle()
            playback.mutableState.value = HubPlaybackState.Playing(
                target = "https://hub.test/direct",
                direct = true,
                grant = "grant",
            )
            advanceUntilIdle()

            playback.mutableState.value = HubPlaybackState.Playing(
                target = "https://hub.test/relay",
                direct = true,
                grant = "grant",
            )
            advanceUntilIdle()

            assertEquals(
                PlayerMediaSource("https://hub.test/relay", C.TIME_UNSET),
                viewModel.playbackSource.value,
            )
        }

    private class PositionOnlyPlayer(
        override var positionMs: Long,
    ) : WatchTogetherPlayer {
        override val events = emptyFlow<WatchTogetherPlaybackEvent>()
        override val paused = false
        override val playbackRate = 1.0
        override val isLive = false
        override fun play() = Unit
        override fun pause() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun setPlaybackRate(rate: Double) = Unit
    }

    private fun hubViewModel(
        playback: FakeHubPlayback,
        target: PlayerTarget = HUB_TARGET,
    ) = PlayerViewModel(
        source = FakePlayerDataSource(),
        target = target,
        hubPlaybackFactory = { playback },
        capabilityReporter = { ReportedCapabilities(emptyList(), emptyList()) },
    )

    private class FakePlayerDataSource : PlayerDataSource {
        override val settings = MutableStateFlow(PlayerSettings(seekSeconds = 20))
        val channel = Channel(
            playlistId = PLAYLIST_ID,
            name = "Channel",
            url = STREAM_URL,
            logo = null,
            groupTitle = "Group",
            tvgId = TVG_ID,
            kind = 0,
            seriesKey = null,
            season = null,
            episode = null,
            position = 0,
            xtreamStreamId = 7,
        )
        val nowNext = NowNextProgramme("Current", 60_000, "Next")
        val guide = listOf(GuideEntry("Programme", null, 1_000, 2_000, replayable = true))
        val catchupTarget = LOCAL_TARGET.copy(
            url = "https://provider.example/catchup",
            title = "Channel · Programme",
            live = false,
        )
        val resumeRequests = mutableListOf<PlayerTarget>()
        val savedSettings = mutableListOf<PlayerSettings>()
        val clearedProgress = mutableListOf<PlayerTarget>()
        var guideRequests = 0
        var progressSave: ProgressSave? = null

        override suspend fun channelFor(target: PlayerTarget): Channel = channel

        override suspend fun upcoming(target: PlayerTarget): NowNextProgramme = nowNext

        override suspend fun guideFor(target: PlayerTarget): List<GuideEntry> {
            guideRequests++
            return guide
        }

        override suspend fun catchupTargetFor(
            target: PlayerTarget,
            entry: GuideEntry,
        ): PlayerTarget = catchupTarget

        override suspend fun resumePositionFor(target: PlayerTarget): Long {
            resumeRequests += target
            return RESUME_POSITION_MS
        }

        override suspend fun saveSettings(settings: PlayerSettings) {
            savedSettings += settings
            this.settings.value = settings
        }

        override fun saveProgress(target: PlayerTarget, positionMs: Long, durationMs: Long) {
            progressSave = ProgressSave(target, positionMs, durationMs)
        }

        override fun clearProgress(target: PlayerTarget) {
            clearedProgress += target
        }
    }

    private class FakeHubPlayback : HubPlayerPlayback {
        val mutableState = MutableStateFlow<HubPlaybackState>(HubPlaybackState.Preparing)
        override val state: StateFlow<HubPlaybackState> = mutableState
        override val selfId: String = "self"
        private val mutableCommands = MutableSharedFlow<SessionCommandDto>(extraBufferCapacity = 8)
        override val commands = mutableCommands
        val mediaFailures = mutableListOf<Int>()
        val remuxRequests = mutableListOf<Int>()
        var remuxResult: RemuxStartDto? = null
        var remuxGate: CompletableDeferred<Unit>? = null
        var contentStart: ContentStart? = null
        var catchUpStart: CatchUpStart? = null
        var stopCalls = 0
        val liveRoomChanges = mutableListOf<Boolean>()
        var intentResponse: WatchIntentResponse? = WatchIntentResponse(emptyList(), false, 2)
        val startupEvents = mutableListOf<String>()
        val mediaStarts = mutableListOf<Boolean>()
        val joinedPeers = mutableListOf<String>()

        override suspend fun start(
            contentId: String,
            capabilities: ClientCapabilitiesDto,
        ): Boolean {
            contentStart = ContentStart(contentId, capabilities)
            startupEvents += "lease"
            mutableState.value = HubPlaybackState.LeaseCreated
            return true
        }

        override suspend fun startCatchUp(
            contentId: String,
            startMs: Long,
            durationMs: Long,
            capabilities: ClientCapabilitiesDto,
        ): Boolean {
            catchUpStart = CatchUpStart(contentId, startMs, durationMs, capabilities)
            startupEvents += "lease"
            mutableState.value = HubPlaybackState.LeaseCreated
            return true
        }

        override suspend fun retry(): Boolean {
            startupEvents += "lease"
            mutableState.value = HubPlaybackState.LeaseCreated
            return true
        }

        override suspend fun startMedia(inRoom: Boolean) {
            startupEvents += "media:${if (inRoom) "room" else "solo"}"
            mediaStarts += inRoom
        }

        override suspend fun providerAtCapacity() {
            startupEvents += "capacity"
            mutableState.value = HubPlaybackState.AtCapacity(null)
        }

        override suspend fun intent(): WatchIntentResponse? {
            startupEvents += "intent"
            return intentResponse
        }

        override suspend fun requestJoin(peerId: String) {
            joinedPeers += peerId
        }

        fun emit(command: SessionCommandDto) {
            mutableCommands.tryEmit(command)
        }

        override suspend fun requestRemux(audioTrackIndex: Int): RemuxStartDto? {
            remuxRequests += audioTrackIndex
            remuxGate?.await()
            return remuxResult?.also { result ->
                mutableState.value = HubPlaybackState.Playing(
                    target = result.playlistUrl,
                    direct = false,
                    grant = "grant",
                    audioTracks = result.audioTracks,
                    selectedAudioTrackIndex = result.audio,
                )
            }
        }

        override suspend fun onMediaRequestFailed(statusCode: Int) {
            mediaFailures += statusCode
        }

        override fun updateSnapshot(snapshot: HubPlaybackSnapshot) = Unit

        override fun currentGrant(): String? = "grant"

        override fun setLiveRoom(inRoom: Boolean): Boolean {
            liveRoomChanges += inRoom
            return true
        }

        override fun sharesLiveRoomRead(): Boolean = true

        override fun stop() {
            stopCalls++
        }
    }

    private data class ProgressSave(
        val target: PlayerTarget,
        val positionMs: Long,
        val durationMs: Long,
    )

    private data class ContentStart(
        val contentId: String,
        val capabilities: ClientCapabilitiesDto,
    )

    private data class CatchUpStart(
        val contentId: String,
        val startMs: Long,
        val durationMs: Long,
        val capabilities: ClientCapabilitiesDto,
    )

    private companion object {
        const val HUB_ID = 5L
        const val PLAYLIST_ID = 11L
        const val STREAM_URL = "https://provider.example/live"
        const val TVG_ID = "channel-id"
        const val CONTENT_ID = "stable-content"
        const val RESUME_POSITION_MS = 30_000L

        val LOCAL_TARGET = PlayerTarget.LocalUrl(
            STREAM_URL,
            "Channel",
            PLAYLIST_ID,
            TVG_ID,
            live = true,
        )
        val HUB_TARGET = PlayerTarget.HubContent(
            HUB_ID,
            PLAYLIST_ID,
            CONTENT_ID,
            "Hub channel",
            live = true,
        )
        val HUB_VOD_TARGET = HUB_TARGET.copy(live = false)
    }
}
