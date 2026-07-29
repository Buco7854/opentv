package com.buco7854.opentv.server

import com.buco7854.opentv.core.model.Channel
import com.buco7854.opentv.core.model.ChannelKind
import com.buco7854.opentv.core.model.SeriesGroup
import com.buco7854.opentv.core.model.XtreamSeries
import com.buco7854.opentv.core.storage.ChannelListing
import com.buco7854.opentv.core.storage.Storage
import com.buco7854.opentv.core.storage.XtreamSeriesListing
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
    private val loadChannels: suspend (List<Long>) -> List<Channel> = storage.channels::getMany,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val reconciliation = Mutex()

    suspend fun channel(channel: Channel): ContentIdentityRow =
        channels(listOf(channel)).getValue(channel.id)

    suspend fun channels(channels: List<Channel>): Map<Long, ContentIdentityRow> {
        if (channels.isEmpty()) return emptyMap()
        val result = mutableMapOf<Long, ContentIdentityRow>()
        channels.groupBy(Channel::playlistId).forEach { (playlistId, entries) ->
            val keys = entries.associate { it.id to IdentityKey(it.kind, channelFingerprint(it)) }
            val firstChannelOf = keys.entries.reversed().associate { (id, key) -> key to id }
            val resolved = identities(playlistId, keys.values.toList()) { firstChannelOf[it] }
            entries.forEach { result[it.id] = requireNotNull(resolved[keys[it.id]]) }
        }
        return result
    }

    /** Listing equivalent of [channels], retaining only the columns the page query selected. */
    suspend fun channelListings(channels: List<ChannelListing>): Map<Long, ContentIdentityRow> {
        if (channels.isEmpty()) return emptyMap()
        val result = mutableMapOf<Long, ContentIdentityRow>()
        channels.groupBy(ChannelListing::playlistId).forEach { (playlistId, entries) ->
            val keys = entries.associate { it.id to IdentityKey(it.kind, channelFingerprint(it)) }
            val firstChannelOf = keys.entries.reversed().associate { (id, key) -> key to id }
            val resolved = identities(playlistId, keys.values.toList()) { firstChannelOf[it] }
            entries.forEach { result[it.id] = requireNotNull(resolved[keys[it.id]]) }
        }
        return result
    }

    suspend fun xtreamSeries(series: XtreamSeries): ContentIdentityRow =
        xtreamSeriesIdentities(listOf(series)).getValue(series.seriesId)

    suspend fun xtreamSeriesIdentities(
        series: List<XtreamSeries>,
    ): Map<Long, ContentIdentityRow> {
        if (series.isEmpty()) return emptyMap()
        val result = mutableMapOf<Long, ContentIdentityRow>()
        series.groupBy(XtreamSeries::playlistId).forEach { (playlistId, entries) ->
            val keys = entries.associate { it.seriesId to xtreamSeriesKey(it.seriesId) }
            val resolved = identities(playlistId, keys.values.toList())
            entries.forEach { result[it.seriesId] = requireNotNull(resolved[keys[it.seriesId]]) }
        }
        return result
    }

    suspend fun xtreamSeriesListingIdentities(
        series: List<XtreamSeriesListing>,
    ): Map<Long, ContentIdentityRow> {
        if (series.isEmpty()) return emptyMap()
        val result = mutableMapOf<Long, ContentIdentityRow>()
        series.groupBy(XtreamSeriesListing::playlistId).forEach { (playlistId, entries) ->
            val keys = entries.associate { it.seriesId to xtreamSeriesKey(it.seriesId) }
            val resolved = identities(playlistId, keys.values.toList())
            entries.forEach { result[it.seriesId] = requireNotNull(resolved[keys[it.seriesId]]) }
        }
        return result
    }

    suspend fun m3uSeries(playlistId: Long, series: SeriesGroup): ContentIdentityRow =
        m3uSeriesIdentities(playlistId, listOf(series)).getValue(series.seriesKey)

    suspend fun m3uSeries(playlistId: Long, seriesKey: String): ContentIdentityRow {
        val key = m3uSeriesKey(seriesKey)
        return requireNotNull(identities(playlistId, listOf(key))[key])
    }

    suspend fun m3uSeriesIdentities(
        playlistId: Long,
        series: List<SeriesGroup>,
    ): Map<String, ContentIdentityRow> {
        if (series.isEmpty()) return emptyMap()
        val keys = series.associate { it.seriesKey to m3uSeriesKey(it.seriesKey) }
        val resolved = identities(playlistId, keys.values.toList())
        return series.associate { it.seriesKey to requireNotNull(resolved[keys[it.seriesKey]]) }
    }

    /** The identity alone. Prefer this over [resolve] when the channel is not needed: the
     *  channel lives in the other database, so resolving one is never free. */
    suspend fun identity(contentId: String): ContentIdentityRow =
        db.content().get(contentId) ?: throw ResourceNotFound("content")

    /** Identities for a batch of ids, so a list endpoint reads them once rather than per row.
     *  Ids with no identity are absent from the result. */
    suspend fun identitiesByContentId(contentIds: Collection<String>): Map<String, ContentIdentityRow> {
        if (contentIds.isEmpty()) return emptyMap()
        return contentIds.distinct()
            .chunked(MAX_BOUND_VARIABLES)
            .flatMap { db.content().byContentIds(it) }
            .associateBy { it.contentId }
    }

    /**
     * Resolves display titles for a list surface in two bounded reads: one identity batch and
     * one channel batch. Missing or retired content is deliberately absent from the result.
     */
    suspend fun titlesByContentId(contentIds: Collection<String>): Map<String, String> {
        val identities = identitiesByContentId(contentIds)
        if (identities.isEmpty()) return emptyMap()
        val channels = loadChannels(
            identities.values.filterNot(ContentIdentityRow::retired)
                .mapNotNull { it.currentChannelId },
        ).associateBy(Channel::id)
        return identities.mapNotNull { (contentId, identity) ->
            channels[identity.currentChannelId]
                ?.takeIf { it.matches(identity) }
                ?.let { contentId to it.name }
        }.toMap()
    }

    suspend fun resolve(contentId: String): Pair<ContentIdentityRow, Channel?> {
        val identity = identity(contentId)
        val channel = identity.currentChannelId
            ?.takeUnless { identity.retired }
            ?.let { storage.channels.get(it) }
            ?.takeIf { it.matches(identity) }
        return identity to channel
    }

    suspend fun requireChannel(contentId: String): Pair<ContentIdentityRow, Channel> {
        val (identity, channel) = resolve(contentId)
        return identity to (channel ?: throw ResourceNotFound("content", "Content is unavailable"))
    }

    suspend fun deletePlaylist(playlistId: Long) = db.content().deletePlaylist(playlistId)

    /** Reconcile a refreshed catalog while retaining missing identities as retired. */
    suspend fun reconcilePlaylist(playlistId: Long) {
        val observedAt = clock()
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
            // lastSeenAtMs is the reconciliation generation as well as a timestamp. Two
            // refreshes can finish in one clock millisecond, so make the generation strictly
            // increase or the second pass cannot distinguish its missing rows from its own.
            val seenAt = maxOf(
                observedAt,
                (existing.values.maxOfOrNull(ContentIdentityRow::lastSeenAtMs) ?: Long.MIN_VALUE)
                    .let { if (it == Long.MAX_VALUE) it else it + 1 },
            )
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
                val key = xtreamSeriesKey(series.seriesId)
                pending.putIfAbsent(
                    key,
                    existing[key]?.copy(lastSeenAtMs = seenAt, retired = false)
                        ?: newIdentity(playlistId, key, null, seenAt),
                )
            }
            m3uSeries.forEach { series ->
                val key = m3uSeriesKey(series.seriesKey)
                pending.putIfAbsent(
                    key,
                    existing[key]?.copy(lastSeenAtMs = seenAt, retired = false)
                        ?: newIdentity(playlistId, key, null, seenAt),
                )
            }
            val inserts = pending.filterKeys { it !in existing }.values.toList()
            val updates = pending.filterKeys { it in existing }.values.toList()
            if (inserts.isNotEmpty()) db.content().insertAll(inserts)
            if (updates.isNotEmpty()) db.content().updateAll(updates)
            db.content().retireNotSeen(playlistId, seenAt)
        }
    }

    private suspend fun identities(
        playlistId: Long,
        keys: List<IdentityKey>,
        channelIdFor: (IdentityKey) -> Long? = { null },
    ): Map<IdentityKey, ContentIdentityRow> {
        val wanted = keys.distinct()
        if (wanted.isEmpty()) return emptyMap()
        val known = lookup(playlistId, wanted)
        val missing = wanted.filterNot { it in known }
        if (missing.isEmpty()) return known
        val now = clock()
        val created = reconciliation.withLock {
            db.content().insertAll(
                missing.map { newIdentity(playlistId, it, channelIdFor(it), now) },
            )
            lookup(playlistId, missing)
        }
        return known + created
    }

    private suspend fun lookup(
        playlistId: Long,
        keys: List<IdentityKey>,
    ): Map<IdentityKey, ContentIdentityRow> =
        keys.groupBy(IdentityKey::kind)
            .flatMap { (kind, sameKind) ->
                sameKind.map(IdentityKey::fingerprint)
                    .chunked(MAX_BOUND_VARIABLES)
                    .flatMap { db.content().byFingerprints(playlistId, kind, it) }
            }
            .associateBy { IdentityKey(it.kind, it.providerFingerprint) }

    private fun channelFingerprint(channel: Channel): String =
        channel.xtreamStreamId?.let { fingerprint("xtream:${channel.kind}:$it") }
            ?: fingerprint("m3u:${channel.kind}:${normalizeUrl(channel.url)}")

    private fun channelFingerprint(channel: ChannelListing): String =
        channel.xtreamStreamId?.let { fingerprint("xtream:${channel.kind}:$it") }
            ?: fingerprint("m3u:${channel.kind}:${normalizeUrl(channel.url)}")

    private fun Channel.matches(identity: ContentIdentityRow): Boolean =
        playlistId == identity.playlistId &&
            kind == identity.kind &&
            channelFingerprint(this) == identity.providerFingerprint

    private fun xtreamSeriesKey(seriesId: Long) =
        IdentityKey(ChannelKind.SERIES, fingerprint("xtream:series:$seriesId"))

    private fun m3uSeriesKey(seriesKey: String) =
        IdentityKey(ChannelKind.SERIES, fingerprint("m3u:series:${seriesKey.trim()}"))

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

    private companion object {
        const val MAX_BOUND_VARIABLES = 500
    }
}
