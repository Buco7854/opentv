package com.buco7854.opentv.ui.account

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.buco7854.opentv.OpenTvApp
import com.buco7854.opentv.R
import com.buco7854.opentv.core.model.Playlist
import com.buco7854.opentv.core.repo.AccountInfoResult
import com.buco7854.opentv.core.xtream.AccountInfo
import com.buco7854.opentv.source.CatalogResult
import com.buco7854.opentv.source.ProviderAccountInfo
import com.buco7854.opentv.source.SourceId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AccountUiState(
    val playlist: Playlist? = null,
    /** Screen title; a server-hosted playlist has no local [Playlist] to take it from. */
    val title: String? = null,
    /** Which server a hosted playlist belongs to, shown under its name. */
    val serverName: String? = null,
    /** Whether there is a provider account API to ask at all. */
    val hasProviderAccount: Boolean = false,
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
    private val source: SourceId,
) : AndroidViewModel(app) {
    private val graph = OpenTvApp.graph
    private val _state = MutableStateFlow(AccountUiState())
    val state: StateFlow<AccountUiState> = _state

    init {
        viewModelScope.launch {
            when (source) {
                is SourceId.LocalPlaylist -> {
                    val playlist = graph.storage.playlists.get(source.playlistId)
                    _state.update {
                        it.copy(
                            playlist = playlist,
                            title = playlist?.name,
                            hasProviderAccount = playlist?.xtreamBase != null,
                            loading = false,
                        )
                    }
                }
                is SourceId.Hub -> {
                    // Name the playlist, as the local case does; the server is context under
                    // it. A bare server address says nothing about which of its playlists
                    // these connection figures belong to.
                    val hub = graph.storage.hubSources.get(source.hubId)
                    val title = runCatching {
                        graph.catalogFor(source).traits().title
                    }.getOrNull()
                    _state.update {
                        it.copy(
                            title = title ?: hub?.name,
                            serverName = hub?.name?.takeIf { _ -> title != null },
                            hasProviderAccount = true,
                            loading = false,
                        )
                    }
                }
                is SourceId.HubConnection -> _state.update { it.copy(loading = false) }
            }
            if (_state.value.hasProviderAccount) refresh(force = false)
        }
    }

    fun refresh(force: Boolean = true) {
        if (!_state.value.hasProviderAccount || _state.value.refreshing) return
        _state.update { it.copy(refreshing = true, error = null) }
        viewModelScope.launch {
            // Both sides answer the same question -- how many connections, until when -- so
            // the server-hosted case is mapped onto the same result the local one returns
            // and the screen never learns which kind of playlist it is showing.
            val result = when (source) {
                is SourceId.LocalPlaylist ->
                    graph.account.accountInfo(requireNotNull(_state.value.playlist), force)
                else -> graph.catalogFor(source).providerAccount(force).toAccountInfoResult()
            }
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

private fun CatalogResult<ProviderAccountInfo>.toAccountInfoResult(): AccountInfoResult =
    when (this) {
        is CatalogResult.Success -> {
            val info = AccountInfo(
                activeConnections = value.activeConnections,
                maxConnections = value.maxConnections,
                status = value.status,
                expiresAtMs = value.expiresAtMs,
                isTrial = value.isTrial,
                createdAtMs = value.createdAtMs,
                timezone = value.timezone,
            )
            if (value.stale) {
                AccountInfoResult.Stale(info, value.fetchedAtMs)
            } else {
                AccountInfoResult.Fresh(info, value.fetchedAtMs)
            }
        }
        else -> AccountInfoResult.Unavailable(null)
    }
