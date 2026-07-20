package com.buco7854.opentv.download

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/** Counting gate with a per-acquisition limit, since the transfer limit is dynamic (provider max_connections). */
object DownloadGate {
    private val active = MutableStateFlow(0)

    suspend fun <T> withSlot(limit: Int, block: suspend () -> T): T {
        val cappedLimit = limit.coerceAtLeast(1)
        while (true) {
            val current = active.value
            if (current < cappedLimit) {
                if (active.compareAndSet(current, current + 1)) break
            } else {
                active.first { it < cappedLimit }
            }
        }
        try {
            return block()
        } finally {
            while (true) {
                val current = active.value
                if (active.compareAndSet(current, current - 1)) break
            }
        }
    }
}
