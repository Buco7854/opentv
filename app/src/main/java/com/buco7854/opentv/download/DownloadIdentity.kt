package com.buco7854.opentv.download

import com.buco7854.opentv.core.model.Download
import com.buco7854.opentv.core.model.DownloadStatus
import com.buco7854.opentv.source.ContentRef
import com.buco7854.opentv.source.SourceId

fun Download.downloadIdentityKey(): String =
    hubSourceId?.let { hubId -> contentId?.let { "hub:$hubId:$it" } }
        ?: "local:$url"

fun downloadIdentityKey(sourceId: SourceId, ref: ContentRef): String? = when {
    sourceId is SourceId.LocalPlaylist && ref is ContentRef.LocalUrl -> "local:${ref.url}"
    sourceId is SourceId.Hub && ref is ContentRef.HubContent -> "hub:${sourceId.hubId}:${ref.contentId}"
    else -> null
}

fun List<Download>.downloadFor(sourceId: SourceId, ref: ContentRef): Download? {
    val key = downloadIdentityKey(sourceId, ref) ?: return null
    return firstOrNull {
        it.downloadIdentityKey() == key &&
            it.status != DownloadStatus.CANCELLED &&
            it.status != DownloadStatus.FAILED
    }
}
