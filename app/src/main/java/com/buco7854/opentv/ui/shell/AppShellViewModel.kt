package com.buco7854.opentv.ui.shell

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.buco7854.opentv.OpenTvApp
import com.buco7854.opentv.data.prefs.PlayerSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AppShellViewModel(app: Application) : AndroidViewModel(app) {
    private val preferences = OpenTvApp.graph.playerPrefs

    val settings: StateFlow<PlayerSettings?> = preferences.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setActivePlaylist(playlistId: Long) {
        viewModelScope.launch {
            val current = preferences.settings.first()
            if (current.activePlaylistId != playlistId) {
                preferences.save(current.copy(activePlaylistId = playlistId))
            }
        }
    }
}
