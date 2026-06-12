package com.buco7854.opentv.ui.home

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.buco7854.opentv.OpenTvApp
import com.buco7854.opentv.data.db.PlaylistEntity
import com.buco7854.opentv.data.xtream.AccountInfo
import com.buco7854.opentv.diag.ErrorLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val graph = OpenTvApp.graph

    val playlists: StateFlow<List<PlaylistEntity>> = graph.playlists.playlists
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _accounts = MutableStateFlow<Map<Long, AccountInfo>>(emptyMap())
    val accounts: StateFlow<Map<Long, AccountInfo>> = _accounts

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun consumeMessage() {
        _message.value = null
    }

    /** Fetch connection status for Xtream playlists; AccountRepository caches for 60s. */
    fun loadAccountInfo(playlists: List<PlaylistEntity>) {
        viewModelScope.launch {
            for (playlist in playlists) {
                if (playlist.xtreamBase == null) continue
                graph.account.accountInfo(playlist)?.let { info ->
                    _accounts.value = _accounts.value + (playlist.id to info)
                }
            }
        }
    }

    fun addFromUrl(name: String, url: String, epgUrl: String) {
        viewModelScope.launch {
            _busy.value = true
            try {
                graph.playlists.addFromUrl(name, url, epgUrl)
                _message.value = "Playlist added"
            } catch (e: Exception) {
                ErrorLog.log("Add playlist", e)
                _message.value = "Failed to load playlist: ${ErrorLog.describe(e)}"
            } finally {
                _busy.value = false
            }
        }
    }

    fun addFromFile(name: String, uri: Uri) {
        viewModelScope.launch {
            _busy.value = true
            try {
                graph.playlists.addFromFile(name, uri, getApplication<Application>().contentResolver)
                _message.value = "Playlist imported"
            } catch (e: Exception) {
                ErrorLog.log("Import playlist", e)
                _message.value = "Import failed: ${ErrorLog.describe(e)}"
            } finally {
                _busy.value = false
            }
        }
    }

    fun refresh(playlistId: Long) {
        viewModelScope.launch {
            _busy.value = true
            try {
                graph.playlists.refresh(playlistId, force = true)
                _message.value = "Playlist refreshed"
            } catch (e: Exception) {
                ErrorLog.log("Playlist refresh", e)
                _message.value = "Refresh failed: ${ErrorLog.describe(e)}"
            } finally {
                _busy.value = false
            }
        }
    }

    fun delete(playlistId: Long) {
        viewModelScope.launch { graph.playlists.delete(playlistId) }
    }
}
