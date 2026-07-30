package com.buco7854.opentv.hub

import android.content.Intent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The browser telling us a sign-in finished, so the waiting poll can ask immediately
 * instead of sitting out its interval.
 *
 * Deliberately carries no data. Any installed app can register `opentv://sign-in`, so the
 * server puts nothing secret in that redirect; it is a nudge, not a channel. The session is
 * still fetched with the poll token this process has held in memory since it started the
 * request, which an intercepting app does not have. Losing the nudge costs only latency,
 * because polling remains the actual mechanism.
 */
object BrowserSignInReturn {

    private val returns = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits once per return from the browser. */
    val events = returns.asSharedFlow()

    /** True when [intent] is the browser handing control back to us. */
    fun isSignInReturn(intent: Intent?): Boolean {
        val data = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data ?: return false
        return data.scheme == SCHEME && data.host == HOST
    }

    /** Wakes a waiting sign-in poll. No-op when nothing is waiting. */
    fun signal() {
        returns.tryEmit(Unit)
    }

    const val SCHEME = "opentv"
    const val HOST = "sign-in"
}
