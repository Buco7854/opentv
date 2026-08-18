package com.buco7854.opentv.ui.player

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PlayerProgressWriteQueueTest {
    @Test
    fun `final save waits for an older periodic save instead of being overwritten by it`() =
        runTest {
            val owner = SupervisorJob()
            val scope = CoroutineScope(owner + StandardTestDispatcher(testScheduler))
            val queue = PlayerProgressWriteQueue(scope)
            val olderMayFinish = CompletableDeferred<Unit>()
            val writes = mutableListOf<String>()

            queue.enqueue {
                writes += "periodic-start"
                olderMayFinish.await()
                writes += "periodic-finish"
            }
            queue.enqueue { writes += "final" }

            // enqueue is nonblocking, but the final write cannot pass the older write.
            runCurrent()
            assertEquals(listOf("periodic-start"), writes)

            olderMayFinish.complete(Unit)
            runCurrent()

            assertEquals(
                listOf("periodic-start", "periodic-finish", "final"),
                writes,
            )
            assertFalse(owner.children.any())
            scope.cancel()
        }
}
