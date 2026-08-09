package com.buco7854.opentv.ui.details

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.buco7854.opentv.AppGraph
import com.buco7854.opentv.OpenTvApp
import com.buco7854.opentv.core.meta.CastMember
import com.buco7854.opentv.core.meta.castFromNames
import com.buco7854.opentv.core.meta.decodeCast
import com.buco7854.opentv.core.model.Channel
import com.buco7854.opentv.core.model.Download
import com.buco7854.opentv.core.model.Metadata
import com.buco7854.opentv.source.CatalogDetail
import com.buco7854.opentv.source.CatalogGateway
import com.buco7854.opentv.source.CatalogItem
import com.buco7854.opentv.source.CatalogLoadError
import com.buco7854.opentv.source.CatalogResult
import com.buco7854.opentv.source.ContentRef
import com.buco7854.opentv.source.ServerPagedState
import com.buco7854.opentv.source.SourceId
import com.buco7854.opentv.source.encode
import com.buco7854.opentv.source.valueOrNull
import com.buco7854.opentv.source.toCatalogItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal abstract class BaseDetailViewModel(
    protected val graph: AppGraph,
    val sourceId: SourceId,
    protected val gateway: CatalogGateway = graph.catalogFor(sourceId),
) : ViewModel() {
    val downloads: StateFlow<List<Download>> = graph.downloads.downloads
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val progressByUrl: StateFlow<Map<String, Float>> = graph.resume.progressByUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    suspend fun enqueue(item: CatalogItem): String? {
        val hub = sourceId as? SourceId.Hub
        val hubRef = item.ref as? ContentRef.HubContent
        if (hub != null && hubRef != null) {
            return graph.downloads.enqueueHub(hub.hubId, hubRef.contentId, item.title)
        }
        val channel = localChannel(item.ref) ?: return null
        return graph.downloads.enqueue(channel)
    }

    protected suspend fun localChannel(ref: ContentRef): Channel? {
        val local = sourceId as? SourceId.LocalPlaylist ?: return null
        val localRef = ref as? ContentRef.LocalUrl ?: return null
        return localRef.channelId.takeIf { it != 0L }?.let { graph.storage.channels.get(it) }
            ?.takeIf {
                it.playlistId == local.playlistId && it.url == localRef.url
            }
            ?: graph.storage.channels.getByUrl(local.playlistId, localRef.url)
    }

    protected suspend fun <T> safeCall(
        call: suspend () -> CatalogResult<T>,
    ): CatalogResult<T> = try {
        call()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        CatalogResult.Failed(error)
    }
}

internal data class MovieDetailState(
    val detail: CatalogDetail? = null,
    val metadata: Metadata? = null,
    val isFavorite: Boolean = false,
    val loading: Boolean = true,
    val error: CatalogLoadError? = null,
)

internal class MovieDetailViewModel(
    graph: AppGraph,
    sourceId: SourceId,
    private val ref: ContentRef,
) : BaseDetailViewModel(graph, sourceId) {
    private val mutableState = MutableStateFlow(MovieDetailState())
    val state: StateFlow<MovieDetailState> = mutableState

    init {
        load()
    }

    fun retry() = load()

    fun toggleFavorite() {
        viewModelScope.launch {
            when (val result = safeCall { gateway.toggleFavorite(ref) }) {
                is CatalogResult.Success ->
                    mutableState.value = mutableState.value.copy(isFavorite = result.value)
                CatalogResult.SignedOut -> fail(CatalogLoadError.SignedOut)
                CatalogResult.Unreachable -> fail(CatalogLoadError.Unreachable)
                is CatalogResult.Failed -> fail(CatalogLoadError.Failed(result.cause))
            }
        }
    }

    private fun load() {
        mutableState.value = mutableState.value.copy(loading = true, error = null)
        viewModelScope.launch {
            when (val result = safeCall { gateway.detail(ref) }) {
                is CatalogResult.Success -> {
                    val detail = result.value
                    if (detail == null) {
                        fail(CatalogLoadError.Failed(NoSuchElementException("Content not found")))
                        return@launch
                    }
                    mutableState.value = mutableState.value.copy(detail = detail)
                    val channel = localChannel(ref)
                    val metadata: Metadata? = if (channel != null) {
                        channel.xtreamStreamId?.let { graph.xtream.vodMetadata(channel) }
                            ?: graph.metadata.forTitle(isSeries = false, rawName = channel.name)
                    } else {
                        // No local channel row means the source owns this film, so its cast
                        // and rating have to be asked for. Losing them is not worth failing
                        // a page that has already loaded, so a refusal leaves them absent.
                        gateway.movieMetadata(ref).valueOrNull()
                    }
                    val favorite = when (
                        val favoriteResult = safeCall { gateway.isFavorite(ref) }
                    ) {
                        is CatalogResult.Success -> favoriteResult.value
                        CatalogResult.SignedOut -> {
                            fail(CatalogLoadError.SignedOut)
                            return@launch
                        }
                        CatalogResult.Unreachable -> {
                            fail(CatalogLoadError.Unreachable)
                            return@launch
                        }
                        is CatalogResult.Failed -> {
                            fail(CatalogLoadError.Failed(favoriteResult.cause))
                            return@launch
                        }
                    }
                    mutableState.value = MovieDetailState(
                        detail = detail,
                        metadata = metadata,
                        isFavorite = favorite,
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

internal data class SeriesDetailState(
    val detail: CatalogDetail? = null,
    val metadata: Metadata? = null,
    val isFavorite: Boolean = false,
    val episodeTotal: Int = 0,
    val seasons: List<Int> = emptyList(),
    val loading: Boolean = true,
    val error: CatalogLoadError? = null,
)

internal open class SeriesDetailViewModel(
    graph: AppGraph,
    sourceId: SourceId,
    protected val ref: ContentRef,
    private val seriesKeyHint: String? = null,
    private val seriesIdHint: String? = null,
) : BaseDetailViewModel(graph, sourceId) {
    protected val mutableEpisodes = MutableStateFlow<List<CatalogItem>>(emptyList())
    val episodes: StateFlow<List<CatalogItem>> = mutableEpisodes

    protected val mutableState = MutableStateFlow(SeriesDetailState())
    val state: StateFlow<SeriesDetailState> = mutableState

    private var pager: ServerPagedState<CatalogItem>? = null
    private var pagerJob: Job? = null

    init {
        load()
    }

    fun retry() = load()
    fun loadMore() = pager?.loadMore()

    fun toggleFavorite() {
        viewModelScope.launch {
            when (val result = safeCall { gateway.toggleFavorite(ref) }) {
                is CatalogResult.Success ->
                    mutableState.value = mutableState.value.copy(isFavorite = result.value)
                CatalogResult.SignedOut -> fail(CatalogLoadError.SignedOut)
                CatalogResult.Unreachable -> fail(CatalogLoadError.Unreachable)
                is CatalogResult.Failed -> fail(CatalogLoadError.Failed(result.cause))
            }
        }
    }

    protected open suspend fun localMetadata(detail: CatalogDetail): Metadata? =
        graph.metadata.forTitle(isSeries = true, rawName = detail.item.title)

    protected open fun seriesKey(detail: CatalogDetail): String =
        seriesKeyHint ?: detail.item.seriesKey ?: detail.item.title

    private fun load() {
        pager?.cancel()
        pagerJob?.cancel()
        mutableState.value = mutableState.value.copy(loading = true, error = null)
        viewModelScope.launch {
            val detailCall = seriesKeyHint?.let { key ->
                suspend { gateway.seriesDetail(ref, key, seriesIdHint) }
            } ?: suspend { gateway.detail(ref) }
            when (val result = safeCall(detailCall)) {
                is CatalogResult.Success -> {
                    val detail = result.value
                    if (detail == null) {
                        fail(CatalogLoadError.Failed(NoSuchElementException("Content not found")))
                        return@launch
                    }
                    val favorite = when (
                        val favoriteResult = safeCall { gateway.isFavorite(ref) }
                    ) {
                        is CatalogResult.Success -> favoriteResult.value
                        CatalogResult.SignedOut -> {
                            fail(CatalogLoadError.SignedOut)
                            return@launch
                        }
                        CatalogResult.Unreachable -> {
                            fail(CatalogLoadError.Unreachable)
                            return@launch
                        }
                        is CatalogResult.Failed -> {
                            fail(CatalogLoadError.Failed(favoriteResult.cause))
                            return@launch
                        }
                    }
                    mutableState.value = SeriesDetailState(
                        detail = detail,
                        metadata = if (sourceId is SourceId.LocalPlaylist) {
                            localMetadata(detail)
                        } else null,
                        isFavorite = favorite,
                        loading = true,
                    )
                    loadEpisodes(seriesKey(detail))
                }
                CatalogResult.SignedOut -> fail(CatalogLoadError.SignedOut)
                CatalogResult.Unreachable -> fail(CatalogLoadError.Unreachable)
                is CatalogResult.Failed -> fail(CatalogLoadError.Failed(result.cause))
            }
        }
    }

    private fun loadEpisodes(key: String) {
        val local = sourceId as? SourceId.LocalPlaylist
        if (local != null) {
            pager = null
            pagerJob = viewModelScope.launch {
                if (key.startsWith("xs:")) {
                    when (val result = safeCall {
                        gateway.episodes(key, offset = 0, limit = 1)
                    }) {
                        CatalogResult.SignedOut -> {
                            fail(CatalogLoadError.SignedOut)
                            return@launch
                        }
                        CatalogResult.Unreachable -> {
                            fail(CatalogLoadError.Unreachable)
                            return@launch
                        }
                        is CatalogResult.Failed -> {
                            fail(CatalogLoadError.Failed(result.cause))
                            return@launch
                        }
                        is CatalogResult.Success -> Unit
                    }
                }
                graph.storage.channels.observeEpisodes(local.playlistId, key).collect { rows ->
                    mutableEpisodes.value = rows.map { it.toCatalogItem() }
                    mutableState.value = mutableState.value.copy(
                        episodeTotal = rows.size,
                        seasons = rows.mapNotNull { it.season }.distinct().sorted(),
                        loading = false,
                        error = null,
                    )
                }
            }
            return
        }
        val next = ServerPagedState(viewModelScope, keyOf = { it.ref }) { offset, limit ->
            gateway.episodes(key, offset = offset, limit = limit)
        }
        pager = next
        pagerJob = viewModelScope.launch {
            next.state.collect { page ->
                mutableEpisodes.value = page.items
                mutableState.value = mutableState.value.copy(
                    episodeTotal = page.total,
                    seasons = page.seasons,
                    loading = page.loading,
                    error = page.error,
                )
            }
        }
    }

    protected fun fail(error: CatalogLoadError) {
        mutableState.value = mutableState.value.copy(loading = false, error = error)
    }
}

internal data class EpisodeDetailState(
    val detail: CatalogDetail? = null,
    val seriesTitle: String? = null,
    val metadata: Metadata? = null,
    val seriesCast: List<CastMember> = emptyList(),
    val loading: Boolean = true,
    val error: CatalogLoadError? = null,
)

internal class EpisodeDetailViewModel(
    graph: AppGraph,
    sourceId: SourceId,
    private val ref: ContentRef,
) : BaseDetailViewModel(graph, sourceId) {
    private val mutableState = MutableStateFlow(EpisodeDetailState())
    val state: StateFlow<EpisodeDetailState> = mutableState

    init {
        load()
    }

    fun retry() = load()

    private fun load() {
        mutableState.value = mutableState.value.copy(loading = true, error = null)
        viewModelScope.launch {
            when (val result = safeCall { gateway.detail(ref) }) {
                is CatalogResult.Success -> {
                    val detail = result.value
                    if (detail == null) {
                        fail(CatalogLoadError.Failed(NoSuchElementException("Content not found")))
                        return@launch
                    }
                    val item = detail.item
                    val localEpisode = localChannel(ref)
                    val key = item.seriesKey
                    val (seriesTitle, seriesCast) =
                        if (localEpisode != null && key?.startsWith("xs:") == true) {
                            val series = key.removePrefix("xs:").toLongOrNull()
                                ?.let {
                                    graph.storage.xtreamSeries.get(localEpisode.playlistId, it)
                                }
                            series?.name to castFromNames(series?.castNames)
                        } else if (localEpisode != null) {
                            key to key
                                ?.let { decodeCast(graph.metadata.forTitle(true, it)?.castJson) }
                                .orEmpty()
                        } else {
                            key to emptyList()
                        }
                    val metadata =
                        if (localEpisode != null &&
                            detail.description == null &&
                            item.season != null &&
                            item.episode != null &&
                            seriesTitle != null
                        ) {
                            graph.metadata.episodeInfo(
                                seriesTitle,
                                item.season,
                                item.episode,
                            )
                        } else null
                    mutableState.value = EpisodeDetailState(
                        detail = detail,
                        seriesTitle = seriesTitle,
                        metadata = metadata,
                        seriesCast = seriesCast,
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

internal class XtreamSeriesViewModel(
    graph: AppGraph,
    sourceId: SourceId,
    ref: ContentRef,
    seriesKey: String?,
    seriesId: String?,
) : SeriesDetailViewModel(graph, sourceId, ref, seriesKey, seriesId) {
    override suspend fun localMetadata(detail: CatalogDetail): Metadata? = null
}

@Composable
internal inline fun <reified VM : ViewModel> detailViewModel(
    sourceId: SourceId,
    ref: ContentRef,
    crossinline create: (AppGraph) -> VM,
): VM = viewModel(
    key = "${VM::class.java.simpleName}-${sourceId.encode()}-${ref.encode()}",
    factory = viewModelFactory {
        initializer { create(OpenTvApp.graph) }
    },
)
