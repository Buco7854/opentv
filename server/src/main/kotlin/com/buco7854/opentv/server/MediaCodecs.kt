package com.buco7854.opentv.server

import java.security.MessageDigest

/**
 * What a browser can play as-is. Every media path draws the same line: the relay decides
 * whether its shared read may copy audio, and the remux decides whether a file needs
 * exposing at all and whether its video is copied or re-encoded.
 */
internal object MediaCodecs {
    private val BROWSER_AUDIO = setOf("aac", "mp3", "opus", "flac", "vorbis")
    private val BROWSER_VIDEO = setOf("h264")

    /** Null means there is no such stream, so there is nothing that needs normalizing. */
    fun audioDecodable(codec: String?): Boolean = codec == null || codec.lowercase() in BROWSER_AUDIO

    fun videoDecodable(codec: String?): Boolean = codec == null || codec.lowercase() in BROWSER_VIDEO

    fun isHevc(codec: String?): Boolean = codec.equals("hevc", ignoreCase = true)
}

/** Stable short identity for a media artifact, derived from its inputs alone. */
internal fun shortSha1(value: String): String =
    MessageDigest.getInstance("SHA-1").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }.take(16)
