package com.buco7854.opentv.server

import com.buco7854.opentv.core.model.Channel
import com.buco7854.opentv.core.model.ChannelKind
import com.buco7854.opentv.core.model.SeriesGroup
import com.buco7854.opentv.core.model.XtreamSeries
import com.buco7854.opentv.core.storage.ChannelListing
import com.buco7854.opentv.core.storage.Storage
import com.buco7854.opentv.core.storage.XtreamSeriesListing
import com.buco7854.opentv.serverdata.db.ContentIdentityRow
import com.buco7854.opentv.serverdata.db.OpenTvServerDatabase
import com.buco7854.opentv.serverdata.db.writeContentIdentityReconciliation
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ContentIdentityService(
    private val db: OpenTvServerDatabase,
    private val storage: Storage,
    private val loadChannels: suspend (List<Long>) -> List<Channel> = storage.channels::getMany,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val reconciliation = Mutex()
    private val catalogGatesGuard = Mutex()
    private val catalogGates = mutableMapOf<Long, CatalogGate>()

    /**
     * Holds a shared catalog read across all storage and identity reads in one user-visible
     * operation. Refreshes take the exclusive side, so the channel FK's temporary SET NULL
     * state never escapes through an API response.
     */
    internal suspend fun <T> withStablePlaylist(
        playlistId: Long,
        block: suspend () -> T,
    ): T = withCatalogAccess(playlistId, write = false, block)

    /**
     * Runs a catalog refresh and identity reconciliation as one application-level operation.
     * SQLite still commits the streaming catalog batches independently, but readers of this
     * service wait until their stable identities have been rebound.
     */
    suspend fun refreshPlaylist(
        playlistId: Long,
        refresh: suspend () -> Boolean,
    ): Boolean = withCatalogAccess(playlistId, write = true) {
        try {
            refresh().also { refreshed ->
                if (refreshed) reconcilePlaylistWhileStable(playlistId, retireMissing = true)
            }
        } catch (failure: Throwable) {
            repairAfterFailedCatalogWrite(playlistId, failure)
        }
    }

    /** Catalog updates always rewrite some part of the playlist and therefore always reconcile. */
    suspend fun <T> updatePlaylist(
        playlistId: Long,
        update: suspend () -> T,
    ): T = withCatalogAccess(playlistId, write = true) {
        try {
            update().also {
                reconcilePlaylistWhileStable(playlistId, retireMissing = true)
            }
        } catch (failure: Throwable) {
            repairAfterFailedCatalogWrite(playlistId, failure)
        }
    }

    /** Repairs a catalog commit that may have outlived the process before reconciliation. */
    suspend fun repairPlaylist(playlistId: Long) =
        withCatalogAccess(playlistId, write = true) {
            reconcilePlaylistWhileStable(playlistId, retireMissing = false)
        }

    /** Serializes a catalog mutation that does not rewrite identity-bearing rows. */
    suspend fun <T> mutatePlaylist(
        playlistId: Long,
        mutation: suspend () -> T,
    ): T = withCatalogAccess(playlistId, write = true, block = mutation)

    /** Prevents a refresh from writing new catalog rows after playlist deletion cascades. */
    suspend fun <T> deletePlaylist(
        playlistId: Long,
        delete: suspend () -> T,
    ): T = mutatePlaylist(playlistId, delete)

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
    suspend fun identity(contentId: String): ContentIdentityRow {
        val observed = db.content().get(contentId) ?: throw ResourceNotFound("content")
        return withStablePlaylist(observed.playlistId) {
            db.content().get(contentId) ?: throw ResourceNotFound("content")
        }
    }

    /** Identities for a batch of ids, so a list endpoint reads them once rather than per row.
     *  Ids with no identity are absent from the result. */
    suspend fun identitiesByContentId(contentIds: Collection<String>): Map<String, ContentIdentityRow> {
        if (contentIds.isEmpty()) return emptyMap()
        val ids = contentIds.distinct()
        val observed = readIdentities(ids)
        return withStablePlaylists(observed.values.map(ContentIdentityRow::playlistId)) {
            readIdentities(ids)
        }
    }

    /**
     * Resolves display titles for a list surface in two bounded reads: one identity batch and
     * one channel batch. Missing or retired content is deliberately absent from the result.
     */
    suspend fun titlesByContentId(contentIds: Collection<String>): Map<String, String> {
        val ids = contentIds.distinct()
        if (ids.isEmpty()) return emptyMap()
        val observed = readIdentities(ids)
        return withStablePlaylists(observed.values.map(ContentIdentityRow::playlistId)) {
            val identities = readIdentities(ids)
            val channels = loadChannels(
                identities.values.filterNot(ContentIdentityRow::retired)
                    .mapNotNull { it.currentChannelId },
            ).associateBy(Channel::id)
            identities.mapNotNull { (contentId, identity) ->
                channels[identity.currentChannelId]
                    ?.takeIf { it.matches(identity) }
                    ?.let { contentId to it.name }
            }.toMap()
        }
    }

    suspend fun resolve(contentId: String): Pair<ContentIdentityRow, Channel?> {
        val observed = db.content().get(contentId) ?: throw ResourceNotFound("content")
        return withStablePlaylist(observed.playlistId) {
            val identity = db.content().get(contentId) ?: throw ResourceNotFound("content")
            val channel = identity.currentChannelId
                ?.takeUnless { identity.retired }
                ?.let { storage.channels.get(it) }
                ?.takeIf { it.matches(identity) }
            identity to channel
        }
    }

    suspend fun requireChannel(contentId: String): Pair<ContentIdentityRow, Channel> {
        val (identity, channel) = resolve(contentId)
        return identity to (channel ?: throw ResourceNotFound("content", "Content is unavailable"))
    }

    /** Reconcile a refreshed catalog while retaining missing identities as retired. */
    suspend fun reconcilePlaylist(playlistId: Long) =
        withCatalogAccess(playlistId, write = true) {
            reconcilePlaylistWhileStable(playlistId, retireMissing = true)
        }

    private suspend fun reconcilePlaylistWhileStable(
        playlistId: Long,
        retireMissing: Boolean,
    ) {
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
                pending[key] = existing[key]?.let { current ->
                    current.copy(
                        currentChannelId = channel.id,
                        lastSeenAtMs = if (retireMissing) seenAt else current.lastSeenAtMs,
                        retired = false,
                    )
                } ?: newIdentity(playlistId, key, channel.id, seenAt)
            }
            xtreamSeries.forEach { series ->
                val key = xtreamSeriesKey(series.seriesId)
                pending.putIfAbsent(
                    key,
                    existing[key]?.let { current ->
                        current.copy(
                            lastSeenAtMs = if (retireMissing) seenAt else current.lastSeenAtMs,
                            retired = false,
                        )
                    }
                        ?: newIdentity(playlistId, key, null, seenAt),
                )
            }
            m3uSeries.forEach { series ->
                val key = m3uSeriesKey(series.seriesKey)
                pending.putIfAbsent(
                    key,
                    existing[key]?.let { current ->
                        current.copy(
                            lastSeenAtMs = if (retireMissing) seenAt else current.lastSeenAtMs,
                            retired = false,
                        )
                    }
                        ?: newIdentity(playlistId, key, null, seenAt),
                )
            }
            val inserts = pending.filterKeys { it !in existing }.values.toList()
            val updates = pending.filterKeys { it in existing }.values.toList()
            db.writeContentIdentityReconciliation(
                inserts,
                updates,
                playlistId,
                seenAt.takeIf { retireMissing },
            )
        }
    }

    private suspend fun identities(
        playlistId: Long,
        keys: List<IdentityKey>,
        channelIdFor: (IdentityKey) -> Long? = { null },
    ): Map<IdentityKey, ContentIdentityRow> = withStablePlaylist(playlistId) {
        val wanted = keys.distinct()
        if (wanted.isEmpty()) return@withStablePlaylist emptyMap()
        val known = lookup(playlistId, wanted)
        val missing = wanted.filterNot { it in known }
        if (missing.isEmpty()) return@withStablePlaylist known
        val now = clock()
        val created = reconciliation.withLock {
            db.content().insertAll(
                missing.map { newIdentity(playlistId, it, channelIdFor(it), now) },
            )
            lookup(playlistId, missing)
        }
        known + created
    }

    private suspend fun readIdentities(
        contentIds: List<String>,
    ): Map<String, ContentIdentityRow> =
        contentIds.chunked(MAX_BOUND_VARIABLES)
            .flatMap { db.content().byContentIds(it) }
            .associateBy(ContentIdentityRow::contentId)

    private suspend fun <T> withStablePlaylists(
        playlistIds: Collection<Long>,
        block: suspend () -> T,
    ): T {
        suspend fun acquire(ids: List<Long>, index: Int): T =
            if (index == ids.size) {
                block()
            } else {
                withStablePlaylist(ids[index]) { acquire(ids, index + 1) }
            }
        return acquire(playlistIds.distinct().sorted(), 0)
    }

    private suspend fun repairAfterFailedCatalogWrite(
        playlistId: Long,
        failure: Throwable,
    ): Nothing {
        try {
            withContext(NonCancellable) {
                // Bind whatever the failed streaming refresh managed to commit, but do not
                // interpret absent rows in a partial response as provider-confirmed retirement.
                reconcilePlaylistWhileStable(playlistId, retireMissing = false)
            }
        } catch (repairFailure: Throwable) {
            failure.addSuppressed(repairFailure)
        }
        throw failure
    }

    private suspend fun <T> withCatalogAccess(
        playlistId: Long,
        write: Boolean,
        block: suspend () -> T,
    ): T {
        val held = coroutineContext[CatalogAccess]
        if (playlistId in held?.writes.orEmpty() ||
            (!write && playlistId in held?.reads.orEmpty())
        ) {
            return block()
        }
        check(!write || playlistId !in held?.reads.orEmpty()) {
            "Cannot upgrade a stable catalog read to a write"
        }
        val gate = catalogGatesGuard.withLock {
            catalogGates.getOrPut(playlistId, ::CatalogGate)
        }
        val access = CatalogAccess(
            reads = held?.reads.orEmpty() + playlistId,
            writes = held?.writes.orEmpty() + if (write) setOf(playlistId) else emptySet(),
        )
        val guarded = suspend {
            withContext(access) { block() }
        }
        return if (write) gate.write(guarded) else gate.read(guarded)
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

    /** Matches already-resolved favorite identities to series catalog rows without another
     * identity query for every playlist represented on the favorites page. */
    internal fun xtreamSeriesFingerprint(seriesId: Long): String =
        xtreamSeriesKey(seriesId).fingerprint

    internal fun m3uSeriesFingerprint(seriesKey: String): String =
        m3uSeriesKey(seriesKey).fingerprint

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

private class CatalogAccess(
    val reads: Set<Long>,
    val writes: Set<Long>,
) : AbstractCoroutineContextElement(CatalogAccess) {
    companion object Key : CoroutineContext.Key<CatalogAccess>
}

/**
 * Coroutine-friendly per-playlist read/write gate. Readers remain concurrent; a queued writer
 * prevents new readers and waits for the readers already inside, avoiding refresh starvation.
 */
private class CatalogGate {
    private val state = Mutex()
    private var readers = 0
    private var readersDrained = completedSignal()
    private var writer: CompletableDeferred<Unit>? = null

    suspend fun <T> read(block: suspend () -> T): T {
        while (true) {
            val activeWriter = state.withLock {
                writer?.let { return@withLock it }
                if (readers == 0) readersDrained = CompletableDeferred()
                readers++
                null
            }
            if (activeWriter == null) break
            activeWriter.await()
        }
        try {
            return block()
        } finally {
            state.withLock {
                readers--
                check(readers >= 0)
                if (readers == 0) readersDrained.complete(Unit)
            }
        }
    }

    suspend fun <T> write(block: suspend () -> T): T {
        val completion = CompletableDeferred<Unit>()
        var ownsWriterSlot = false
        try {
            while (true) {
                var readersToDrain: CompletableDeferred<Unit>? = null
                val activeWriter = state.withLock {
                    writer?.let { return@withLock it }
                    writer = completion
                    ownsWriterSlot = true
                    readersToDrain = readersDrained.takeIf { readers > 0 }
                    null
                }
                if (activeWriter == null) {
                    readersToDrain?.await()
                    break
                }
                activeWriter.await()
            }
            return block()
        } finally {
            if (ownsWriterSlot) {
                state.withLock {
                    check(writer === completion)
                    writer = null
                    completion.complete(Unit)
                }
            }
        }
    }

    private companion object {
        fun completedSignal() = CompletableDeferred<Unit>().also { it.complete(Unit) }
    }
}
