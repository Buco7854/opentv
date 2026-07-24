package com.buco7854.opentv.server

/** Injectable wall clock for state expiry and deterministic concurrency tests. */
fun interface ServerClock {
    fun nowMs(): Long

    companion object {
        val SYSTEM = ServerClock(System::currentTimeMillis)
    }
}
