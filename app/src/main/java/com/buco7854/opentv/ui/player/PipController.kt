package com.buco7854.opentv.ui.player

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Bridges Picture-in-Picture between the single Activity (which receives the
 * system callbacks) and the player composable (which knows when PiP applies).
 * The player registers [onUserLeave] while it is on screen; the Activity calls
 * it from onUserLeaveHint to auto-enter PiP when the user presses Home.
 */
object PipController {
    /** Set by the player while active; invoked by the Activity on user-leave. */
    @Volatile
    var onUserLeave: (() -> Unit)? = null

    /** Current PiP mode, updated by the Activity; observed by the player to hide chrome. */
    val isInPip = MutableStateFlow(false)
}
