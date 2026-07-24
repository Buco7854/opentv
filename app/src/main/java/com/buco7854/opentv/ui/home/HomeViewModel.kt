package com.buco7854.opentv.ui.home

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.buco7854.opentv.core.log.rethrowCancellation
import com.buco7854.opentv.OpenTvApp
import com.buco7854.opentv.R
import com.buco7854.opentv.core.model.Playlist
import com.buco7854.opentv.data.prefs.PlayerSettings
import com.buco7854.opentv.diag.ErrorLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val graph = OpenTvApp.graph

    private fun str(resId: Int, vararg args: Any) =
        getApplication<Application>().getString(resId, *args)

    private suspend fun <T> importLines(uri: Uri, block: suspend (Sequence<String>) -> T): T {
        val stream = getApplication<Application>().contentResolver.openInputStream(uri)
            ?: throw java.io.IOException("Could not open the selected file")
        return stream.bufferedReader().use { block(it.lineSequence()) }
    }

    // Nullable presentation types preserve an explicit loading state before
    // either cold persistence flow has emitted its first value.
    val playlists: Flow<List<Playlist>?> = graph.playlists.playlists
    val settings: Flow<PlayerSettings?> = graph.playerPrefs.settings

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun consumeMessage() {
        _message.value = null
    }

    fun addXtream(name: String, server: String, username: String, password: String) {
        viewModelScope.launch {
            _busy.value = true
            try {
                graph.playlists.addFromXtream(name, server, username, password)
                _message.value = str(R.string.playlist_xtream_added)
            } catch (e: Exception) {
                e.rethrowCancellation()
                ErrorLog.log("Xtream login", e)
                _message.value = str(R.string.playlist_xtream_failed, ErrorLog.describe(e))
            } finally {
                _busy.value = false
            }
        }
    }

    fun addFromUrl(name: String, url: String, epgUrl: String) {
        viewModelScope.launch {
            _busy.value = true
            try {
                graph.playlists.addFromUrl(name, url, epgUrl)
                _message.value = str(R.string.playlist_added)
            } catch (e: Exception) {
                e.rethrowCancellation()
                ErrorLog.log("Add playlist", e)
                _message.value = str(R.string.playlist_add_failed, ErrorLog.describe(e))
            } finally {
                _busy.value = false
            }
        }
    }

    fun addFromFile(name: String, uri: Uri) {
        viewModelScope.launch {
            _busy.value = true
            try {
                importLines(uri) { graph.playlists.importFromLines(name, it) }
                _message.value = str(R.string.playlist_imported)
            } catch (e: Exception) {
                e.rethrowCancellation()
                ErrorLog.log("Import playlist", e)
                _message.value = str(R.string.playlist_import_failed, ErrorLog.describe(e))
            } finally {
                _busy.value = false
            }
        }
    }

    fun editXtream(id: Long, name: String, server: String, username: String, password: String) {
        viewModelScope.launch {
            _busy.value = true
            try {
                graph.playlists.updateXtream(id, name, server, username, password)
                _message.value = str(R.string.playlist_updated)
            } catch (e: Exception) {
                e.rethrowCancellation()
                ErrorLog.log("Edit Xtream", e)
                _message.value = str(R.string.playlist_update_failed, ErrorLog.describe(e))
            } finally {
                _busy.value = false
            }
        }
    }

    fun editUrl(id: Long, name: String, url: String, epgUrl: String) {
        viewModelScope.launch {
            _busy.value = true
            try {
                graph.playlists.updateUrl(id, name, url, epgUrl)
                _message.value = str(R.string.playlist_updated)
            } catch (e: Exception) {
                e.rethrowCancellation()
                ErrorLog.log("Edit playlist", e)
                _message.value = str(R.string.playlist_update_failed, ErrorLog.describe(e))
            } finally {
                _busy.value = false
            }
        }
    }

    fun rename(id: Long, name: String) {
        viewModelScope.launch {
            try {
                graph.playlists.rename(id, name)
                _message.value = str(R.string.playlist_renamed)
            } catch (e: Exception) {
                e.rethrowCancellation()
                ErrorLog.log("Rename playlist", e)
                _message.value = str(R.string.playlist_rename_failed, ErrorLog.describe(e))
            }
        }
    }

    fun replaceFile(id: Long, name: String, uri: Uri) {
        viewModelScope.launch {
            _busy.value = true
            try {
                importLines(uri) { graph.playlists.replaceFromLines(id, name, it) }
                _message.value = str(R.string.playlist_replaced)
            } catch (e: Exception) {
                e.rethrowCancellation()
                ErrorLog.log("Replace playlist file", e)
                _message.value = str(R.string.playlist_replace_failed, ErrorLog.describe(e))
            } finally {
                _busy.value = false
            }
        }
    }

    /** Refresh one playlist's M3U list and EPG (not account status). */
    fun refresh(playlistId: Long) {
        viewModelScope.launch {
            _busy.value = true
            try {
                graph.playlists.refresh(playlistId, force = true)
                runCatching { graph.epg.refresh(playlistId, force = true) }
                    .onFailure { it.rethrowCancellation(); ErrorLog.log("EPG refresh", it) }
                _message.value = str(R.string.playlist_refreshed)
            } catch (e: Exception) {
                e.rethrowCancellation()
                ErrorLog.log("Playlist refresh", e)
                _message.value = str(R.string.playlist_refresh_failed, ErrorLog.describe(e))
            } finally {
                _busy.value = false
            }
        }
    }

    fun delete(playlistId: Long) {
        viewModelScope.launch { graph.playlists.delete(playlistId) }
    }

    /** Wipes saved watch progress for this playlist's channels (only). */
    fun clearProgress(playlistId: Long) {
        viewModelScope.launch {
            graph.resume.clearForPlaylist(playlistId)
            _message.value = str(R.string.playlist_progress_cleared)
        }
    }
}
