package com.buco7854.opentv.source

import com.buco7854.opentv.core.net.Urls

sealed interface SourceId {
    data class LocalPlaylist(val playlistId: Long) : SourceId
    data class Hub(val hubId: Long, val playlistId: Long) : SourceId
    /** Identifies a hub whose playlists could not be discovered, so the failure can be retried. */
    data class HubConnection(val hubId: Long) : SourceId

    companion object
}

fun SourceId.encode(): String = when (this) {
    is SourceId.LocalPlaylist -> "p:$playlistId"
    is SourceId.Hub -> "h:$hubId:$playlistId"
    is SourceId.HubConnection -> "hc:$hubId"
}

fun SourceId.Companion.decode(raw: String): SourceId? {
    val parts = raw.split(':')
    return when {
        parts.size == 2 && parts[0] == "p" ->
            canonicalLong(parts[1])?.let(SourceId::LocalPlaylist)
        parts.size == 3 && parts[0] == "h" -> {
            val hubId = canonicalLong(parts[1]) ?: return null
            val playlistId = canonicalLong(parts[2]) ?: return null
            SourceId.Hub(hubId, playlistId)
        }
        parts.size == 2 && parts[0] == "hc" ->
            canonicalLong(parts[1])?.let(SourceId::HubConnection)
        else -> null
    }
}

sealed interface ContentRef {
    data class LocalUrl(val url: String, val channelId: Long) : ContentRef
    data class HubContent(val contentId: String) : ContentRef

    companion object
}

fun ContentRef.encode(): String = when (this) {
    is ContentRef.LocalUrl -> "l:$channelId:${Urls.percentEncode(url)}"
    is ContentRef.HubContent -> "h:${Urls.percentEncode(contentId)}"
}

fun ContentRef.Companion.decode(raw: String): ContentRef? = when {
    raw.startsWith("l:") -> {
        val separator = raw.indexOf(':', startIndex = 2)
        if (separator < 0) return null
        val channelId = canonicalLong(raw.substring(2, separator)) ?: return null
        decodeCanonical(raw.substring(separator + 1))?.let {
            ContentRef.LocalUrl(it, channelId)
        }
    }
    raw.startsWith("h:") -> decodeCanonical(raw.substring(2))
        ?.takeIf(String::isNotEmpty)
        ?.let(ContentRef::HubContent)
    else -> null
}

private val CANONICAL_LONG = Regex("""-?(0|[1-9][0-9]*)""")

private fun canonicalLong(raw: String): Long? =
    raw.takeIf(CANONICAL_LONG::matches)?.toLongOrNull()

private fun decodeCanonical(raw: String): String? {
    val decoded = Urls.percentDecode(raw)
    return decoded.takeIf { Urls.percentEncode(it) == raw }
}
