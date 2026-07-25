package com.buco7854.opentv.server

import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/** What the installed ffmpeg can do. Probed lazily and shared by every ffmpeg-backed path. */
internal class FfmpegSupport(
    private val processRunner: MediaProcessRunner,
    private val clock: ServerClock = ServerClock.SYSTEM,
) {
    private val log = LoggerFactory.getLogger("opentv")

    @Volatile
    private var availableCheck: Pair<Boolean, Long>? = null

    /** ffmpeg+ffprobe presence, re-checked periodically after a negative result. */
    val available: Boolean
        get() {
            availableCheck?.let { (ok, atMs) ->
                if (ok || clock.nowMs() - atMs < RECHECK_MS) return ok
            }
            val ok = runs("ffmpeg") && runs("ffprobe")
            availableCheck = ok to clock.nowMs()
            return ok
        }

    /** `-readrate` (ffmpeg 5.0) and its initial burst (6.1) throttle the read; on older
     *  ffmpeg they're an unknown-option error, so gate them by version. */
    val readrateArgs: List<String> by lazy {
        val (major, minor) = version()
        when {
            major > 6 || (major == 6 && minor >= 1) ->
                listOf("-readrate", "1.5", "-readrate_initial_burst", "30")
            major >= 5 -> listOf("-readrate", "1.5")
            else -> emptyList()
        }
    }

    private fun runs(binary: String) = runCatching {
        val process = processRunner.start(
            MediaProcessRequest(
                listOf(binary, "-version"),
                discardStdout = true,
                discardStderr = true,
            )
        )
        process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0
    }.onFailure { log.warn("Could not run {}: {}", binary, it.message) }.getOrDefault(false)

    private fun version(): Pair<Int, Int> = runCatching {
        val process = processRunner.start(
            MediaProcessRequest(listOf("ffmpeg", "-version"), mergeErrorIntoStdout = true)
        )
        val text = process.inputStream.bufferedReader().readText()
        process.waitFor(10, TimeUnit.SECONDS)
        VERSION.find(text)?.destructured?.let { (major, minor) -> major.toInt() to minor.toInt() }
    }.getOrNull() ?: (0 to 0)

    private companion object {
        const val RECHECK_MS = 60_000L
        val VERSION = Regex("""version n?(\d+)\.(\d+)""")
    }
}
