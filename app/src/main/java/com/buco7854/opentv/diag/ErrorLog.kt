package com.buco7854.opentv.diag

import android.content.Context
import com.buco7854.opentv.core.log.ProviderSecrets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.Date

/**
 * In-app diagnostics: ring buffer of recent errors plus a crash hook that surfaces on next launch.
 * Everything is redacted first, since playlist URLs carry credentials and users paste logs publicly.
 */
object ErrorLog {

    class Entry(
        val id: Long,
        val timeMs: Long,
        val tag: String,
        val message: String,
        val stackTrace: String?,
    )

    private const val MAX_ENTRIES = 200

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries

    private val nextId = java.util.concurrent.atomic.AtomicLong(1)

    /** Delegates to the shared policy so Android and the server mask the same things. */
    fun redact(text: String): String = ProviderSecrets.redact(text)

    /** One-line, redacted human description of a throwable, for snackbars. */
    fun describe(error: Throwable): String =
        redact(error.message ?: error.javaClass.simpleName)

    fun log(tag: String, error: Throwable? = null, message: String? = null) {
        // Scope teardown is not a failure.
        if (error is kotlinx.coroutines.CancellationException) return
        val text = message ?: error?.let { describe(it) } ?: "Unknown error"
        add(tag, redact(text), error?.let { redact(it.stackTraceToString()) })
    }

    @Synchronized
    fun clear() {
        _entries.value = emptyList()
    }

    /** Install the crash handler and surface any crash from the previous session. */
    fun install(context: Context) {
        val crashFile = File(context.filesDir, "last_crash.txt")
        if (crashFile.exists()) {
            runCatching { crashFile.readText() }.getOrNull()?.takeIf { it.isNotBlank() }?.let {
                add("Crash", "App crashed in the previous session", redact(it))
            }
            crashFile.delete()
        }
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                // Redacted at write time: nothing credentialed rests on disk.
                crashFile.writeText(redact("${Date()} · thread ${thread.name}\n${throwable.stackTraceToString()}"))
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    @Synchronized
    private fun add(tag: String, message: String, stackTrace: String?) {
        _entries.value =
            (listOf(Entry(nextId.getAndIncrement(), System.currentTimeMillis(), tag, message, stackTrace)) + _entries.value)
                .take(MAX_ENTRIES)
    }
}
