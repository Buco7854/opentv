package com.buco7854.opentv.server

import kotlin.math.ceil

/**
 * Builds the ffmpeg invocation that produces a session's fMP4 segments and sidecar subtitles.
 *
 * fMP4 for everything: it lets HEVC pass through, and keeps AAC signalled as real AAC-LC
 * instead of the HE-AAC hls.js forces for AAC in MPEG-TS.
 */
internal class RemuxCommandBuilder(
    /** Read per launch: an administrator can change the agent while sessions are prepared. */
    private val userAgent: () -> String,
    private val videoEncoder: String,
    private val x264Preset: String,
    private val ffmpeg: FfmpegSupport,
) {
    fun build(session: RemuxSession, startNumber: Int): List<String> = buildList {
        addAll(listOf("ffmpeg", "-nostdin", "-y", "-loglevel", "error"))
        if (session.url.startsWith("http")) {
            addAll(
                listOf(
                    "-user_agent", userAgent(),
                    "-reconnect", "1", "-reconnect_streamed", "1", "-reconnect_delay_max", "10",
                )
            )
        }
        // Read a little ahead of playback (input side) to bound disk and not hold the provider
        // connection far past what's watched; the initial burst fills hls.js's buffer at startup.
        if (!session.timeshift) addAll(ffmpeg.readrateArgs)
        addAll(seekArgs(session, startNumber))
        if (!session.transcodeVideo) addAll(listOf("-fflags", "+genpts"))
        addAll(listOf("-i", session.url, "-map", "0:v:0", "-map", "0:${session.audio.index}"))
        addAll(videoArgs(session))
        addAll(listOf("-af", audioFilters(session, startNumber).joinToString(",")))
        addAll(
            listOf(
                "-c:a", "aac", "-b:a", "192k",
                // Keep the source clock and zero the base so A/V/subtitles stay aligned across seeks.
                "-copyts", "-avoid_negative_ts", "disabled", "-start_at_zero",
                "-max_muxing_queue_size", "2048",
                "-f", "hls", "-max_delay", "5000000", "-hls_time", session.segLenSec.toString(),
                "-hls_playlist_type", "vod", "-hls_list_size", "0",
                "-start_number", startNumber.toString(), "-hls_flags", "temp_file",
            )
        )
        // The init name must be bare: ffmpeg resolves it against the playlist dir on Linux and the
        // CWD on Windows, both the session dir here (the launcher sets the CWD) - an absolute path
        // would break Linux.
        addAll(
            listOf(
                "-hls_segment_type", "fmp4", "-hls_segment_options", "movflags=+frag_discont",
                "-hls_fmp4_init_filename", "init.mp4",
                "-hls_segment_filename", session.dir.resolve("main%d.m4s").toString(),
            )
        )
        add(session.dir.resolve("ff.m3u8").toString())
        // Sidecar WebVTT per text sub, on the same zeroed clock as the video; flush per packet.
        session.subs.forEachIndexed { index, stream ->
            addAll(
                listOf(
                    "-map", "0:${stream.index}",
                    "-copyts", "-avoid_negative_ts", "disabled", "-start_at_zero",
                    "-flush_packets", "1",
                    "-f", "webvtt", session.dir.resolve("sub_$index.vtt").toString(),
                )
            )
        }
    }

    private fun seekArgs(session: RemuxSession, startNumber: Int): List<String> {
        // A timeshift only plays forward from the start: the provider serves it sequentially
        // and ffmpeg cannot -ss into it.
        if (session.timeshift || !restartedMidFile(session, startNumber)) return emptyList()
        val at = session.starts[startNumber]
        // Copied video can only start on a keyframe: the +0.5s nudge lands -ss on the boundary
        // keyframe, and noaccurate_seek keeps the re-encoded audio there too.
        return if (session.transcodeVideo) listOf("-ss", at.toString())
        else listOf("-noaccurate_seek", "-ss", (at + 0.5).toString())
    }

    private fun videoArgs(session: RemuxSession): List<String> = when {
        !session.transcodeVideo -> buildList {
            addAll(listOf("-c:v", "copy"))
            // Copied HEVC in fMP4 must be tagged hvc1, not hev1, or Safari/browsers refuse it.
            if (MediaCodecs.isHevc(session.videoCodec)) addAll(listOf("-tag:v", "hvc1"))
        }
        videoEncoder == "libx264" -> listOf(
            "-c:v", "libx264", "-preset", x264Preset, "-crf", "23", "-pix_fmt", "yuv420p",
            // Keyframe on every segment boundary so equal-length segments are exact.
            "-force_key_frames", "expr:gte(t,n_forced*${session.segLenSec})", "-sc_threshold", "0",
        )
        else -> listOf("-c:v", videoEncoder, "-g", ceil(session.segLenSec * 25).toInt().toString())
    }

    private fun audioFilters(session: RemuxSession, startNumber: Int): List<String> = buildList {
        // On a copied-video restart the audio starts a few frames before the boundary; drop those
        // so hls.js joins onto the previous run's tail with a gap, not an overlap that corrupts it.
        if (!session.transcodeVideo && restartedMidFile(session, startNumber)) {
            add("aselect=gte(t\\,${session.starts[startNumber]})")
        }
        // Downmix to stereo then cap the level: a loud 5.1 centre summed into L+R can clip; the
        // 0.85 ceiling leaves headroom for AAC decode overshoot and is transparent below it.
        add("aformat=channel_layouts=stereo")
        add("alimiter=limit=0.85:level=disabled")
    }

    /** This run picks the file up at a real segment boundary rather than at its start. */
    private fun restartedMidFile(session: RemuxSession, startNumber: Int): Boolean =
        startNumber > 0 && startNumber < session.starts.size
}
