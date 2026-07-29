package com.buco7854.opentv.ui.components

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.buco7854.opentv.AppGraph
import com.buco7854.opentv.OpenTvApp
import com.buco7854.opentv.source.CatalogGateway
import com.buco7854.opentv.source.CatalogGuideEntry
import com.buco7854.opentv.source.CatalogItem
import com.buco7854.opentv.source.CatalogLoadError
import com.buco7854.opentv.source.CatalogResult
import com.buco7854.opentv.source.ContentRef
import com.buco7854.opentv.source.SourceId
import com.buco7854.opentv.source.encode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class GuideState(
    val entries: List<CatalogGuideEntry>? = null,
    val error: CatalogLoadError? = null,
)

internal class GuideViewModel(
    val sourceId: SourceId,
    private val gateway: CatalogGateway,
    private val graph: AppGraph?,
) : ViewModel() {
    private val mutableState = MutableStateFlow(GuideState())
    val state: StateFlow<GuideState> = mutableState

    private var item: CatalogItem? = null
    private var loadGeneration = 0L

    fun show(target: CatalogItem) {
        item = target
        load(target)
    }

    fun retry() {
        item?.let(::load)
    }

    suspend fun catchupUrlFor(entry: CatalogGuideEntry): String? {
        val local = sourceId as? SourceId.LocalPlaylist ?: return null
        val ref = item?.ref as? ContentRef.LocalUrl ?: return null
        val currentGraph = graph ?: return null
        val channel = ref.channelId.takeIf { it != 0L }
            ?.let { currentGraph.storage.channels.get(it) }
            ?.takeIf { it.playlistId == local.playlistId && it.url == ref.url }
            ?: currentGraph.storage.channels.getByUrl(local.playlistId, ref.url)
            ?: return null
        return currentGraph.xtream.catchupUrlFor(channel, entry.startMs, entry.endMs)
    }

    private fun load(target: CatalogItem) {
        val generation = ++loadGeneration
        mutableState.value = GuideState()
        viewModelScope.launch {
            val result = try {
                gateway.guideFor(target.ref)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                CatalogResult.Failed(error)
            }
            if (generation != loadGeneration || item?.ref != target.ref) return@launch
            mutableState.value = when (result) {
                is CatalogResult.Success -> GuideState(entries = result.value)
                CatalogResult.SignedOut -> GuideState(error = CatalogLoadError.SignedOut)
                CatalogResult.Unreachable -> GuideState(error = CatalogLoadError.Unreachable)
                is CatalogResult.Failed ->
                    GuideState(error = CatalogLoadError.Failed(result.cause))
            }
        }
    }
}

@Composable
internal fun guideViewModel(sourceId: SourceId): GuideViewModel = viewModel(
    key = "Guide-${sourceId.encode()}",
    factory = viewModelFactory {
        initializer {
            GuideViewModel(
                sourceId = sourceId,
                gateway = OpenTvApp.graph.catalogFor(sourceId),
                graph = OpenTvApp.graph,
            )
        }
    },
)
