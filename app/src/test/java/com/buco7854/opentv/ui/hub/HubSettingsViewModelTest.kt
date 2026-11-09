package com.buco7854.opentv.ui.hub

import androidx.lifecycle.ViewModelStore
import com.buco7854.opentv.core.model.HubSource
import com.buco7854.opentv.source.SourceId
import com.buco7854.opentv.ui.home.CatalogSourceEntry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HubSettingsViewModelTest {
    private fun hub(user: String? = "one") = HubSource(
        id = 4, name = "Living room server", baseUrl = "https://tv.example", userId = user,
        username = user, role = "ADMIN", addedMs = 1,
    )
    private fun rows(title: String) = listOf(CatalogSourceEntry(SourceId.Hub(4, 8), title, itemCount = 120))

    @Test
    fun `retry keeps the last good playlists and concurrent refreshes share one request`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val sources = MutableStateFlow(listOf(hub()))
            var calls = 0
            var fail = false
            val gate = CompletableDeferred<Unit>()
            val vm = HubSettingsViewModel(4, sources, { sources.value.firstOrNull() }, {}, {
                calls++
                if (calls == 1) gate.await()
                if (fail) error("offline")
                rows("Cinema")
            })
            store.put("server", vm)
            runCurrent()
            vm.refresh()
            runCurrent()
            assertEquals(1, calls)
            assertTrue(vm.state.value.loading)
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals("Cinema", vm.state.value.playlists.single().title)
            fail = true
            vm.refresh()
            advanceUntilIdle()
            assertTrue(vm.state.value.failed)
            assertEquals("Cinema", vm.state.value.playlists.single().title)
            fail = false
            vm.refresh()
            advanceUntilIdle()
            assertFalse(vm.state.value.failed)
            assertFalse(vm.state.value.loading)
        } finally {
            store.clear()
            advanceUntilIdle()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `an in flight catalog from the previous account is discarded after reauthentication`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val sources = MutableStateFlow(listOf(hub()))
            val gate = CompletableDeferred<Unit>()
            val vm = HubSettingsViewModel(4, sources, { sources.value.firstOrNull() }, {}, {
                gate.await()
                rows("Previous account cinema")
            })
            store.put("server", vm)
            runCurrent()
            sources.value = listOf(hub("two"))
            runCurrent()
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals("two", vm.state.value.source?.userId)
            assertTrue(vm.state.value.playlists.isEmpty())
        } finally {
            store.clear()
            advanceUntilIdle()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `signed out and removed servers stop loading without fetching a catalog`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val sources = MutableStateFlow(listOf(hub(null)))
            var fetched = false
            val vm = HubSettingsViewModel(4, sources, { sources.value.firstOrNull() }, {}, {
                fetched = true
                rows("Not entitled")
            })
            store.put("server", vm)
            advanceUntilIdle()
            assertFalse(fetched)
            assertFalse(vm.state.value.loading)
            assertTrue(vm.state.value.playlists.isEmpty())
            sources.value = emptyList()
            vm.refresh()
            advanceUntilIdle()
            assertNull(vm.state.value.source)
            assertTrue(vm.state.value.sourceLoaded)
            assertFalse(vm.state.value.loading)
        } finally {
            store.clear()
            advanceUntilIdle()
            Dispatchers.resetMain()
        }
    }
}
