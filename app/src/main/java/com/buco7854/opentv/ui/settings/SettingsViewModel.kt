package com.buco7854.opentv.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.buco7854.opentv.OpenTvApp
import com.buco7854.opentv.data.prefs.PlayerSettings
import com.buco7854.opentv.download.DownloadRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal data class MoveDownloadsState(
    val pending: Int = 0,
    val moving: Boolean = false,
    val result: DownloadRepository.MoveResult? = null,
)

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = OpenTvApp.graph

    val settings: StateFlow<PlayerSettings?> = graph.playerPrefs.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _moveDownloads = MutableStateFlow(MoveDownloadsState())
    internal val moveDownloads: StateFlow<MoveDownloadsState> = _moveDownloads.asStateFlow()

    init {
        refreshMoveCount()
    }

    fun save(settings: PlayerSettings) {
        viewModelScope.launch {
            graph.playerPrefs.save(settings)
        }
    }

    fun refreshMoveCount() {
        viewModelScope.launch {
            _moveDownloads.value = _moveDownloads.value.copy(
                pending = graph.downloads.completedElsewhereCount(),
            )
        }
    }

    fun moveDownloads() {
        if (_moveDownloads.value.moving) return
        viewModelScope.launch {
            _moveDownloads.value = _moveDownloads.value.copy(moving = true, result = null)
            val result = graph.downloads.moveCompletedToCurrentFolder()
            _moveDownloads.value = MoveDownloadsState(
                pending = graph.downloads.completedElsewhereCount(),
                moving = false,
                result = result,
            )
        }
    }

    fun consumeMoveResult() {
        _moveDownloads.value = _moveDownloads.value.copy(result = null)
    }
}
