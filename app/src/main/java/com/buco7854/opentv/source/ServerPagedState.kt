package com.buco7854.opentv.source

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class ServerPageSnapshot<T>(
    val items: List<T> = emptyList(),
    val total: Int = 0,
    val seasons: List<Int> = emptyList(),
    val loading: Boolean = false,
    val error: CatalogLoadError? = null,
)

sealed interface CatalogLoadError {
    data object SignedOut : CatalogLoadError
    data object Unreachable : CatalogLoadError
    data class Failed(val cause: Throwable) : CatalogLoadError
}

class ServerPagedState<T>(
    private val scope: CoroutineScope,
    private val pageSize: Int = DEFAULT_CATALOG_PAGE_SIZE,
    private val keyOf: ((T) -> Any)? = null,
    private val loader: suspend (offset: Int, limit: Int) -> CatalogResult<Page<T>>,
) {
    init {
        require(pageSize > 0) { "pageSize must be positive" }
    }

    private val mutableState = MutableStateFlow(ServerPageSnapshot<T>())
    val state: StateFlow<ServerPageSnapshot<T>> = mutableState.asStateFlow()

    private var generation = 0L
    private var request: Job? = null
    private var hasLoaded = false
    private var endReached = false
    private var nextOffset = 0

    init {
        loadMore()
    }

    fun loadMore() {
        val current = mutableState.value
        if (current.loading || current.error != null ||
            endReached || (hasLoaded && current.items.size >= current.total)
        ) return
        request(offset = nextOffset, replace = false)
    }

    fun retry() {
        if (mutableState.value.loading || mutableState.value.error == null) return
        request(offset = nextOffset, replace = false)
    }

    fun refresh() {
        generation++
        request?.cancel()
        request = null
        hasLoaded = false
        endReached = false
        nextOffset = 0
        mutableState.value = ServerPageSnapshot(loading = true)
        request(offset = 0, replace = true, generation = generation)
    }

    fun cancel() {
        generation++
        request?.cancel()
        request = null
        mutableState.value = mutableState.value.copy(loading = false)
    }

    private fun request(
        offset: Int,
        replace: Boolean,
        generation: Long = this.generation,
    ) {
        mutableState.value = mutableState.value.copy(loading = true, error = null)
        request = scope.launch {
            val result = try {
                loader(offset, pageSize)
            } catch (cancelled: CancellationException) {
                if (!currentCoroutineContext().isActive) throw cancelled
                CatalogResult.Failed(cancelled)
            } catch (error: Throwable) {
                CatalogResult.Failed(error)
            }
            if (generation != this@ServerPagedState.generation) return@launch
            request = null
            when (result) {
                is CatalogResult.Success -> {
                    if (!replace &&
                        mutableState.value.total > 0 &&
                        result.value.total < mutableState.value.total
                    ) {
                        this@ServerPagedState.generation++
                        hasLoaded = false
                        endReached = false
                        nextOffset = 0
                        mutableState.value = ServerPageSnapshot(loading = true)
                        request(
                            offset = 0,
                            replace = true,
                            generation = this@ServerPagedState.generation,
                        )
                        return@launch
                    }
                    hasLoaded = true
                    nextOffset = offset + result.value.items.size
                    val items = if (replace || keyOf == null) {
                        if (replace) result.value.items
                        else mutableState.value.items + result.value.items
                    } else {
                        val keys = mutableState.value.items.mapTo(mutableSetOf(), keyOf)
                        mutableState.value.items + result.value.items.filter {
                            keys.add(keyOf(it))
                        }
                    }
                    endReached = result.value.items.isEmpty() ||
                        nextOffset >= result.value.total
                    mutableState.value = ServerPageSnapshot(
                        items = items,
                        total = result.value.total,
                        seasons = result.value.seasons.ifEmpty {
                            mutableState.value.seasons
                        },
                        loading = false,
                    )
                }
                CatalogResult.SignedOut -> fail(CatalogLoadError.SignedOut)
                CatalogResult.Unreachable -> fail(CatalogLoadError.Unreachable)
                is CatalogResult.Failed -> fail(CatalogLoadError.Failed(result.cause))
            }
        }
    }

    private fun fail(error: CatalogLoadError) {
        mutableState.value = mutableState.value.copy(loading = false, error = error)
    }
}
