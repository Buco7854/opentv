package com.buco7854.opentv.ui.components

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.buco7854.opentv.AppGraph
import com.buco7854.opentv.OpenTvApp
import com.buco7854.opentv.core.model.Channel
import com.buco7854.opentv.core.repo.GuideEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal class GuideViewModel(
    private val graph: AppGraph,
    private val channel: Channel,
) : ViewModel() {
    private val _entries = MutableStateFlow<List<GuideEntry>?>(null)
    val entries: StateFlow<List<GuideEntry>?> = _entries.asStateFlow()

    init {
        viewModelScope.launch {
            _entries.value = graph.xtream.guideFor(channel)
        }
    }

    suspend fun catchupUrlFor(entry: GuideEntry): String? =
        graph.xtream.catchupUrlFor(channel, entry.startMs, entry.endMs)
}

@Composable
internal fun guideViewModel(channel: Channel): GuideViewModel = viewModel(
    key = "Guide-${channel.playlistId}-${channel.id}-${channel.url.hashCode()}",
    factory = viewModelFactory {
        initializer { GuideViewModel(OpenTvApp.graph, channel) }
    },
)
