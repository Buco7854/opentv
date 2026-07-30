package com.buco7854.opentv.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.buco7854.opentv.OpenTvApp
import com.buco7854.opentv.data.prefs.PlayerSettings
import com.buco7854.opentv.diag.ErrorLog
import com.buco7854.opentv.download.DownloadRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal interface SettingsDataSource {
    val settings: Flow<PlayerSettings>

    suspend fun save(settings: PlayerSettings)
    suspend fun completedElsewhereCount(): Int
    suspend fun moveCompletedToCurrentFolder(): DownloadRepository.MoveResult
}

private class AppSettingsDataSource : SettingsDataSource {
    private val graph = OpenTvApp.graph

    override val settings: Flow<PlayerSettings> = graph.playerPrefs.settings

    override suspend fun save(settings: PlayerSettings) = graph.playerPrefs.save(settings)
    override suspend fun completedElsewhereCount() = graph.downloads.completedElsewhereCount()
    override suspend fun moveCompletedToCurrentFolder() =
        graph.downloads.moveCompletedToCurrentFolder()
}

internal data class MoveDownloadsState(
    val pending: Int = 0,
    val moving: Boolean = false,
    val result: DownloadRepository.MoveResult? = null,
)

class SettingsViewModel internal constructor(
    app: Application,
    private val source: SettingsDataSource,
) : AndroidViewModel(app) {
    constructor(app: Application) : this(app, AppSettingsDataSource())

    val settings: StateFlow<PlayerSettings?> = source.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _moveDownloads = MutableStateFlow(MoveDownloadsState())
    internal val moveDownloads: StateFlow<MoveDownloadsState> = _moveDownloads.asStateFlow()
    private val settingsMutex = Mutex()
    private var moveCountJob: Job? = null
    private var moveCountGeneration = 0L

    init {
        refreshMoveCount()
    }

    fun updateSettings(transform: PlayerSettings.() -> PlayerSettings) {
        viewModelScope.launch {
            settingsMutex.withLock {
                source.save(source.settings.first().transform())
            }
        }
    }

    fun refreshMoveCount() {
        val generation = ++moveCountGeneration
        moveCountJob?.cancel()
        moveCountJob = viewModelScope.launch {
            val pending = try {
                source.completedElsewhereCount()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                ErrorLog.log("Count downloads outside current folder", error)
                return@launch
            }
            if (generation == moveCountGeneration) {
                _moveDownloads.update { state ->
                    if (state.moving) state else state.copy(pending = pending)
                }
            }
        }
    }

    fun moveDownloads() {
        while (true) {
            val state = _moveDownloads.value
            if (state.moving) return
            if (_moveDownloads.compareAndSet(
                    state,
                    state.copy(moving = true, result = null),
                )
            ) break
        }
        moveCountGeneration++
        moveCountJob?.cancel()
        viewModelScope.launch {
            val result = try {
                source.moveCompletedToCurrentFolder()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                ErrorLog.log("Move downloads", error)
                DownloadRepository.MoveResult(
                    moved = 0,
                    alreadyThere = 0,
                    failed = _moveDownloads.value.pending.coerceAtLeast(1),
                )
            }
            val pending = try {
                source.completedElsewhereCount()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                ErrorLog.log("Count downloads outside current folder", error)
                _moveDownloads.value.pending
            }
            _moveDownloads.value = MoveDownloadsState(
                pending = pending,
                moving = false,
                result = result,
            )
        }
    }

    fun consumeMoveResult() {
        _moveDownloads.update { it.copy(result = null) }
    }
}
