package com.buco7854.opentv.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Tracks which provider host (if any) is currently being streamed by the
 * player, so the download worker can avoid opening a second connection to the
 * same provider - exceeding the account's concurrent-connection limit is the
 * classic way to get blacklisted.
 */
object PlaybackMonitor {
    private val _activeHost = MutableStateFlow<String?>(null)
    val activeHost: StateFlow<String?> = _activeHost

    fun playbackStarted(url: String) {
        _activeHost.value = url.toHttpUrlOrNull()?.host
    }

    fun playbackStopped() {
        _activeHost.value = null
    }
}
