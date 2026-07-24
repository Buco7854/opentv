package com.buco7854.opentv.server

import com.buco7854.opentv.core.model.Channel
import com.buco7854.opentv.core.model.ChannelKind
import com.buco7854.opentv.core.model.SeriesGroup
import com.buco7854.opentv.core.model.XtreamSeries
import com.buco7854.opentv.core.storage.Storage
import com.buco7854.opentv.serverdata.db.ContentIdentityRow
import com.buco7854.opentv.serverdata.db.ServerUserDatabase
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ContentIdentityService(
    private val db: ServerUserDatabase,
    private val storage: Storage,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val reconciliation = Mutex()

    suspend fun channel(channel: Channel): ContentIdentityRow =
        channels(listOf(channel)).getValue(channel.id)

    suspend fun channels(channels: List<Channel>): Map<Long, ContentIdentityRow> =
        reconciliation.withLock {
            val now = clock()
            val result = mutableMapOf<Long, ContentIdentityRow>()
            channels.groupBy(Channel::playlistId).forEach { (playlistId, playlistChannels) ->
                val existing = db.content().forPlaylist(playlistId)
                    .associateBy { IdentityKey(it.kind, it.providerFingerprint) }
                val pending = linkedMapOf<IdentityKey, ContentIdentityRow>()
                playlistChannels.forEach { channel ->
                    val key = IdentityKey(channel.kind, channelFingerprint(channel))
                    val row = pending[key] ?: existing[key]?.copy(
                        currentChannelId = channel.id,
                        lastSeenAtMs = now,
                        retired = false,
                    ) ?: newIdentity(playlistId, key, channel.id, now)
                    pending[key] = row
                    result[channel.id] = row
                }
                persist(existing, pending)
            }
            result
        }

    suspend fun xtreamSeries(series: XtreamSeries): ContentIdentityRow =
        identity(
            series.playlistId,
            ChannelKind.SERIES,
            fingerprint("xtream:series:${series.seriesId}"),
            null,
        )

    suspend fun m3uSeries(playlistId: Long, series: SeriesGroup): ContentIdentityRow =
        identity(
            playlistId,
            ChannelKind.SERIES,
            fingerprint("m3u:series:${series.seriesKey.trim()}"),
            null,
        )

    suspend fun resolve(contentId: String): Pair<ContentIdentityRow, Channel?> {
        val identity = db.content().get(contentId) ?: throw ResourceNotFound("content")
        val channel = identity.currentChannelId?.let { storage.channels.get(it) }
        return identity to channel
    }

    suspend fun requireChannel(contentId: String): Pair<ContentIdentityRow, Channel> {
        val (identity, channel) = resolve(contentId)
        return identity to (channel ?: throw ResourceNotFound("content", "Content is unavailable"))
    }

    suspend fun deletePlaylist(playlistId: Long) = db.content().deletePlaylist(playlistId)

    /** Reconcile a refreshed catalog while retaining missing identities as retired. */
    suspend fun reconcilePlaylist(playlistId: Long) {
        val seenAt = clock()
        val channels = mutableListOf<Channel>()
        listOf(ChannelKind.LIVE, ChannelKind.MOVIE, ChannelKind.SERIES).forEach { kind ->
            storage.channels.observeGroups(playlistId, kind).first().forEach { group ->
                channels += storage.channels.observeInGroup(
                    playlistId,
                    kind,
                    group.groupTitle,
                ).first()
            }
        }
        val xtreamSeries = storage.xtreamSeries.observeAll(playlistId).first()
        val m3uSeries = storage.channels.observeAllSeries(playlistId).first()
            .filterNot { it.seriesKey.startsWith("xs:") }
        reconciliation.withLock {
            val existing = db.content().forPlaylist(playlistId)
                .associateBy { IdentityKey(it.kind, it.providerFingerprint) }
            val pending = linkedMapOf<IdentityKey, ContentIdentityRow>()
            channels.forEach { channel ->
                val key = IdentityKey(channel.kind, channelFingerprint(channel))
                pending[key] = existing[key]?.copy(
                    currentChannelId = channel.id,
                    lastSeenAtMs = seenAt,
                    retired = false,
                ) ?: newIdentity(playlistId, key, channel.id, seenAt)
            }
            xtreamSeries.forEach { series ->
                val key = IdentityKey(
                    ChannelKind.SERIES,
                    fingerprint("xtream:series:${series.seriesId}"),
                )
                pending.putIfAbsent(
                    key,
                    existing[key]?.copy(lastSeenAtMs = seenAt, retired = false)
                        ?: newIdentity(playlistId, key, null, seenAt),
                )
            }
            m3uSeries.forEach { series ->
                val key = IdentityKey(
                    ChannelKind.SERIES,
                    fingerprint("m3u:series:${series.seriesKey.trim()}"),
                )
                pending.putIfAbsent(
                    key,
                    existing[key]?.copy(lastSeenAtMs = seenAt, retired = false)
                        ?: newIdentity(playlistId, key, null, seenAt),
                )
            }
            persist(existing, pending)
            db.content().retireNotSeen(playlistId, seenAt)
        }
    }

    private suspend fun identity(
        playlistId: Long,
        kind: Int,
        fingerprint: String,
        channelId: Long?,
    ): ContentIdentityRow = reconciliation.withLock {
        val key = IdentityKey(kind, fingerprint)
        val existing = db.content().forPlaylist(playlistId)
            .firstOrNull { it.kind == kind && it.providerFingerprint == fingerprint }
        val now = clock()
        val row = existing?.copy(
            currentChannelId = channelId ?: existing.currentChannelId,
            lastSeenAtMs = now,
            retired = false,
        ) ?: newIdentity(playlistId, key, channelId, now)
        db.content().upsert(row)
        row
    }

    private fun channelFingerprint(channel: Channel): String =
        channel.xtreamStreamId?.let { fingerprint("xtream:${channel.kind}:$it") }
            ?: fingerprint("m3u:${channel.kind}:${normalizeUrl(channel.url)}")

    private suspend fun persist(
        existing: Map<IdentityKey, ContentIdentityRow>,
        pending: Map<IdentityKey, ContentIdentityRow>,
    ) {
        val inserts = pending.filterKeys { it !in existing }.values.toList()
        val updates = pending.filterKeys { it in existing }.values.toList()
        if (inserts.isNotEmpty()) db.content().insertAll(inserts)
        if (updates.isNotEmpty()) db.content().updateAll(updates)
    }

    private fun newIdentity(
        playlistId: Long,
        key: IdentityKey,
        channelId: Long?,
        seenAtMs: Long,
    ) = ContentIdentityRow(
        UUID.randomUUID().toString(),
        playlistId,
        key.kind,
        key.fingerprint,
        channelId,
        seenAtMs,
        false,
    )

    internal fun normalizeUrl(value: String): String {
        val trimmed = value.trim()
        return runCatching {
            val uri = URI(trimmed)
            URI(
                uri.scheme?.lowercase(),
                uri.userInfo,
                uri.host?.lowercase(),
                uri.port,
                uri.path,
                uri.query,
                null,
            ).toString()
        }.getOrDefault(trimmed.substringBefore('#'))
    }

    private fun fingerprint(value: String): String = digest("opentv-content-v1:$value")

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private data class IdentityKey(val kind: Int, val fingerprint: String)
}
