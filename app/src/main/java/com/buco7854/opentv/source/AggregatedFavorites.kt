package com.buco7854.opentv.source

import com.buco7854.opentv.core.model.HubSource
import com.buco7854.opentv.core.model.Playlist
import com.buco7854.opentv.core.storage.Storage
import com.buco7854.opentv.hub.HubRegistry
import com.buco7854.opentv.hub.HubUnauthorizedException
import com.buco7854.opentv.hub.HubUnreachableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class FavoritesSection(
    val source: SourceId,
    val title: String,
    val items: List<CatalogItem>,
    val loading: Boolean,
    val error: CatalogLoadError?,
)

data class AggregatedFavoritesState(
    val sections: List<FavoritesSection> = emptyList(),
    val loading: Boolean = false,
) {
    val totalCount: Int get() = sections.sumOf { it.items.size }
    val hasMultipleSources: Boolean
        get() = sections.count { it.items.isNotEmpty() || it.error != null } > 1
}

/**
 * Aggregates render-ready favorites while leaving reads and writes owned by each source.
 *
 * Sections follow configured storage order: local playlists first, then hubs, with each
 * hub's playlists in the order returned by that hub.
 */
class AggregatedFavorites internal constructor(
    private val scope: CoroutineScope,
    localPlaylists: Flow<List<Playlist>>,
    hubSources: Flow<List<HubSource>>,
    private val hubPlaylists: suspend (HubSource) -> CatalogResult<List<HubPlaylist>>,
    private val gatewayFor: (SourceId) -> CatalogGateway,
) {
    constructor(
        scope: CoroutineScope,
        storage: Storage,
        hubs: HubRegistry,
        gatewayFor: (SourceId) -> CatalogGateway,
    ) : this(
        scope = scope,
        localPlaylists = storage.playlists.observeAll(),
        hubSources = storage.hubSources.observeAll(),
        hubPlaylists = { hub -> discoverHubPlaylists(hubs, hub) },
        gatewayFor = gatewayFor,
    )

    private val lock = Any()
    private val slots = mutableMapOf<SourceId, SectionSlot>()
    private val loadJobs = mutableMapOf<SourceId, Job>()
    private val loadRequests = mutableMapOf<SourceId, Long>()
    private val hubJobs = mutableMapOf<Long, Job>()
    private val hubRequests = mutableMapOf<Long, Long>()
    private var requestSequence = 0L
    private var currentLocals = emptyList<Playlist>()
    private var currentHubs = emptyList<HubSource>()

    private val mutableState = MutableStateFlow(AggregatedFavoritesState())
    val state: StateFlow<AggregatedFavoritesState> = mutableState.asStateFlow()

    init {
        scope.launch {
            combine(localPlaylists, hubSources) { locals, hubs -> locals to hubs }
                .collect { (locals, hubs) -> configurationChanged(locals, hubs) }
        }
    }

    fun refresh() {
        val locals: List<SourceId.LocalPlaylist>
        val hubs: List<Pair<HubSource, Int>>
        synchronized(lock) {
            locals = currentLocals.map { SourceId.LocalPlaylist(it.id) }
            hubs = currentHubs.mapIndexed { index, hub -> hub to index }
        }
        locals.forEach(::startLoad)
        hubs.forEach { (hub, index) -> startHubDiscovery(hub, index) }
    }

    fun retry(source: SourceId) {
        if (source is SourceId.HubConnection) {
            val target = synchronized(lock) {
                currentHubs.indexOfFirst { it.id == source.hubId }
                    .takeIf { it >= 0 }
                    ?.let { currentHubs[it] to it }
            }
            target?.let { (hub, index) -> startHubDiscovery(hub, index) }
        } else {
            startLoad(source)
        }
    }

    suspend fun toggleFavorite(source: SourceId, ref: ContentRef) {
        mutateFavorite(source) { gatewayFor(source).toggleFavorite(ref) }
    }

    suspend fun setFavorite(source: SourceId, ref: ContentRef, favorite: Boolean) {
        mutateFavorite(source) { gatewayFor(source).setFavorite(ref, favorite) }
    }

    private suspend fun mutateFavorite(
        source: SourceId,
        mutation: suspend () -> CatalogResult<Boolean>,
    ) {
        val token = beginMutation(source) ?: return
        val result = try {
            mutation()
        } catch (cancelled: CancellationException) {
            finishMutation(source, token)
            throw cancelled
        } catch (error: Throwable) {
            CatalogResult.Failed(error)
        }
        when (result) {
            is CatalogResult.Success -> reloadAwait(source)
            CatalogResult.SignedOut ->
                setError(source, CatalogLoadError.SignedOut, expectedToken = token)
            CatalogResult.Unreachable ->
                setError(source, CatalogLoadError.Unreachable, expectedToken = token)
            is CatalogResult.Failed ->
                setError(source, CatalogLoadError.Failed(result.cause), expectedToken = token)
        }
    }

    private fun configurationChanged(locals: List<Playlist>, hubs: List<HubSource>) {
        val localLoads = mutableListOf<SourceId.LocalPlaylist>()
        val hubDiscoveries = mutableListOf<Pair<HubSource, Int>>()
        synchronized(lock) {
            val previousHubIds = currentHubs.mapTo(mutableSetOf()) { it.id }
            currentLocals = locals
            currentHubs = hubs
            val localIds = locals.mapTo(mutableSetOf()) { it.id }
            val hubIds = hubs.mapTo(mutableSetOf()) { it.id }
            (previousHubIds - hubIds).forEach { hubId ->
                hubRequests.remove(hubId)
                hubJobs.remove(hubId)?.cancel()
            }
            slots.keys.filter {
                when (it) {
                    is SourceId.LocalPlaylist -> it.playlistId !in localIds
                    is SourceId.Hub -> it.hubId !in hubIds
                    is SourceId.HubConnection -> it.hubId !in hubIds
                }
            }.forEach(::removeSlotLocked)

            locals.forEachIndexed { index, playlist ->
                val source = SourceId.LocalPlaylist(playlist.id)
                val existing = slots[source]
                if (existing == null) {
                    slots[source] = SectionSlot(
                        descriptor = SourceDescriptor(source, playlist.name, SectionOrder(0, index, 0)),
                        loading = true,
                    )
                    localLoads += source
                } else {
                    existing.descriptor =
                        SourceDescriptor(source, playlist.name, SectionOrder(0, index, 0))
                }
            }

            val changedHubIds = hubs.filter { hub ->
                val old = slots.values.firstOrNull { it.descriptor.hubId == hub.id }
                old == null || old.descriptor.hubName != hub.name ||
                    currentHubBaseUrlLocked(hub.id) != hub.baseUrl
            }.mapTo(mutableSetOf()) { it.id }
            hubs.forEachIndexed { index, hub ->
                if (hub.id in changedHubIds || slots.values.none { it.descriptor.hubId == hub.id }) {
                    hubDiscoveries += hub to index
                } else {
                    slots.values.filter { it.descriptor.hubId == hub.id }.forEach {
                        it.descriptor = it.descriptor.copy(
                            order = it.descriptor.order.copy(primary = index),
                            hubName = hub.name,
                            hubBaseUrl = hub.baseUrl,
                        )
                    }
                }
            }
            publishLocked()
        }
        localLoads.forEach(::startLoad)
        hubDiscoveries.forEach { (hub, index) -> startHubDiscovery(hub, index) }
    }

    private fun currentHubBaseUrlLocked(hubId: Long): String? =
        slots.values.firstOrNull { it.descriptor.hubId == hubId }?.descriptor?.hubBaseUrl

    private fun startHubDiscovery(hub: HubSource, hubIndex: Int) {
        val token: Long
        synchronized(lock) {
            token = ++requestSequence
            hubRequests[hub.id] = token
            hubJobs.remove(hub.id)?.cancel()
            val existing = slots.values.filter { it.descriptor.hubId == hub.id }
            if (existing.isEmpty()) {
                val source = SourceId.HubConnection(hub.id)
                slots[source] = SectionSlot(
                    descriptor = SourceDescriptor(
                        source = source,
                        title = hub.name,
                        order = SectionOrder(1, hubIndex, 0),
                        hubId = hub.id,
                        hubName = hub.name,
                        hubBaseUrl = hub.baseUrl,
                    ),
                    loading = true,
                )
            } else {
                existing.forEach {
                    it.loading = true
                    it.error = null
                }
            }
            publishLocked()
        }
        val job = scope.launch {
            val result = try {
                hubPlaylists(hub)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                CatalogResult.Failed(error)
            }
            completeHubDiscovery(hub, hubIndex, token, result)
        }
        synchronized(lock) {
            if (hubRequests[hub.id] == token) hubJobs[hub.id] = job else job.cancel()
        }
    }

    private fun completeHubDiscovery(
        hub: HubSource,
        hubIndex: Int,
        token: Long,
        result: CatalogResult<List<HubPlaylist>>,
    ) {
        val loads = mutableListOf<SourceId.Hub>()
        synchronized(lock) {
            if (hubRequests[hub.id] != token || currentHubs.none { it.id == hub.id }) return
            hubRequests.remove(hub.id)
            hubJobs.remove(hub.id)
            val previousItems = slots.values
                .filter { it.descriptor.hubId == hub.id }
                .associate { it.descriptor.source to it.items }
            slots.keys.filter { slots[it]?.descriptor?.hubId == hub.id }.forEach(::removeSlotLocked)
            when (result) {
                is CatalogResult.Success -> result.value.forEachIndexed { playlistIndex, playlist ->
                    val source = SourceId.Hub(hub.id, playlist.id)
                    slots[source] = SectionSlot(
                        descriptor = SourceDescriptor(
                            source = source,
                            title = playlist.name,
                            order = SectionOrder(1, hubIndex, playlistIndex),
                            hubId = hub.id,
                            hubName = hub.name,
                            hubBaseUrl = hub.baseUrl,
                        ),
                        items = previousItems[source].orEmpty(),
                        loading = true,
                    )
                    loads += source
                }
                CatalogResult.SignedOut -> addHubFailureLocked(hub, hubIndex, CatalogLoadError.SignedOut)
                CatalogResult.Unreachable ->
                    addHubFailureLocked(hub, hubIndex, CatalogLoadError.Unreachable)
                is CatalogResult.Failed ->
                    addHubFailureLocked(hub, hubIndex, CatalogLoadError.Failed(result.cause))
            }
            publishLocked()
        }
        loads.forEach(::startLoad)
    }

    private fun addHubFailureLocked(hub: HubSource, hubIndex: Int, error: CatalogLoadError) {
        val source = SourceId.HubConnection(hub.id)
        slots[source] = SectionSlot(
            descriptor = SourceDescriptor(
                source = source,
                title = hub.name,
                order = SectionOrder(1, hubIndex, 0),
                hubId = hub.id,
                hubName = hub.name,
                hubBaseUrl = hub.baseUrl,
            ),
            error = error,
        )
    }

    private fun startLoad(source: SourceId) {
        val token = beginLoad(source) ?: return
        val job = scope.launch {
            val result = loadAllFavorites(source)
            completeLoad(source, token, result)
        }
        synchronized(lock) {
            if (loadRequests[source] == token) loadJobs[source] = job else job.cancel()
        }
    }

    private suspend fun reloadAwait(source: SourceId) {
        val token = beginLoad(source) ?: return
        val result = try {
            loadAllFavorites(source)
        } catch (cancelled: CancellationException) {
            synchronized(lock) {
                if (loadRequests[source] == token) {
                    slots[source]?.loading = false
                    publishLocked()
                }
            }
            throw cancelled
        }
        completeLoad(source, token, result)
    }

    private fun beginLoad(source: SourceId): Long? = synchronized(lock) {
        if (source is SourceId.HubConnection || source !in slots) return@synchronized null
        val token = ++requestSequence
        loadRequests[source] = token
        loadJobs.remove(source)?.cancel()
        slots[source]?.apply {
            loading = true
            error = null
        }
        publishLocked()
        token
    }

    private suspend fun loadAllFavorites(source: SourceId): CatalogResult<List<CatalogItem>> {
        val gateway = try {
            gatewayFor(source)
        } catch (error: Throwable) {
            return CatalogResult.Failed(error)
        }
        val items = mutableListOf<CatalogItem>()
        val seen = mutableSetOf<ContentRef>()
        var offset = 0
        while (true) {
            val page = try {
                gateway.favorites(offset, DEFAULT_CATALOG_PAGE_SIZE)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                return CatalogResult.Failed(error)
            }
            when (page) {
                is CatalogResult.Success -> {
                    offset += page.value.items.size
                    items += page.value.items.filter { seen.add(it.ref) }
                    if (offset >= page.value.total || page.value.items.isEmpty()) {
                        return CatalogResult.Success(items)
                    }
                }
                CatalogResult.SignedOut -> return CatalogResult.SignedOut
                CatalogResult.Unreachable -> return CatalogResult.Unreachable
                is CatalogResult.Failed -> return page
            }
        }
    }

    private fun completeLoad(
        source: SourceId,
        token: Long,
        result: CatalogResult<List<CatalogItem>>,
    ) {
        synchronized(lock) {
            if (loadRequests[source] != token) return
            loadRequests.remove(source)
            loadJobs.remove(source)
            slots[source]?.apply {
                loading = false
                when (result) {
                    is CatalogResult.Success -> {
                        items = result.value
                        error = null
                    }
                    CatalogResult.SignedOut -> error = CatalogLoadError.SignedOut
                    CatalogResult.Unreachable -> error = CatalogLoadError.Unreachable
                    is CatalogResult.Failed -> error = CatalogLoadError.Failed(result.cause)
                }
            }
            publishLocked()
        }
    }

    private fun setError(
        source: SourceId,
        error: CatalogLoadError,
        expectedToken: Long? = null,
    ) {
        synchronized(lock) {
            if (expectedToken != null && loadRequests[source] != expectedToken) return
            loadRequests.remove(source)
            loadJobs.remove(source)?.cancel()
            slots[source]?.apply {
                loading = false
                this.error = error
            }
            publishLocked()
        }
    }

    private fun beginMutation(source: SourceId): Long? = synchronized(lock) {
        if (source is SourceId.HubConnection || source !in slots) return@synchronized null
        val token = ++requestSequence
        loadRequests[source] = token
        loadJobs.remove(source)?.cancel()
        slots[source]?.apply {
            loading = true
            error = null
        }
        publishLocked()
        token
    }

    private fun finishMutation(source: SourceId, token: Long) {
        synchronized(lock) {
            if (loadRequests[source] != token) return
            loadRequests.remove(source)
            slots[source]?.loading = false
            publishLocked()
        }
    }

    private fun removeSlotLocked(source: SourceId) {
        slots.remove(source)
        loadRequests.remove(source)
        loadJobs.remove(source)?.cancel()
    }

    private fun publishLocked() {
        val sections = slots.values.asSequence()
            .filter { it.loading || it.error != null || it.items.isNotEmpty() }
            .sortedBy { it.descriptor.order }
            .map {
                FavoritesSection(
                    source = it.descriptor.source,
                    title = it.descriptor.title,
                    items = it.items,
                    loading = it.loading,
                    error = it.error,
                )
            }
            .toList()
        mutableState.value = AggregatedFavoritesState(
            sections = sections,
            loading = sections.any(FavoritesSection::loading),
        )
    }

    private data class SectionSlot(
        var descriptor: SourceDescriptor,
        var items: List<CatalogItem> = emptyList(),
        var loading: Boolean = false,
        var error: CatalogLoadError? = null,
    )

    private data class SourceDescriptor(
        val source: SourceId,
        val title: String,
        val order: SectionOrder,
        val hubId: Long? = null,
        val hubName: String? = null,
        val hubBaseUrl: String? = null,
    )

    private data class SectionOrder(
        val tier: Int,
        val primary: Int,
        val secondary: Int,
    ) : Comparable<SectionOrder> {
        override fun compareTo(other: SectionOrder): Int =
            compareValuesBy(this, other, SectionOrder::tier, SectionOrder::primary, SectionOrder::secondary)
    }
}

internal data class HubPlaylist(
    val id: Long,
    val name: String,
)

private suspend fun discoverHubPlaylists(
    hubs: HubRegistry,
    hub: HubSource,
): CatalogResult<List<HubPlaylist>> {
    val client = hubs.clientFor(hub.id) ?: return CatalogResult.SignedOut
    return try {
        CatalogResult.Success(
            client.call { playlists(it) }.map { HubPlaylist(it.id, it.name) }
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: HubUnauthorizedException) {
        CatalogResult.SignedOut
    } catch (_: HubUnreachableException) {
        CatalogResult.Unreachable
    } catch (error: Throwable) {
        CatalogResult.Failed(error)
    }
}
