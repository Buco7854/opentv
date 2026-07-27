package com.buco7854.opentv.ui.account

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.buco7854.opentv.OpenTvApp
import com.buco7854.opentv.R
import com.buco7854.opentv.core.model.Playlist
import com.buco7854.opentv.core.xtream.AccountInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AccountUiState(
    val playlist: Playlist? = null,
    val info: AccountInfo? = null,
    val updatedAtMs: Long? = null,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
)

/**
 * Owns account loading and refresh policy so the composable only renders state.
 * The account repository remains responsible for provider caching/fallback.
 */
class AccountViewModel(
    app: Application,
    private val playlistId: Long,
) : AndroidViewModel(app) {
    private val graph = OpenTvApp.graph
    private val _state = MutableStateFlow(AccountUiState())
    val state: StateFlow<AccountUiState> = _state

    init {
        viewModelScope.launch {
            val playlist = graph.storage.playlists.get(playlistId)
            _state.update { it.copy(playlist = playlist, loading = false) }
            if (playlist?.xtreamBase != null) refresh(force = false)
        }
    }

    fun refresh(force: Boolean = true) {
        val playlist = _state.value.playlist ?: return
        if (_state.value.refreshing) return
        _state.update { it.copy(refreshing = true, error = null) }
        viewModelScope.launch {
            val info = graph.account.accountInfo(playlist, force)
            _state.update {
                if (info != null) {
                    it.copy(
                        info = info,
                        updatedAtMs = System.currentTimeMillis(),
                        refreshing = false,
                    )
                } else {
                    it.copy(
                        refreshing = false,
                        error = getApplication<Application>().getString(R.string.account_error),
                    )
                }
            }
        }
    }
}
