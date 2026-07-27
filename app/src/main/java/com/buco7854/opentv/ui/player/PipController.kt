package com.buco7854.opentv.ui.player

import kotlinx.coroutines.flow.MutableStateFlow

/** Bridges PiP between the Activity (system callbacks) and the player composable. */
object PipController {
    /** Set by the player while active; invoked by the Activity on user-leave to auto-enter PiP. */
    @Volatile
    var onUserLeave: (() -> Unit)? = null

    /** Current PiP mode, updated by the Activity; observed by the player to hide chrome. */
    val isInPip = MutableStateFlow(false)
}
