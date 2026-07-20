package com.buco7854.opentv.core.log

import kotlin.coroutines.cancellation.CancellationException

/** Error-reporting seam (app: in-app log, server: slf4j). */
fun interface CoreLog {
    fun log(context: String, error: Throwable)
}

/** Call first in generic catch blocks: cancellation must propagate, not be swallowed. */
fun Throwable.rethrowCancellation() {
    if (this is CancellationException) throw this
}
