package com.buco7854.opentv.diag

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.Date

/**
 * In-app diagnostics: a ring buffer of recent errors (message + full stack
 * trace) that the user can inspect and copy from the Error log screen, plus an
 * uncaught-exception hook that saves a crash report and surfaces it on the
 * next launch.
 *
 * Everything stored here is redacted first: playlist URLs carry the user's
 * provider credentials as query parameters, and users paste logs into forums
 * and support chats.
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

    // Credentials as query parameters: get.php?username=U&password=P, api_key=...
    private val REDACT_QUERY = Regex("""(?i)\b(username|password|token|pass|api_key)=[^&\s"'<>]+""")
    // Xtream path credentials: /live|movie(s)|series/USER/PASS/...
    private val REDACT_KIND_PATH = Regex("""(?i)/(live|movies?|series)/[^/\s"'<>]+/[^/\s"'<>]+/""")
    // Bare Xtream stream paths: http://host/USER/PASS/1234.ts
    private val REDACT_BARE_PATH =
        Regex("""(://[^/\s"'<>]+)/[^/\s"'<>]+/[^/\s"'<>]+/(\d+(?:\.\w{1,5})?)(?=[\s"'<>,)\]}]|$)""")

    fun redact(text: String): String {
        var result = REDACT_QUERY.replace(text) { "${it.groupValues[1]}=•••" }
        result = REDACT_BARE_PATH.replace(result) { "${it.groupValues[1]}/•••/•••/${it.groupValues[2]}" }
        result = REDACT_KIND_PATH.replace(result) { "/${it.groupValues[1]}/•••/•••/" }
        return result
    }

    /** One-line, redacted human description of a throwable, for snackbars. */
    fun describe(error: Throwable): String =
        redact(error.message ?: error.javaClass.simpleName)

    fun log(tag: String, error: Throwable? = null, message: String? = null) {
        val text = message ?: error?.let { describe(it) } ?: "Unknown error"
        add(tag, redact(text), error?.let { redact(it.stackTraceToString()) })
    }

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
                // Redacted at write time too: nothing credentialed ever rests on disk.
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
