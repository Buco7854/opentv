package com.buco7854.opentv.ui.settings

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.buco7854.opentv.data.prefs.PlayerSettings
import com.buco7854.opentv.download.DownloadRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.yield
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SettingsViewModelTest {
    @Test
    fun rapidPreferenceChangesPreserveBothEdits() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val source = FakeSettingsDataSource()
            val viewModel = SettingsViewModel(application(), source)

            viewModel.updateSettings { copy(themeMode = PlayerSettings.THEME_DARK) }
            viewModel.updateSettings { copy(seekSeconds = 30) }
            viewModel.updateSettings {
                copy(subtitleStyle = subtitleStyle.copy(scale = 1.4f))
            }
            viewModel.updateSettings {
                copy(subtitleStyle = subtitleStyle.copy(bold = true))
            }
            advanceUntilIdle()

            assertEquals(PlayerSettings.THEME_DARK, source.current.value.themeMode)
            assertEquals(30, source.current.value.seekSeconds)
            assertEquals(1.4f, source.current.value.subtitleStyle.scale)
            assertEquals(true, source.current.value.subtitleStyle.bold)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun rapidMoveTapsStartOnlyOneRelocation() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val source = FakeSettingsDataSource(pending = 2)
            val viewModel = SettingsViewModel(application(), source)
            runCurrent()

            viewModel.moveDownloads()
            viewModel.moveDownloads()
            runCurrent()

            assertEquals(1, source.moveCalls)
            source.finishMove.complete(Unit)
            advanceUntilIdle()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun staleCountRefreshCannotOverwriteTheCompletedMove() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val staleCount = CompletableDeferred<Int>()
            val source = FakeSettingsDataSource(pending = 2, firstCount = staleCount)
            val viewModel = SettingsViewModel(application(), source)
            runCurrent()

            viewModel.moveDownloads()
            runCurrent()
            source.finishMove.complete(Unit)
            runCurrent()
            staleCount.complete(2)
            advanceUntilIdle()

            assertEquals(0, viewModel.moveDownloads.value.pending)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun application(): Application = ApplicationProvider.getApplicationContext()
}

private class FakeSettingsDataSource(
    private var pending: Int = 0,
    private val firstCount: CompletableDeferred<Int>? = null,
) : SettingsDataSource {
    val current = MutableStateFlow(PlayerSettings())
    override val settings: Flow<PlayerSettings> = current
    val finishMove = CompletableDeferred<Unit>()
    var moveCalls = 0
    private var countCalls = 0

    override suspend fun save(settings: PlayerSettings) {
        // Let another submitted edit read the same prior snapshot.
        yield()
        current.value = settings
    }

    override suspend fun completedElsewhereCount(): Int {
        countCalls++
        return if (countCalls == 1 && firstCount != null) {
            withContext(NonCancellable) { firstCount.await() }
        } else {
            pending
        }
    }

    override suspend fun moveCompletedToCurrentFolder(): DownloadRepository.MoveResult {
        moveCalls++
        finishMove.await()
        pending = 0
        return DownloadRepository.MoveResult(moved = 2, alreadyThere = 0, failed = 0)
    }
}
