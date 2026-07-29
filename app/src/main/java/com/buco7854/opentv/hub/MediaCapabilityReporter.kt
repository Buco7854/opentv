package com.buco7854.opentv.hub

import android.media.MediaCodecInfo
import android.media.MediaCodecList

/**
 * Reports what this device can decode, in the hub's vocabulary (ffprobe codec
 * names — the server whitelists them, see MediaCapabilities). The hub uses the
 * report to skip remux/transcode wherever the device direct-plays, so
 * under-reporting only costs server CPU while over-reporting breaks playback:
 * include a codec only when a decoder actually exists.
 */
data class ReportedCapabilities(
    val videoCodecs: List<String>,
    val audioCodecs: List<String>,
    val selectsTracksInBand: Boolean = true,
)

object MediaCapabilityReporter {

    @Volatile
    private var cached: ReportedCapabilities? = null

    fun report(): ReportedCapabilities = cached ?: build().also { cached = it }

    private fun build(): ReportedCapabilities {
        val mimes = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .asSequence()
            .filterNot(MediaCodecInfo::isEncoder)
            .flatMap { it.supportedTypes.asSequence() }
            .map { it.lowercase() }
            .toSet()
        return fromMimeTypes(mimes)
    }

    /** Pure mapping, separated so tests need no MediaCodecList. */
    fun fromMimeTypes(mimes: Set<String>): ReportedCapabilities = ReportedCapabilities(
        videoCodecs = VIDEO_MIME_TO_FFPROBE.mapNotNull { (mime, codec) -> codec.takeIf { mime in mimes } }
            .distinct()
            .sorted(),
        audioCodecs = AUDIO_MIME_TO_FFPROBE.mapNotNull { (mime, codec) -> codec.takeIf { mime in mimes } }
            .distinct()
            .sorted(),
    )

    private val VIDEO_MIME_TO_FFPROBE = mapOf(
        "video/avc" to "h264",
        "video/hevc" to "hevc",
        "video/av01" to "av1",
        "video/x-vnd.on2.vp8" to "vp8",
        "video/x-vnd.on2.vp9" to "vp9",
        "video/mpeg2" to "mpeg2video",
        "video/mp4v-es" to "mpeg4",
        "video/3gpp" to "h263",
    )

    private val AUDIO_MIME_TO_FFPROBE = mapOf(
        "audio/mp4a-latm" to "aac",
        "audio/mpeg" to "mp3",
        "audio/mpeg-l2" to "mp2",
        "audio/ac3" to "ac3",
        "audio/eac3" to "eac3",
        "audio/opus" to "opus",
        "audio/flac" to "flac",
        "audio/vorbis" to "vorbis",
        "audio/vnd.dts" to "dts",
        "audio/vnd.dts.hd" to "dts",
        "audio/true-hd" to "truehd",
        "audio/alac" to "alac",
        "audio/amr-wb" to "amr_wb",
        "audio/3gpp" to "amr_nb",
        // Every Android device has a PCM decoder; ffprobe names it per layout.
        "audio/raw" to "pcm_s16le",
    )
}
