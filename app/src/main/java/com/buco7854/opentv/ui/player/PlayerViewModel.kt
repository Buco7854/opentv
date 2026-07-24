package com.buco7854.opentv.ui.player

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
import com.buco7854.opentv.data.prefs.PlayerSettings
import com.buco7854.opentv.data.prefs.SubtitleStyle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class PlayerBootstrap(
    val settings: PlayerSettings,
    val resumePositionMs: Long,
)

internal data class NowNextProgramme(
    val currentTitle: String,
    val currentEndMs: Long,
    val nextTitle: String?,
)

/**
 * Application boundary for the player screen.
 *
 * The player UI must not know whether catalog, guide, progress, or preferences
 * are backed by Room, DataStore, or a future provider adapter.
 */
internal interface PlayerDataSource {
    val settings: Flow<PlayerSettings>

    suspend fun channelFor(playlistId: Long, url: String): Channel?
    suspend fun upcoming(playlistId: Long, tvgId: String): NowNextProgramme?
    suspend fun guideFor(channel: Channel): List<GuideEntry>
    suspend fun catchupUrlFor(channel: Channel, entry: GuideEntry): String?
    suspend fun resumePositionFor(url: String): Long?
    suspend fun saveSettings(settings: PlayerSettings)
    fun saveProgress(url: String, positionMs: Long, durationMs: Long)
    fun clearProgress(url: String)
}

private class LocalPlayerDataSource(
    private val graph: AppGraph,
) : PlayerDataSource {
    override val settings: Flow<PlayerSettings> = graph.playerPrefs.settings

    override suspend fun channelFor(playlistId: Long, url: String): Channel? =
        graph.storage.channels.getByUrl(playlistId, url)

    override suspend fun upcoming(playlistId: Long, tvgId: String): NowNextProgramme? {
        val programmes = graph.epg.upcoming(playlistId, tvgId, limit = 2)
        val current = programmes.firstOrNull() ?: return null
        return NowNextProgramme(
            currentTitle = current.title,
            currentEndMs = current.endMs,
            nextTitle = programmes.getOrNull(1)?.title,
        )
    }

    override suspend fun guideFor(channel: Channel): List<GuideEntry> =
        graph.xtream.guideFor(channel)

    override suspend fun catchupUrlFor(channel: Channel, entry: GuideEntry): String? =
        graph.xtream.catchupUrlFor(channel, entry.startMs, entry.endMs)

    override suspend fun resumePositionFor(url: String): Long? =
        graph.resume.resumePositionFor(url)

    override suspend fun saveSettings(settings: PlayerSettings) {
        graph.playerPrefs.save(settings)
    }

    override fun saveProgress(url: String, positionMs: Long, durationMs: Long) {
        graph.resume.save(url, positionMs, durationMs)
    }

    override fun clearProgress(url: String) {
        graph.resume.clear(url)
    }
}

internal class PlayerViewModel(
    private val source: PlayerDataSource,
    private val url: String,
    private val playlistId: Long,
    private val tvgId: String?,
) : ViewModel() {

    private val settingsMutex = Mutex()
    private val _bootstrap = MutableStateFlow<PlayerBootstrap?>(null)
    val bootstrap: StateFlow<PlayerBootstrap?> = _bootstrap.asStateFlow()

    private val _settings = MutableStateFlow<PlayerSettings?>(null)
    val settings: StateFlow<PlayerSettings?> = _settings.asStateFlow()

    private val _channel = MutableStateFlow<Channel?>(null)
    val channel: StateFlow<Channel?> = _channel.asStateFlow()

    private val _nowNext = MutableStateFlow<NowNextProgramme?>(null)
    val nowNext: StateFlow<NowNextProgramme?> = _nowNext.asStateFlow()

    private val _guideEntries = MutableStateFlow<List<GuideEntry>?>(null)
    val guideEntries: StateFlow<List<GuideEntry>?> = _guideEntries.asStateFlow()

    init {
        viewModelScope.launch {
            source.settings.collect { value ->
                _settings.value = value
                if (_bootstrap.value == null) {
                    _bootstrap.value = PlayerBootstrap(
                        settings = value,
                        resumePositionMs = source.resumePositionFor(url) ?: 0L,
                    )
                }
            }
        }
        if (playlistId > 0) {
            viewModelScope.launch {
                _channel.value = source.channelFor(playlistId, url)
            }
        }
        if (playlistId > 0 && tvgId != null) {
            viewModelScope.launch {
                _nowNext.value = source.upcoming(playlistId, tvgId)
            }
        }
    }

    fun loadGuide() {
        val selectedChannel = _channel.value ?: return
        if (_guideEntries.value != null) return
        viewModelScope.launch {
            _guideEntries.value = source.guideFor(selectedChannel)
        }
    }

    suspend fun catchupUrlFor(entry: GuideEntry): String? =
        _channel.value?.let { source.catchupUrlFor(it, entry) }

    fun saveResizeMode(resizeMode: Int) {
        updateSettings { copy(resizeMode = resizeMode) }
    }

    fun saveSubtitleStyle(style: SubtitleStyle) {
        updateSettings { copy(subtitleStyle = style) }
    }

    fun saveProgress(positionMs: Long, durationMs: Long) {
        source.saveProgress(url, positionMs, durationMs)
    }

    fun clearProgress() {
        source.clearProgress(url)
    }

    private fun updateSettings(transform: PlayerSettings.() -> PlayerSettings) {
        viewModelScope.launch {
            settingsMutex.withLock {
                source.saveSettings(source.settings.first().transform())
            }
        }
    }
}

@Composable
internal fun playerViewModel(
    url: String,
    playlistId: Long,
    tvgId: String?,
): PlayerViewModel = viewModel(
    key = "PlayerViewModel-$playlistId-${url.hashCode()}-${tvgId.hashCode()}",
    factory = viewModelFactory {
        initializer {
            PlayerViewModel(LocalPlayerDataSource(OpenTvApp.graph), url, playlistId, tvgId)
        }
    },
)
