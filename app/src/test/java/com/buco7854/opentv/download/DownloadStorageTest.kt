package com.buco7854.opentv.download

import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadStorageTest {

    @Test
    fun relocationCopyStopsAtACoroutineCancellationBoundary() = runBlocking {
        val readStarted = CompletableDeferred<Unit>()
        val writeStarted = CompletableDeferred<Unit>()
        val writes = AtomicInteger()
        val input = object : InputStream() {
            override fun read(): Int = 0

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                readStarted.complete(Unit)
                buffer[offset] = 1
                return 1
            }
        }
        val sink = object : DownloadStorage.Sink {
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                writes.incrementAndGet()
                writeStarted.complete(Unit)
            }

            override fun close() = Unit
        }

        val copy = launch(Dispatchers.Default) {
            DownloadStorage.copyForRelocation(input, sink)
        }
        readStarted.await()
        writeStarted.await()
        withTimeout(2_000) {
            copy.cancelAndJoin()
        }

        assertTrue(copy.isCancelled)
        assertTrue(writes.get() > 0)
    }
}
