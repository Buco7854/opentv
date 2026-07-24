package com.buco7854.opentv.ui.details

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.buco7854.opentv.AppGraph
import com.buco7854.opentv.OpenTvApp
import com.buco7854.opentv.core.log.rethrowCancellation
import com.buco7854.opentv.core.meta.CastMember
import com.buco7854.opentv.core.meta.castFromNames
import com.buco7854.opentv.core.meta.decodeCast
import com.buco7854.opentv.core.model.Channel
import com.buco7854.opentv.core.model.ChannelKind
import com.buco7854.opentv.core.model.Download
import com.buco7854.opentv.core.model.Metadata
import com.buco7854.opentv.core.model.XtreamSeries
import com.buco7854.opentv.core.repo.xtreamFavoriteKey
import com.buco7854.opentv.core.repo.xtreamSeriesKey
import com.buco7854.opentv.diag.ErrorLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal abstract class BaseDetailViewModel(
    protected val graph: AppGraph,
) : ViewModel() {
    val downloads: StateFlow<List<Download>> = graph.downloads.downloads
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val progressByUrl: StateFlow<Map<String, Float>> = graph.resume.progressByUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    suspend fun enqueue(channel: Channel): String? = graph.downloads.enqueue(channel)
}

internal data class MovieDetailState(
    val channel: Channel? = null,
    val metadata: Metadata? = null,
    val isFavorite: Boolean = false,
)

internal class MovieDetailViewModel(
    graph: AppGraph,
    private val channelId: Long,
) : BaseDetailViewModel(graph) {
    private val _state = MutableStateFlow(MovieDetailState())
    val state: StateFlow<MovieDetailState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val channel = graph.storage.channels.get(channelId) ?: return@launch
            _state.value = _state.value.copy(channel = channel)
            _state.value = _state.value.copy(
                isFavorite = graph.favorites.contains(channel.playlistId, channel.url),
            )
            val metadata = channel.xtreamStreamId?.let { graph.xtream.vodMetadata(channel) }
                ?: graph.metadata.forTitle(isSeries = false, rawName = channel.name)
            _state.value = _state.value.copy(metadata = metadata)
        }
    }

    fun toggleFavorite() {
        val channel = _state.value.channel ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(
                isFavorite = graph.favorites.toggle(channel.playlistId, channel.url, channel.kind),
            )
        }
    }
}

internal data class SeriesDetailState(
    val metadata: Metadata? = null,
    val isFavorite: Boolean = false,
)

internal class SeriesDetailViewModel(
    graph: AppGraph,
    private val playlistId: Long,
    private val seriesKey: String,
) : BaseDetailViewModel(graph) {
    val episodes: StateFlow<List<Channel>> = graph.storage.channels
        .observeEpisodes(playlistId, seriesKey)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _state = MutableStateFlow(SeriesDetailState())
    val state: StateFlow<SeriesDetailState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = SeriesDetailState(
                metadata = graph.metadata.forTitle(isSeries = true, rawName = seriesKey),
                isFavorite = graph.favorites.contains(playlistId, seriesKey),
            )
        }
    }

    fun toggleFavorite() {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                isFavorite = graph.favorites.toggle(playlistId, seriesKey, ChannelKind.SERIES),
            )
        }
    }
}

internal data class EpisodeDetailState(
    val episode: Channel? = null,
    val seriesTitle: String? = null,
    val metadata: Metadata? = null,
    val seriesCast: List<CastMember> = emptyList(),
)

internal class EpisodeDetailViewModel(
    graph: AppGraph,
    channelId: Long,
) : BaseDetailViewModel(graph) {
    private val _state = MutableStateFlow(EpisodeDetailState())
    val state: StateFlow<EpisodeDetailState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val episode = graph.storage.channels.get(channelId) ?: return@launch
            _state.value = _state.value.copy(episode = episode)
            val seriesKey = episode.seriesKey
            val (seriesTitle, seriesCast) =
                if (seriesKey != null && seriesKey.startsWith("xs:")) {
                    val series = seriesKey.removePrefix("xs:").toLongOrNull()
                        ?.let { graph.storage.xtreamSeries.get(episode.playlistId, it) }
                    series?.name to castFromNames(series?.castNames)
                } else {
                    seriesKey to seriesKey
                        ?.let { decodeCast(graph.metadata.forTitle(true, it)?.castJson) }
                        .orEmpty()
                }
            val season = episode.season
            val episodeNumber = episode.episode
            _state.value = _state.value.copy(
                seriesTitle = seriesTitle,
                seriesCast = seriesCast,
            )
            val metadata =
                if (episode.description == null &&
                    season != null &&
                    episodeNumber != null &&
                    seriesTitle != null
                ) {
                    graph.metadata.episodeInfo(seriesTitle, season, episodeNumber)
                } else {
                    null
                }
            _state.value = _state.value.copy(metadata = metadata)
        }
    }
}

internal data class XtreamSeriesState(
    val series: XtreamSeries? = null,
    val isFavorite: Boolean = false,
    val loading: Boolean = true,
    val error: Throwable? = null,
)

internal class XtreamSeriesViewModel(
    graph: AppGraph,
    private val playlistId: Long,
    private val seriesId: Long,
) : BaseDetailViewModel(graph) {
    val episodes: StateFlow<List<Channel>> = graph.storage.channels
        .observeEpisodes(playlistId, xtreamSeriesKey(seriesId))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _state = MutableStateFlow(XtreamSeriesState())
    val state: StateFlow<XtreamSeriesState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val series = graph.xtream.series(playlistId, seriesId)
            val favorite = graph.favorites.contains(playlistId, xtreamFavoriteKey(seriesId))
            _state.value = _state.value.copy(series = series, isFavorite = favorite)
            val error = try {
                graph.xtream.ensureEpisodes(playlistId, seriesId)
                null
            } catch (exception: Exception) {
                exception.rethrowCancellation()
                ErrorLog.log("Series episodes", exception)
                exception
            }
            _state.value = _state.value.copy(loading = false, error = error)
        }
    }

    fun toggleFavorite() {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                isFavorite = graph.favorites.toggle(
                    playlistId,
                    xtreamFavoriteKey(seriesId),
                    ChannelKind.SERIES,
                ),
            )
        }
    }
}

@Composable
internal inline fun <reified VM : ViewModel> detailViewModel(
    key: String,
    crossinline create: (AppGraph) -> VM,
): VM = viewModel(
    key = key,
    factory = viewModelFactory {
        initializer { create(OpenTvApp.graph) }
    },
)
