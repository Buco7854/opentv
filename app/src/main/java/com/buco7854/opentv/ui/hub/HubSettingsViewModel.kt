package com.buco7854.opentv.ui.hub

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.buco7854.opentv.core.log.rethrowCancellation
import com.buco7854.opentv.core.model.HubSource
import com.buco7854.opentv.hub.HubUnauthorizedException
import com.buco7854.opentv.ui.home.CatalogSourceEntry
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class HubWorkspaceState(
    val source: HubSource? = null,
    val sourceLoaded: Boolean = false,
    val playlists: List<CatalogSourceEntry> = emptyList(),
    val loading: Boolean = true,
    val failed: Boolean = false,
)

/** A server workspace observes identity changes and owns its catalog refresh. */
internal class HubSettingsViewModel(
    private val hubId: Long,
    observeSources: Flow<List<HubSource>>,
    private val readSource: suspend () -> HubSource?,
    private val refreshIdentity: suspend () -> Unit,
    private val loadPlaylists: suspend () -> List<CatalogSourceEntry>,
) : ViewModel() {
    private val mutableState = MutableStateFlow(HubWorkspaceState())
    val state: StateFlow<HubWorkspaceState> = mutableState
    private var refreshJob: Job? = null

    init {
        viewModelScope.launch {
            observeSources.collect { sources ->
                val next = sources.firstOrNull { it.id == hubId }
                mutableState.update { current ->
                    current.copy(
                        source = next,
                        sourceLoaded = true,
                        playlists = if (current.source?.userId == next?.userId && next?.userId != null) current.playlists else emptyList(),
                    )
                }
            }
        }
        refresh()
    }

    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            mutableState.update { it.copy(loading = true, failed = false) }
            try {
                refreshIdentity()
                val current = readSource()
                mutableState.update { it.copy(source = current, sourceLoaded = true) }
                if (current?.userId == null) {
                    mutableState.update { it.copy(playlists = emptyList(), loading = false) }
                } else {
                    val rows = loadPlaylists()
                    // Never keep an old account's catalog after sign-out or reauthentication.
                    val latest = readSource()
                    mutableState.update {
                        if (latest?.userId == current.userId) it.copy(source = latest, playlists = rows, loading = false)
                        else it.copy(source = latest, playlists = emptyList(), loading = false)
                    }
                }
            } catch (_: HubUnauthorizedException) {
                val latest = readSource()
                mutableState.update { it.copy(source = latest, playlists = emptyList(), loading = false) }
            } catch (failure: Exception) {
                failure.rethrowCancellation()
                val latest = readSource()
                mutableState.update {
                    it.copy(source = latest, sourceLoaded = true, loading = false, failed = true,
                        playlists = if (latest?.userId != null && latest.userId == it.source?.userId) it.playlists else emptyList())
                }
            }
        }
    }
}
