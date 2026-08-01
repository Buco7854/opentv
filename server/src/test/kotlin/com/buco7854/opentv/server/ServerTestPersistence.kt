package com.buco7854.opentv.server

import com.buco7854.opentv.core.storage.Storage
import com.buco7854.opentv.serverdata.createOpenTvServerStorage
import com.buco7854.opentv.serverdata.db.OpenTvServerDatabase
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/** Owns one test database and everything that must stop before that database closes. */
internal class ServerTestPersistence(prefix: String) : AutoCloseable {
    val directory: Path = Files.createTempDirectory(prefix)

    private val persistence = createOpenTvServerStorage(
        directory.resolve("opentv.db").toString(),
    )
    val database: OpenTvServerDatabase = persistence.database
    val storage: Storage = persistence.catalog

    private val beforeDatabaseClose = ArrayDeque<() -> Unit>()
    private val closed = AtomicBoolean()

    /** Registers an owner such as a service or coroutine scope before it can use the database. */
    fun closeBeforeDatabase(action: () -> Unit) {
        check(!closed.get()) { "Test persistence is already closed" }
        beforeDatabaseClose.addFirst(action)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        var failure: Throwable? = null
        fun closing(action: () -> Unit) {
            try {
                action()
            } catch (error: Throwable) {
                failure?.addSuppressed(error) ?: run { failure = error }
            }
        }

        beforeDatabaseClose.forEach { closing(it) }
        closing(storage::close)
        closing { directory.toFile().deleteRecursively() }
        failure?.let { throw it }
    }
}
