package com.buco7854.opentv.server

import com.buco7854.opentv.contract.*
import java.security.MessageDigest

/** The codecs a playback lease says it can decode without server-side normalization. */
internal data class MediaCapabilities(
    val video: Set<String>,
    val audio: Set<String>,
    val selectsTracksInBand: Boolean = false,
) {
    /** Null means there is no such stream, so there is nothing that needs normalizing. */
    fun videoDecodable(codec: String?): Boolean =
        codec == null || codec.trim().lowercase() in video

    fun audioDecodable(codec: String?): Boolean =
        codec == null || codec.trim().lowercase() in audio

    fun intersect(other: MediaCapabilities) = MediaCapabilities(
        video = video intersect other.video,
        audio = audio intersect other.audio,
        selectsTracksInBand = selectsTracksInBand && other.selectsTracksInBand,
    )

    /** Stable identity used only to keep unlike remux pipelines from sharing artifacts. */
    val fingerprint: String
        get() = shortSha1(
            "v:${video.map(String::lowercase).sorted().joinToString(",")}" +
                "|a:${audio.map(String::lowercase).sorted().joinToString(",")}" +
                "|in-band:$selectsTracksInBand"
        ).take(FINGERPRINT_LENGTH)

    companion object {
        private const val MAX_CODEC_LIST_SIZE = 64
        private const val MAX_CODEC_NAME_LENGTH = 32
        private const val FINGERPRINT_LENGTH = 12

        val BROWSER = MediaCapabilities(
            video = setOf("h264"),
            audio = setOf("aac", "mp3", "opus", "flac", "vorbis"),
            selectsTracksInBand = false,
        )

        private val KNOWN_VIDEO = setOf(
            "h263", "h264", "hevc", "av1", "vp8", "vp9",
            "mpeg1video", "mpeg2video", "mpeg4", "vc1", "wmv3",
            "theora", "mjpeg", "prores", "dvvideo", "rawvideo",
        )
        private val KNOWN_AUDIO = setOf(
            "aac", "ac3", "eac3", "mp3", "mp2", "opus", "flac", "vorbis",
            "dts", "truehd", "alac", "ape", "cook", "wmav1", "wmav2",
            "amr_nb", "amr_wb", "pcm_s8", "pcm_s16le", "pcm_s16be",
            "pcm_s24le", "pcm_s24be", "pcm_s32le", "pcm_s32be",
            "pcm_f32le", "pcm_f64le", "pcm_mulaw", "pcm_alaw",
        )

        /** Resolve an untrusted client report. Missing or empty reports retain browser behavior. */
        fun from(dto: ClientCapabilitiesDto?): MediaCapabilities {
            if (dto == null ||
                dto.videoCodecs.isEmpty() && dto.audioCodecs.isEmpty() && !dto.selectsTracksInBand
            ) return BROWSER
            val normalized = MediaCapabilities(
                video = normalize(dto.videoCodecs, KNOWN_VIDEO),
                audio = normalize(dto.audioCodecs, KNOWN_AUDIO),
                selectsTracksInBand = dto.selectsTracksInBand,
            )
            return normalized.takeUnless {
                it.video.isEmpty() && it.audio.isEmpty() && !it.selectsTracksInBand
            } ?: BROWSER
        }

        private fun normalize(values: List<String>, known: Set<String>): Set<String> =
            values.asSequence()
                .take(MAX_CODEC_LIST_SIZE)
                .map(String::trim)
                .filter { it.length in 1..MAX_CODEC_NAME_LENGTH }
                .map(String::lowercase)
                .filter(known::contains)
                .toSet()
    }
}

/** Stable short identity for a media artifact, derived from its inputs alone. */
internal fun shortSha1(value: String): String =
    MessageDigest.getInstance("SHA-1").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }.take(16)
