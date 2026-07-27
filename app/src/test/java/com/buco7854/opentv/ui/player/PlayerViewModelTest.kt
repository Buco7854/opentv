package com.buco7854.opentv.ui.player

import androidx.media3.ui.AspectRatioFrameLayout
import com.buco7854.opentv.core.model.Channel
import com.buco7854.opentv.core.repo.GuideEntry
import com.buco7854.opentv.data.prefs.PlayerSettings
import com.buco7854.opentv.data.prefs.SubtitleStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
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
        val viewModel = PlayerViewModel(
            source = source,
            url = STREAM_URL,
            playlistId = PLAYLIST_ID,
            tvgId = TVG_ID,
        )

        advanceUntilIdle()

        assertEquals(PlayerBootstrap(source.settings.value, RESUME_POSITION_MS), viewModel.bootstrap.value)
        assertSame(source.channel, viewModel.channel.value)
        assertEquals(source.nowNext, viewModel.nowNext.value)
        assertEquals(listOf(STREAM_URL), source.resumeRequests)
    }

    @Test
    fun `guide and catch-up operations remain behind the data source`() = runTest(dispatcher) {
        val source = FakePlayerDataSource()
        val viewModel = PlayerViewModel(source, STREAM_URL, PLAYLIST_ID, TVG_ID)
        advanceUntilIdle()

        viewModel.loadGuide()
        advanceUntilIdle()

        assertEquals(source.guide, viewModel.guideEntries.value)
        assertEquals(source.catchupUrl, viewModel.catchupUrlFor(source.guide.single()))
        assertEquals(1, source.guideRequests)

        viewModel.loadGuide()
        advanceUntilIdle()
        assertEquals(1, source.guideRequests)
    }

    @Test
    fun `preference updates are serialized without losing previous changes`() = runTest(dispatcher) {
        val source = FakePlayerDataSource()
        val viewModel = PlayerViewModel(source, STREAM_URL, PLAYLIST_ID, TVG_ID)
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
    fun `progress operations preserve the playing stream identity`() = runTest(dispatcher) {
        val source = FakePlayerDataSource()
        val viewModel = PlayerViewModel(source, STREAM_URL, PLAYLIST_ID, TVG_ID)
        advanceUntilIdle()

        viewModel.saveProgress(positionMs = 42_000, durationMs = 120_000)
        viewModel.clearProgress()

        assertEquals(ProgressSave(STREAM_URL, 42_000, 120_000), source.progressSave)
        assertEquals(listOf(STREAM_URL), source.clearedProgress)
    }

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
        val catchupUrl = "https://provider.example/catchup"
        val resumeRequests = mutableListOf<String>()
        val savedSettings = mutableListOf<PlayerSettings>()
        val clearedProgress = mutableListOf<String>()
        var guideRequests = 0
        var progressSave: ProgressSave? = null

        override suspend fun channelFor(playlistId: Long, url: String): Channel = channel

        override suspend fun upcoming(playlistId: Long, tvgId: String): NowNextProgramme = nowNext

        override suspend fun guideFor(channel: Channel): List<GuideEntry> {
            guideRequests++
            return guide
        }

        override suspend fun catchupUrlFor(channel: Channel, entry: GuideEntry): String = catchupUrl

        override suspend fun resumePositionFor(url: String): Long {
            resumeRequests += url
            return RESUME_POSITION_MS
        }

        override suspend fun saveSettings(settings: PlayerSettings) {
            savedSettings += settings
            this.settings.value = settings
        }

        override fun saveProgress(url: String, positionMs: Long, durationMs: Long) {
            progressSave = ProgressSave(url, positionMs, durationMs)
        }

        override fun clearProgress(url: String) {
            clearedProgress += url
        }
    }

    private data class ProgressSave(
        val url: String,
        val positionMs: Long,
        val durationMs: Long,
    )

    private companion object {
        const val PLAYLIST_ID = 11L
        const val STREAM_URL = "https://provider.example/live"
        const val TVG_ID = "channel-id"
        const val RESUME_POSITION_MS = 30_000L
    }
}
