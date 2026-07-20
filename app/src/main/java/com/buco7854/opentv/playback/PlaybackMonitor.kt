package com.buco7854.opentv.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Tracks the host the player is streaming, so downloads avoid a second connection (exceeding the limit risks a blacklist). */
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
