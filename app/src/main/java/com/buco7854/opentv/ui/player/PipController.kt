package com.buco7854.opentv.ui.player

import kotlinx.coroutines.flow.MutableStateFlow

/** Bridges PiP between the Activity (system callbacks) and the player composable. */
object PipController {
    /** Set by the player while active; invoked by the Activity on user-leave to auto-enter PiP. */
    @Volatile
    var onUserLeave: (() -> Unit)? = null
        private set

    /**
     * Installs the current player callback and returns owner-aware cleanup. During an animated
     * player-to-player transition the old screen may dispose after the new screen has registered;
     * that old cleanup must not erase the new callback.
     */
    fun registerOnUserLeave(callback: () -> Unit): () -> Unit {
        synchronized(this) { onUserLeave = callback }
        return {
            synchronized(this) {
                if (onUserLeave === callback) onUserLeave = null
            }
        }
    }

    /** Current PiP mode, updated by the Activity; observed by the player to hide chrome. */
    val isInPip = MutableStateFlow(false)
}
