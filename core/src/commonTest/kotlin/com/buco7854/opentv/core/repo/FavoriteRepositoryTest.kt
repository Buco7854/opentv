package com.buco7854.opentv.core.repo

import com.buco7854.opentv.core.model.Favorite
import com.buco7854.opentv.core.storage.FavoriteStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FavoriteRepositoryTest {
    @Test
    fun toggleAddsThenRemovesTheSameFavorite() = runTest {
        val store = FakeFavoriteStore()
        val repository = FavoriteRepository(store)

        assertTrue(repository.toggle(playlistId = 7, key = "movie", kind = 1))
        val added = store.get(7, "movie")
        assertEquals(7, added?.playlistId)
        assertEquals("movie", added?.key)
        assertEquals(1, added?.kind)

        assertFalse(repository.toggle(playlistId = 7, key = "movie", kind = 1))
        assertEquals(null, store.get(7, "movie"))
    }

    @Test
    fun batchRestoreAndRemovePreserveKinds() = runTest {
        val store = FakeFavoriteStore()
        val repository = FavoriteRepository(store)
        val favorites = listOf(FavoriteRef("live", 0), FavoriteRef("series", 2))

        repository.restoreAll(3, favorites)
        assertEquals(setOf(Favorite(3, "live", 0), Favorite(3, "series", 2)), store.rows.value.toSet())

        repository.removeAll(3, favorites)
        assertTrue(store.rows.value.isEmpty())
    }
}

private class FakeFavoriteStore : FavoriteStore {
    val rows = MutableStateFlow<List<Favorite>>(emptyList())

    override fun observeAll(playlistId: Long): Flow<List<Favorite>> = rows
    override suspend fun getAll(playlistId: Long): List<Favorite> =
        rows.value.filter { it.playlistId == playlistId }

    override suspend fun get(playlistId: Long, key: String): Favorite? =
        rows.value.firstOrNull { it.playlistId == playlistId && it.key == key }

    override suspend fun add(favorite: Favorite) {
        rows.value = rows.value.filterNot {
            it.playlistId == favorite.playlistId && it.key == favorite.key
        } + favorite
    }

    override suspend fun remove(playlistId: Long, key: String) {
        rows.value = rows.value.filterNot { it.playlistId == playlistId && it.key == key }
    }

    override suspend fun deleteForPlaylist(playlistId: Long) {
        rows.value = rows.value.filterNot { it.playlistId == playlistId }
    }
}
