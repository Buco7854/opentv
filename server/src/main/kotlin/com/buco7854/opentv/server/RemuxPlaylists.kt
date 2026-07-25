package com.buco7854.opentv.server

import kotlin.math.ceil

/**
 * The HLS documents a remux session publishes. All three are VOD playlists listing every
 * segment up front from the known duration, so hls.js can seek anywhere before ffmpeg has
 * produced anything.
 */
internal object RemuxPlaylists {

    /** Media playlist: the video/audio segments, written once when the session is prepared. */
    fun media(session: RemuxSession): String = buildString {
        val lengths = session.segmentLengths()
        append("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-PLAYLIST-TYPE:VOD\n")
        append("#EXT-X-TARGETDURATION:${targetDuration(session, lengths)}\n")
        append("#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-MAP:URI=\"init.mp4\"\n")
        lengths.forEachIndexed { n, length ->
            append("#EXTINF:%.6f,\n".format(length))
            append("main$n.m4s\n")
        }
        append("#EXT-X-ENDLIST\n")
    }

    /** Master playlist: the video/audio rendition plus one WebVTT rendition per subtitle track. */
    fun master(session: RemuxSession, mediaQuery: String): String = buildString {
        append("#EXTM3U\n#EXT-X-VERSION:7\n")
        session.subLabels.forEachIndexed { index, label ->
            append("#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"subs\",NAME=\"${label.replace('"', '\'')}\",")
            append("AUTOSELECT=YES,DEFAULT=NO,FORCED=NO,URI=\"sub_$index.m3u8$mediaQuery\"\n")
        }
        append("#EXT-X-STREAM-INF:BANDWIDTH=3000000")
        if (session.subLabels.isNotEmpty()) append(",SUBTITLES=\"subs\"")
        append("\nmain.m3u8$mediaQuery\n")
    }

    /** A subtitle rendition's own playlist: one WebVTT segment per video segment, so cues become
     *  available in step with the video as ffmpeg produces them. */
    fun subtitles(session: RemuxSession, index: Int, mediaQuery: String): String = buildString {
        val lengths = session.segmentLengths()
        append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-PLAYLIST-TYPE:VOD\n")
        append("#EXT-X-TARGETDURATION:${targetDuration(session, lengths)}\n")
        append("#EXT-X-MEDIA-SEQUENCE:0\n")
        lengths.forEachIndexed { n, length ->
            append("#EXTINF:%.6f,\n".format(length))
            append("sub_${index}_$n.vtt$mediaQuery\n")
        }
        append("#EXT-X-ENDLIST\n")
    }

    /** Media playlists are served with the caller's lease query appended to every child URL. */
    fun withMediaQuery(playlist: String, mediaQuery: String): String {
        if (mediaQuery.isEmpty()) return playlist
        return playlist
            .replace("URI=\"init.mp4\"", "URI=\"init.mp4$mediaQuery\"")
            .lineSequence()
            .joinToString("\n") { line ->
                if (line.isNotBlank() && !line.startsWith("#")) line + mediaQuery else line
            }
    }

    private fun targetDuration(session: RemuxSession, lengths: List<Double>) =
        ceil(lengths.maxOrNull() ?: session.segLenSec).toInt()
}
