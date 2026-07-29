package com.buco7854.opentv.ui.player

import com.buco7854.opentv.core.net.Urls
import com.buco7854.opentv.source.ContentRef
import com.buco7854.opentv.source.SourceId
import com.buco7854.opentv.source.decode
import com.buco7854.opentv.source.encode

sealed interface PlayerTarget {
    val title: String
    val live: Boolean
    val contentRef: ContentRef

    data class LocalUrl(
        val url: String,
        override val title: String,
        val playlistId: Long,
        val tvgId: String?,
        override val live: Boolean,
    ) : PlayerTarget {
        override val contentRef: ContentRef = ContentRef.LocalUrl(url, 0)
    }

    data class HubContent(
        val hubId: Long,
        val playlistId: Long,
        val contentId: String,
        override val title: String,
        override val live: Boolean,
    ) : PlayerTarget {
        override val contentRef: ContentRef = ContentRef.HubContent(contentId)
    }

    data class HubCatchUp(
        val hubId: Long,
        val playlistId: Long,
        val contentId: String,
        override val title: String,
        val startMs: Long,
        val durationMs: Long,
    ) : PlayerTarget {
        override val live: Boolean = false
        override val contentRef: ContentRef = ContentRef.HubContent(contentId)
    }

    companion object
}

fun PlayerTarget.encode(): String = when (this) {
    is PlayerTarget.LocalUrl -> fields(
        "1",
        "local",
        SourceId.LocalPlaylist(playlistId).encode(),
        contentRef.encode(),
        title,
        tvgId.orEmpty(),
        live.toString(),
    )
    is PlayerTarget.HubContent -> fields(
        "1",
        "hub",
        SourceId.Hub(hubId, playlistId).encode(),
        contentRef.encode(),
        title,
        live.toString(),
    )
    is PlayerTarget.HubCatchUp -> fields(
        "1",
        "catchup",
        SourceId.Hub(hubId, playlistId).encode(),
        contentRef.encode(),
        title,
        startMs.toString(),
        durationMs.toString(),
    )
}

fun PlayerTarget.Companion.decode(raw: String): PlayerTarget? {
    val values = raw.split('|').map(Urls::percentDecode)
    if (values.firstOrNull() != "1") return null
    return when (values.getOrNull(1)) {
        "local" -> {
            if (values.size != 7) return null
            val source = SourceId.decode(values[2]) as? SourceId.LocalPlaylist ?: return null
            val ref = ContentRef.decode(values[3]) as? ContentRef.LocalUrl ?: return null
            PlayerTarget.LocalUrl(
                url = ref.url,
                title = values[4],
                playlistId = source.playlistId,
                tvgId = values[5].ifEmpty { null },
                live = values[6].strictBoolean() ?: return null,
            )
        }
        "hub" -> {
            if (values.size != 6) return null
            val source = SourceId.decode(values[2]) as? SourceId.Hub ?: return null
            val ref = ContentRef.decode(values[3]) as? ContentRef.HubContent ?: return null
            PlayerTarget.HubContent(
                hubId = source.hubId,
                playlistId = source.playlistId,
                contentId = ref.contentId,
                title = values[4],
                live = values[5].strictBoolean() ?: return null,
            )
        }
        "catchup" -> {
            if (values.size != 7) return null
            val source = SourceId.decode(values[2]) as? SourceId.Hub ?: return null
            val ref = ContentRef.decode(values[3]) as? ContentRef.HubContent ?: return null
            PlayerTarget.HubCatchUp(
                hubId = source.hubId,
                playlistId = source.playlistId,
                contentId = ref.contentId,
                title = values[4],
                startMs = values[5].toLongOrNull() ?: return null,
                durationMs = values[6].toLongOrNull() ?: return null,
            )
        }
        else -> null
    }
}

private fun fields(vararg values: String): String =
    values.joinToString("|", transform = Urls::percentEncode)

private fun String.strictBoolean(): Boolean? = when (this) {
    "true" -> true
    "false" -> false
    else -> null
}
