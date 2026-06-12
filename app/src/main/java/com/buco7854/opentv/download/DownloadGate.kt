package com.buco7854.opentv.download

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/**
 * Counting gate with a per-acquisition limit, since the allowed number of
 * simultaneous transfers is dynamic (it can come from the provider's
 * max_connections). Workers queue here until a slot frees up.
 */
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
