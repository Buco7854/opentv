package com.buco7854.opentv.source

import java.util.concurrent.ConcurrentHashMap

/**
 * Process-scoped, in-memory cache of the last successful Hub catalog response per
 * playlist/kind (and per playlist/kind/group for item pages). `BrowseViewModel` is
 * scoped to its `NavBackStackEntry`, so dock navigation or a cold ViewModel after being
 * backgrounded a long time otherwise starts every catalog fetch from a blank state. This
 * cache lets a freshly (re)created ViewModel seed itself with the previous screen's data
 * immediately, while a fresh network fetch still runs in the background.
 */
class HubCatalogCache {
    private data class GroupsKey(val source: SourceId.Hub, val kind: Int)
    private data class ItemsKey(val source: SourceId.Hub, val kind: Int, val group: String)

    private val groupsCache = ConcurrentHashMap<GroupsKey, List<CatalogGroup>>()
    private val itemsCache = ConcurrentHashMap<ItemsKey, List<CatalogItem>>()

    fun groups(source: SourceId.Hub, kind: Int): List<CatalogGroup>? =
        groupsCache[GroupsKey(source, kind)]

    fun putGroups(source: SourceId.Hub, kind: Int, groups: List<CatalogGroup>) {
        groupsCache[GroupsKey(source, kind)] = groups
    }

    fun items(source: SourceId.Hub, kind: Int, group: String): List<CatalogItem>? =
        itemsCache[ItemsKey(source, kind, group)]

    fun putItems(source: SourceId.Hub, kind: Int, group: String, items: List<CatalogItem>) {
        itemsCache[ItemsKey(source, kind, group)] = items
    }

    /**
     * Drops every cached response for a hub. A hub id can be reused by a different
     * account (sign-out followed by a different sign-in, or reauthenticating the same
     * slot), and this cache is not scoped to a session, so it must be cleared whenever
     * that hub's identity changes -- otherwise the next account to sign into that slot
     * would briefly see the previous account's cached catalog.
     */
    fun clearHub(hubId: Long) {
        groupsCache.keys.removeAll { it.source.hubId == hubId }
        itemsCache.keys.removeAll { it.source.hubId == hubId }
    }
}
