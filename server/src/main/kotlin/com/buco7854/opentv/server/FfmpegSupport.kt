package com.buco7854.opentv.server

import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/** What the installed ffmpeg can do. Probed lazily and shared by every ffmpeg-backed path. */
internal class FfmpegSupport(
    private val processRunner: MediaProcessRunner,
    private val clock: ServerClock = ServerClock.SYSTEM,
) {
    private val log = LoggerFactory.getLogger("opentv")
    private val detectionLock = Any()

    @Volatile
    private var detection: Detection? = null

    /** ffmpeg+ffprobe presence, re-checked periodically after a negative result. */
    val available: Boolean
        get() = detect().available

    /** `-readrate` (ffmpeg 5.0) and its initial burst (6.1) throttle the read; on older
     *  ffmpeg they're an unknown-option error, so gate them by version. */
    val readrateArgs: List<String>
        get() {
            val (major, minor) = detect().version
            return when {
                major > 6 || (major == 6 && minor >= 1) ->
                    listOf("-readrate", "1.5", "-readrate_initial_burst", "30")
                major >= 5 -> listOf("-readrate", "1.5")
                else -> emptyList()
            }
        }

    private fun detect(): Detection {
        detection?.let { if (it.available || clock.nowMs() - it.atMs < RECHECK_MS) return it }
        return synchronized(detectionLock) {
            detection?.let { if (it.available || clock.nowMs() - it.atMs < RECHECK_MS) return@synchronized it }
            // Capture the version from the availability invocation. Previously the first remux
            // ran `ffmpeg -version` twice (plus ffprobe and the real pipeline).
            val ffmpeg = checkFfmpeg()
            val available = ffmpeg.first && runs("ffprobe")
            Detection(available, clock.nowMs(), ffmpeg.second)
                .also { detection = it }
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
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(3, TimeUnit.SECONDS)
            false
        } else {
            process.exitValue() == 0
        }
    }.onFailure { log.warn("Could not run {}: {}", binary, it.message) }.getOrDefault(false)

    private fun checkFfmpeg(): Pair<Boolean, Pair<Int, Int>> = runCatching {
        val process = processRunner.start(
            MediaProcessRequest(listOf("ffmpeg", "-version"), mergeErrorIntoStdout = true)
        )
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(3, TimeUnit.SECONDS)
            return@runCatching false to (0 to 0)
        }
        val text = process.inputStream.bufferedReader().use { it.readText() }
        val version = VERSION.find(text)?.destructured
            ?.let { (major, minor) -> major.toInt() to minor.toInt() }
            ?: (0 to 0)
        (process.exitValue() == 0) to version
    }.onFailure {
        log.warn("Could not run ffmpeg: {}", it.message)
    }.getOrDefault(false to (0 to 0))

    private data class Detection(
        val available: Boolean,
        val atMs: Long,
        val version: Pair<Int, Int>,
    )

    private companion object {
        const val RECHECK_MS = 60_000L
        val VERSION = Regex("""version n?(\d+)\.(\d+)""")
    }
}
