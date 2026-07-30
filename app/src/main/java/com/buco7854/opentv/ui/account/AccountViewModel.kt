package com.buco7854.opentv.ui.account

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.buco7854.opentv.OpenTvApp
import com.buco7854.opentv.R
import com.buco7854.opentv.core.model.Playlist
import com.buco7854.opentv.core.repo.AccountInfoResult
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

internal fun AccountUiState.withAccountInfo(
    result: AccountInfoResult,
    unavailableError: String,
    staleError: String,
): AccountUiState = when (result) {
    is AccountInfoResult.Fresh -> copy(
        info = result.info,
        updatedAtMs = result.fetchedAtMs,
        refreshing = false,
        error = null,
    )
    is AccountInfoResult.Stale -> copy(
        info = result.info,
        updatedAtMs = result.fetchedAtMs,
        refreshing = false,
        error = staleError,
    )
    is AccountInfoResult.Unavailable -> copy(
        refreshing = false,
        error = unavailableError,
    )
}

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
            val result = graph.account.accountInfo(playlist, force)
            _state.update {
                it.withAccountInfo(
                    result = result,
                    unavailableError = getApplication<Application>().getString(R.string.account_error),
                    staleError = getApplication<Application>().getString(R.string.account_stale_error),
                )
            }
        }
    }
}
