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
) : ViewModel() {
    private val _entries = MutableStateFlow<List<GuideEntry>?>(null)
    val entries: StateFlow<List<GuideEntry>?> = _entries.asStateFlow()

    private var channel: Channel? = null

    fun show(target: Channel) {
        channel = target
        _entries.value = null
        viewModelScope.launch {
            val loaded = graph.xtream.guideFor(target)
            if (channel?.id == target.id) _entries.value = loaded
        }
    }

    suspend fun catchupUrlFor(entry: GuideEntry): String? =
        channel?.let { graph.xtream.catchupUrlFor(it, entry.startMs, entry.endMs) }
}

@Composable
internal fun guideViewModel(): GuideViewModel = viewModel(
    key = "Guide",
    factory = viewModelFactory {
        initializer { GuideViewModel(OpenTvApp.graph) }
    },
)
