package com.buco7854.opentv.core.repo

import com.buco7854.opentv.core.model.Favorite
import com.buco7854.opentv.core.storage.FavoriteStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Application-level favorite operations shared by every presentation layer.
 *
 * Keeping toggle and key projection here prevents Android screens and server
 * transports from each reimplementing persistence policy.
 */
class FavoriteRepository(
    private val store: FavoriteStore,
) {
    fun observeAll(playlistId: Long): Flow<List<Favorite>> =
        store.observeAll(playlistId)

    fun observeKeys(playlistId: Long): Flow<Set<String>> =
        observeAll(playlistId).map { favorites -> favorites.mapTo(mutableSetOf()) { it.key } }

    suspend fun contains(playlistId: Long, key: String): Boolean =
        store.get(playlistId, key) != null

    suspend fun toggle(playlistId: Long, key: String, kind: Int): Boolean {
        val existing = store.get(playlistId, key)
        return if (existing == null) {
            store.add(Favorite(playlistId = playlistId, key = key, kind = kind))
            true
        } else {
            store.remove(playlistId, key)
            false
        }
    }

    suspend fun remove(playlistId: Long, key: String) =
        store.remove(playlistId, key)

    suspend fun removeAll(playlistId: Long, favorites: Iterable<FavoriteRef>) {
        favorites.forEach { store.remove(playlistId, it.key) }
    }

    suspend fun restoreAll(playlistId: Long, favorites: Iterable<FavoriteRef>) {
        favorites.forEach { store.add(Favorite(playlistId, it.key, it.kind)) }
    }
}

/** Persistence-independent identity used for batch favorite operations and Undo. */
data class FavoriteRef(
    val key: String,
    val kind: Int,
)
