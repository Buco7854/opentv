package com.buco7854.opentv.server

import java.nio.file.Path

/**
 * One prepared VOD HLS session: the playlist shape it published, the ffmpeg run currently
 * producing segments for it, and the provider connection that run holds.
 */
internal class RemuxSession(
    val id: String,
    val dir: Path,
    val url: String,
    // Groups sessions that share one provider's connection allowance.
    val providerKey: String,
    // The share group holding this session's one provider connection: a lone viewer's own
    // session id, or a watch-together room id shared by all its members (so a synced room
    // reads the file once). Two sessions with the same shareKey never double-count a seat.
    val shareKey: String,
    // How many reads that provider permits at once (its max_connections).
    val connectionLimit: Int,
    val audioIndex: Int,
    val durationSec: Double,
    // Target segment length (ffmpeg -hls_time). Actual boundaries are in `starts`.
    val segLenSec: Double,
    // Start time (s) of each segment. For copied video these fall on real keyframes so
    // the playlist's durations match the media exactly; uniform otherwise.
    val starts: List<Double>,
    // A catch-up timeshift: the provider serves it sequentially and ffmpeg can't -ss
    // into it, so it's produced from the start with no restart/prune/read throttle.
    val timeshift: Boolean,
    val transcodeVideo: Boolean,
    val videoCodec: String,
    val audio: MediaStreamInfo,
    val subs: List<MediaStreamInfo>,
    val audioLabels: List<String>,
    val subLabels: List<String>,
    val nativeVideoCopy: Boolean,
    @Volatile var lastAccessMs: Long,
    // The running ffmpeg and the segment index it was started at (-1 = none).
    @Volatile var process: Process? = null,
    @Volatile var startNumber: Int = -1,
    // Highest segment index this run has written. Only moves forward within a run (reset
    // on each launch), so pruning segments behind the playhead can't drag it back.
    @Volatile var writtenHead: Int = -1,
) {
    val playlistFile: Path get() = dir.resolve("main.m3u8")
    val initFile: Path get() = dir.resolve("init.mp4")
    val logFile: Path get() = dir.resolve("ffmpeg.log")

    fun segmentFile(n: Int): Path = dir.resolve("main$n.m4s")

    /** Real length of each segment, from the boundaries the playlist was built on. */
    fun segmentLengths(): List<Double> = starts.indices.map { n ->
        ((if (n + 1 < starts.size) starts[n + 1] else durationSec) - starts[n]).coerceAtLeast(0.001)
    }

    /** The media-time window segment [n] covers. */
    fun segmentWindow(n: Int): ClosedFloatingPointRange<Double> {
        val from = starts.getOrElse(n) { durationSec }
        val to = if (n + 1 < starts.size) starts[n + 1] else durationSec
        return from..to
    }
}
