package com.buco7854.opentv.ui.player

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.C
import com.buco7854.opentv.AppGraph
import com.buco7854.opentv.OpenTvApp
import com.buco7854.opentv.contract.ClientCapabilitiesDto
import com.buco7854.opentv.contract.ResumePointDto
import com.buco7854.opentv.core.model.Channel
import com.buco7854.opentv.core.repo.GuideEntry
import com.buco7854.opentv.core.repo.ResumeRepository
import com.buco7854.opentv.data.prefs.PlayerSettings
import com.buco7854.opentv.data.prefs.SubtitleStyle
import com.buco7854.opentv.hub.MediaCapabilityReporter
import com.buco7854.opentv.hub.ReportedCapabilities
import com.buco7854.opentv.hub.playback.HubPlaybackSnapshot
import com.buco7854.opentv.hub.playback.HubPlaybackState
import com.buco7854.opentv.source.CatalogResult
import com.buco7854.opentv.source.ContentRef
import com.buco7854.opentv.source.SourceId
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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

internal enum class PlayerProblem {
    PLAYBACK_ENDED,
    SIGNED_OUT,
    AT_CAPACITY,
    FAILED,
}

internal data class PlayerMediaSource(
    val url: String,
    val startPositionMs: Long? = null,
)

internal data class HubAudioTracks(
    val names: List<String>,
    val selectedIndex: Int,
)

internal interface PlayerDataSource {
    val settings: Flow<PlayerSettings>

    suspend fun channelFor(target: PlayerTarget): Channel?
    suspend fun upcoming(target: PlayerTarget): NowNextProgramme?
    suspend fun guideFor(target: PlayerTarget): List<GuideEntry>
    suspend fun catchupTargetFor(target: PlayerTarget, entry: GuideEntry): PlayerTarget?
    suspend fun resumePositionFor(target: PlayerTarget): Long?
    suspend fun saveSettings(settings: PlayerSettings)
    fun saveProgress(target: PlayerTarget, positionMs: Long, durationMs: Long)
    fun clearProgress(target: PlayerTarget)
}

private class LocalPlayerDataSource(
    private val graph: AppGraph,
) : PlayerDataSource {
    override val settings: Flow<PlayerSettings> = graph.playerPrefs.settings

    override suspend fun channelFor(target: PlayerTarget): Channel? {
        val local = target as? PlayerTarget.LocalUrl ?: return null
        return graph.storage.channels.getByUrl(local.playlistId, local.url)
    }

    override suspend fun upcoming(target: PlayerTarget): NowNextProgramme? {
        val local = target as? PlayerTarget.LocalUrl ?: return null
        val tvgId = local.tvgId ?: return null
        val programmes = graph.epg.upcoming(local.playlistId, tvgId, limit = 2)
        val current = programmes.firstOrNull() ?: return null
        return NowNextProgramme(
            currentTitle = current.title,
            currentEndMs = current.endMs,
            nextTitle = programmes.getOrNull(1)?.title,
        )
    }

    override suspend fun guideFor(target: PlayerTarget): List<GuideEntry> {
        val channel = channelFor(target) ?: return emptyList()
        return graph.xtream.guideFor(channel)
    }

    override suspend fun catchupTargetFor(
        target: PlayerTarget,
        entry: GuideEntry,
    ): PlayerTarget? {
        val local = target as? PlayerTarget.LocalUrl ?: return null
        val channel = channelFor(target) ?: return null
        val url = graph.xtream.catchupUrlFor(channel, entry.startMs, entry.endMs) ?: return null
        return PlayerTarget.LocalUrl(
            url = url,
            title = "${channel.name} · ${entry.title}",
            playlistId = local.playlistId,
            tvgId = local.tvgId,
            live = false,
        )
    }

    override suspend fun resumePositionFor(target: PlayerTarget): Long? =
        (target as? PlayerTarget.LocalUrl)?.let { graph.resume.resumePositionFor(it.url) }

    override suspend fun saveSettings(settings: PlayerSettings) {
        graph.playerPrefs.save(settings)
    }

    override fun saveProgress(target: PlayerTarget, positionMs: Long, durationMs: Long) {
        val local = target as? PlayerTarget.LocalUrl ?: return
        graph.resume.save(local.url, positionMs, durationMs)
    }

    override fun clearProgress(target: PlayerTarget) {
        val local = target as? PlayerTarget.LocalUrl ?: return
        graph.resume.clear(local.url)
    }
}

private class HubPlayerDataSource(
    private val graph: AppGraph,
    private val source: SourceId.Hub,
) : PlayerDataSource {
    override val settings: Flow<PlayerSettings> = graph.playerPrefs.settings

    override suspend fun channelFor(target: PlayerTarget): Channel? = null

    override suspend fun upcoming(target: PlayerTarget): NowNextProgramme? = null

    override suspend fun guideFor(target: PlayerTarget): List<GuideEntry> =
        when (val result = graph.catalogFor(source).guideFor(target.contentRef)) {
            is CatalogResult.Success -> result.value.map {
                GuideEntry(it.title, it.description, it.startMs, it.endMs, it.replayable)
            }
            else -> emptyList()
        }

    override suspend fun catchupTargetFor(
        target: PlayerTarget,
        entry: GuideEntry,
    ): PlayerTarget? {
        if (!entry.replayable) return null
        val content = target.contentRef as? ContentRef.HubContent ?: return null
        return PlayerTarget.HubCatchUp(
            hubId = source.hubId,
            playlistId = source.playlistId,
            contentId = content.contentId,
            title = "${target.title} · ${entry.title}",
            startMs = entry.startMs,
            durationMs = (entry.endMs - entry.startMs).coerceAtLeast(0),
        )
    }

    override suspend fun resumePositionFor(target: PlayerTarget): Long? {
        if (target.live) return null
        val content = target.contentRef as? ContentRef.HubContent ?: return null
        val client = graph.hubs.clientFor(source.hubId) ?: return null
        return try {
            client.call { resume(it) }
                .firstOrNull { it.contentId == content.contentId }
                ?.positionMs
                ?.takeIf { it >= ResumeRepository.MIN_POSITION_MS }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
    }

    override suspend fun saveSettings(settings: PlayerSettings) {
        graph.playerPrefs.save(settings)
    }

    override fun saveProgress(target: PlayerTarget, positionMs: Long, durationMs: Long) {
        if (target.live) return
        val content = target.contentRef as? ContentRef.HubContent ?: return
        graph.applicationScope.launch {
            val client = graph.hubs.clientFor(source.hubId) ?: return@launch
            runCatching {
                client.call { credentials ->
                    if (durationMs <= 0 ||
                        positionMs < ResumeRepository.MIN_POSITION_MS ||
                        positionMs > durationMs - ResumeRepository.END_GUARD_MS
                    ) {
                        deleteResume(credentials, content.contentId)
                    } else {
                        saveResume(
                            credentials,
                            ResumePointDto(content.contentId, positionMs, durationMs),
                        )
                    }
                }
            }
        }
    }

    override fun clearProgress(target: PlayerTarget) {
        if (target.live) return
        val content = target.contentRef as? ContentRef.HubContent ?: return
        graph.applicationScope.launch {
            val client = graph.hubs.clientFor(source.hubId) ?: return@launch
            runCatching { client.call { deleteResume(it, content.contentId) } }
        }
    }
}

internal class PlayerViewModel(
    private val source: PlayerDataSource,
    val target: PlayerTarget,
    hubPlaybackFactory: ((CoroutineScope) -> HubPlayerPlayback)? = null,
    private val capabilityReporter: () -> ReportedCapabilities = MediaCapabilityReporter::report,
) : ViewModel() {
    private val hubPlayback = if (target is PlayerTarget.LocalUrl) {
        null
    } else {
        hubPlaybackFactory?.invoke(viewModelScope)
    }
    private var pendingHubPositionMs: Long? = null
    private var latestHubPositionMs = 0L
    private var retainedResumePositionMs: Long? = null
    private var attachedPlayer: WatchTogetherPlayer? = null
    private val watchTogether = hubPlayback?.let { playback ->
        WatchTogetherCoordinator(
            hub = playback,
            scope = viewModelScope,
            reloadAudio = { audioIndex, positionMs ->
                pendingHubPositionMs = positionMs.coerceAtLeast(0)
                playback.requestRemux(audioIndex)
            },
            reloadAfterLeave = { positionMs ->
                pendingHubPositionMs = positionMs.coerceAtLeast(0)
                val selected = (playback.state.value as? HubPlaybackState.Playing)
                    ?.selectedAudioTrackIndex
                    ?: 0
                playback.requestRemux(selected)
            },
            onRoomMembershipChanged = { inRoom ->
                target.live && playback.setLiveRoom(inRoom)
            },
            sharesRoomRead = {
                !target.live || playback.sharesLiveRoomRead()
            },
            onStartMedia = playback::startMedia,
            onProviderCapacity = playback::providerAtCapacity,
        )
    }
    private val noWatchTogether = MutableStateFlow(WatchTogetherState())
    val watchTogetherState: StateFlow<WatchTogetherState> =
        watchTogether?.state ?: noWatchTogether.asStateFlow()
    private val settingsMutex = Mutex()
    private val closed = AtomicBoolean(false)

    /**
     * Whether the host is in the background, and whether the lease died while it was.
     *
     * The lease is kept alive by a heartbeat this process sends, so a trip to another
     * app long enough for the server to reclaim it ends playback through no decision of
     * the viewer's. Remembering that it happened while we were away is what separates it
     * from a lease an administrator ended, which the viewer should see and act on.
     */
    private var hostAway = false
    private var endedWhileAway = false

    /** Elapsed time at the last return from an actual absence; never set on first open. */
    private var returnedAtMs = Long.MIN_VALUE / 2

    /** Monotonic, so a device clock correction cannot make an absence look recent. */
    private fun monotonicMs(): Long = System.nanoTime() / 1_000_000
    private val _bootstrap = MutableStateFlow<PlayerBootstrap?>(null)
    val bootstrap: StateFlow<PlayerBootstrap?> = _bootstrap.asStateFlow()

    private val _settings = MutableStateFlow<PlayerSettings?>(null)
    val settings: StateFlow<PlayerSettings?> = _settings.asStateFlow()

    private val _channel = MutableStateFlow<Channel?>(null)
    val channel: StateFlow<Channel?> = _channel.asStateFlow()

    private val _guideAvailable = MutableStateFlow(target is PlayerTarget.HubContent && target.live)
    val guideAvailable: StateFlow<Boolean> = _guideAvailable.asStateFlow()

    private val _nowNext = MutableStateFlow<NowNextProgramme?>(null)
    val nowNext: StateFlow<NowNextProgramme?> = _nowNext.asStateFlow()

    private val _guideEntries = MutableStateFlow<List<GuideEntry>?>(null)
    val guideEntries: StateFlow<List<GuideEntry>?> = _guideEntries.asStateFlow()

    private val _playbackSource = MutableStateFlow(
        (target as? PlayerTarget.LocalUrl)?.let { PlayerMediaSource(it.url) }
    )
    val playbackSource: StateFlow<PlayerMediaSource?> = _playbackSource.asStateFlow()

    private val _hubDirect = MutableStateFlow(false)
    val hubDirect: StateFlow<Boolean> = _hubDirect.asStateFlow()
    private val _hubAudioTracks = MutableStateFlow<HubAudioTracks?>(null)
    val hubAudioTracks: StateFlow<HubAudioTracks?> = _hubAudioTracks.asStateFlow()
    private val _problem = MutableStateFlow<PlayerProblem?>(null)
    val problem: StateFlow<PlayerProblem?> = _problem.asStateFlow()

    init {
        viewModelScope.launch {
            source.settings.collect { value ->
                _settings.value = value
                if (_bootstrap.value == null) {
                    _bootstrap.value = PlayerBootstrap(
                        settings = value,
                        resumePositionMs = if (shouldTrackProgress(target)) {
                            source.resumePositionFor(target) ?: 0L
                        } else {
                            0L
                        },
                    )
                }
            }
        }
        viewModelScope.launch {
            _channel.value = source.channelFor(target)
            _guideAvailable.value = when (target) {
                is PlayerTarget.LocalUrl ->
                    _channel.value?.let { it.tvgId != null || it.xtreamStreamId != null } == true
                is PlayerTarget.HubContent -> target.live
                is PlayerTarget.HubCatchUp -> false
            }
        }
        viewModelScope.launch {
            _nowNext.value = source.upcoming(target)
        }
        if (hubPlayback != null) {
            viewModelScope.launch {
                hubPlayback.state.collect { state ->
                    when (state) {
                        HubPlaybackState.Preparing -> Unit
                        HubPlaybackState.LeaseCreated -> Unit
                        is HubPlaybackState.Playing -> {
                            _hubDirect.value = state.direct
                            _hubAudioTracks.value = if (state.direct) {
                                null
                            } else {
                                HubAudioTracks(
                                    state.audioTracks,
                                    state.selectedAudioTrackIndex ?: 0,
                                )
                            }
                            _problem.value = null
                            val current = _playbackSource.value
                            if (current?.url != state.target) {
                                _playbackSource.value = PlayerMediaSource(
                                    url = state.target,
                                    startPositionMs = if (current == null) {
                                        null
                                    } else if (target.live) {
                                        C.TIME_UNSET
                                    } else {
                                        attachedPlayer?.positionMs?.coerceAtLeast(0)
                                            ?: pendingHubPositionMs
                                            ?: latestHubPositionMs
                                    },
                                )
                            }
                            pendingHubPositionMs = null
                        }
                        HubPlaybackState.Revoked -> {
                            pendingHubPositionMs = null
                            val away = endedByBeingAway(
                                hostAway,
                                monotonicMs() - returnedAtMs,
                            )
                            _problem.value = PlayerProblem.PLAYBACK_ENDED
                            // Already back: take it now. Still away: onHostStarted will.
                            if (away && !hostAway) {
                                endedWhileAway = false
                                retryHubPlayback()
                            } else {
                                endedWhileAway = away
                            }
                        }
                        HubPlaybackState.SignedOut -> {
                            pendingHubPositionMs = null
                            _problem.value = PlayerProblem.SIGNED_OUT
                        }
                        HubPlaybackState.DuplicatePlayback -> {
                            pendingHubPositionMs = null
                            _playbackSource.value = null
                            _problem.value = null
                            watchTogether?.duplicatePlaybackRefused()
                        }
                        is HubPlaybackState.AtCapacity -> {
                            pendingHubPositionMs = null
                            _problem.value = PlayerProblem.AT_CAPACITY
                        }
                        is HubPlaybackState.Failed -> {
                            pendingHubPositionMs = null
                            _problem.value = PlayerProblem.FAILED
                        }
                    }
                }
            }
            viewModelScope.launch {
                if (closed.get()) return@launch
                try {
                    val leaseCreated = startHubLease(hubPlayback)
                    if (leaseCreated && !closed.get()) watchTogether?.checkIntent()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    _problem.value = PlayerProblem.FAILED
                }
            }
        }
    }

    fun loadGuide() {
        if (_guideEntries.value != null) return
        viewModelScope.launch {
            _guideEntries.value = source.guideFor(target)
        }
    }

    suspend fun catchupTargetFor(entry: GuideEntry): PlayerTarget? =
        source.catchupTargetFor(target, entry)

    fun saveResizeMode(resizeMode: Int) {
        updateSettings { copy(resizeMode = resizeMode) }
    }

    fun saveSubtitleStyle(style: SubtitleStyle) {
        updateSettings { copy(subtitleStyle = style) }
    }

    fun saveProgress(positionMs: Long, durationMs: Long) {
        if (!shouldTrackProgress(target)) return
        retainedResumePositionMs = positionMs.takeIf {
            shouldApplyResume(it, durationMs, ResumeRepository.END_GUARD_MS)
        } ?: 0L
        source.saveProgress(target, positionMs, durationMs)
    }

    fun clearProgress() {
        if (!shouldTrackProgress(target)) return
        retainedResumePositionMs = 0L
        source.clearProgress(target)
    }

    /** Survives an Activity recreation with the retained ViewModel and lease. */
    fun resumePositionForSession(fallbackMs: Long): Long =
        retainedResumePositionMs ?: fallbackMs

    fun updatePlaybackSnapshot(state: PlaybackUiState?) {
        val playback = hubPlayback ?: return
        latestHubPositionMs = state?.positionMs ?: 0
        if (pendingHubPositionMs != null && state != null) {
            pendingHubPositionMs = state.positionMs.coerceAtLeast(0)
        }
        playback.updateSnapshot(
            HubPlaybackSnapshot(
                title = target.title,
                kind = if (target.live) "live" else "movie",
                positionMs = state?.positionMs ?: 0,
                durationMs = state?.durationMs ?: 0,
                paused = state?.playing == false,
                live = state?.isLive ?: target.live,
                engine = "native",
                direct = _hubDirect.value,
                preparing = state == null || state.buffering,
            ),
        )
    }

    fun onMediaRequestFailed(statusCode: Int): Boolean {
        val playback = hubPlayback ?: return false
        viewModelScope.launch { playback.onMediaRequestFailed(statusCode) }
        // Reporting a 404 keeps the lease heartbeat alive, but repeatedly
        // preparing the same missing media item would be an infinite retry loop.
        return false
    }

    /** The host went to the background; a lease lost from here on is lost to that. */
    fun onHostStopped() {
        hostAway = true
    }

    /**
     * Back in the foreground. If the stream was reclaimed while we were away, take it
     * back rather than leaving the viewer at a message saying playback ended, which
     * reads as though the film finished when they only left the app for a moment.
     */
    fun onHostStarted() {
        if (!hostAway) return
        hostAway = false
        returnedAtMs = monotonicMs()
        val reclaim = shouldReclaimStreamOnReturn(_problem.value, endedWhileAway)
        endedWhileAway = false
        if (reclaim) retryHubPlayback()
    }

    fun retryHubPlayback() {
        val playback = hubPlayback ?: return
        if (closed.get()) return
        _problem.value = null
        _playbackSource.value = null
        viewModelScope.launch {
            try {
                val leaseCreated = if (playback.state.value == HubPlaybackState.Preparing) {
                    // Capability discovery can fail before HubPlayerPlayback has a request
                    // to retry. Re-run that initial step instead of clearing into a spinner.
                    startHubLease(playback)
                } else {
                    playback.retry()
                }
                if (leaseCreated) watchTogether?.checkIntent(force = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                _problem.value = PlayerProblem.FAILED
            }
        }
    }

    fun selectHubAudioTrack(index: Int, positionMs: Long): Boolean {
        val playback = hubPlayback ?: return false
        if (_hubDirect.value || _hubAudioTracks.value == null) return false
        val room = watchTogether?.state?.value
        if (room?.inRoom == true) {
            if (!room.canControl) return false
            viewModelScope.launch { watchTogether.selectRoomAudio(index) }
            return true
        }
        pendingHubPositionMs = positionMs.coerceAtLeast(0)
        viewModelScope.launch {
            if (playback.requestRemux(index) == null &&
                playback.state.value !is HubPlaybackState.Playing
            ) {
                pendingHubPositionMs = null
            }
        }
        return true
    }

    fun attachWatchTogetherPlayer(player: WatchTogetherPlayer?) {
        attachedPlayer = player
        watchTogether?.attachPlayer(player)
    }

    fun watchAlone() {
        watchTogether?.watchAlone()
    }

    fun askToJoin(peerId: String) {
        viewModelScope.launch { watchTogether?.askToJoin(peerId) }
    }

    fun answerJoin(requestId: String, accept: Boolean) {
        viewModelScope.launch { watchTogether?.answerJoin(requestId, accept) }
    }

    fun requestRoomControl() {
        viewModelScope.launch { watchTogether?.requestControl() }
    }

    fun answerRoomControl(peerId: String, grant: Boolean) {
        viewModelScope.launch { watchTogether?.answerControl(peerId, grant) }
    }

    fun setRoomControl(targetId: String, grant: Boolean) {
        viewModelScope.launch { watchTogether?.setControl(targetId, grant) }
    }

    fun kickRoomMember(targetId: String) {
        viewModelScope.launch { watchTogether?.kick(targetId) }
    }

    fun leaveRoom() {
        viewModelScope.launch { watchTogether?.leave() }
    }

    fun dismissWatchTogetherNotice(id: Long) {
        watchTogether?.dismissNotice(id)
    }

    fun currentGrant(): String? = hubPlayback?.currentGrant()

    fun closePlayer() {
        val playback = hubPlayback ?: return
        if (!closed.compareAndSet(false, true)) return
        playback.stop()
    }

    /** Ends the old lease before an in-player navigation creates its replacement. */
    suspend fun closePlayerAndAwait() {
        val playback = hubPlayback ?: return
        if (!closed.compareAndSet(false, true)) return
        playback.stopAndAwait()
    }

    override fun onCleared() {
        closePlayer()
    }

    private suspend fun startHubLease(playback: HubPlayerPlayback): Boolean {
        val reported = capabilityReporter()
        if (closed.get()) return false
        val capabilities = ClientCapabilitiesDto(
            reported.videoCodecs,
            reported.audioCodecs,
            reported.selectsTracksInBand,
        )
        return when (target) {
            is PlayerTarget.HubContent -> playback.start(target.contentId, capabilities)
            is PlayerTarget.HubCatchUp -> playback.startCatchUp(
                target.contentId,
                target.startMs,
                target.durationMs,
                capabilities,
            )
            is PlayerTarget.LocalUrl -> false
        }
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
internal fun playerViewModel(target: PlayerTarget): PlayerViewModel = viewModel(
    key = "PlayerViewModel-${target.encode()}",
    factory = viewModelFactory {
        initializer {
            val graph = OpenTvApp.graph
            val source = when (target) {
                is PlayerTarget.LocalUrl -> LocalPlayerDataSource(graph)
                is PlayerTarget.HubContent -> HubPlayerDataSource(
                    graph,
                    SourceId.Hub(target.hubId, target.playlistId),
                )
                is PlayerTarget.HubCatchUp -> HubPlayerDataSource(
                    graph,
                    SourceId.Hub(target.hubId, target.playlistId),
                )
            }
            val playbackFactory: ((CoroutineScope) -> HubPlayerPlayback)? = when (target) {
                is PlayerTarget.LocalUrl -> null
                is PlayerTarget.HubContent -> { scope ->
                    DefaultHubPlayerPlayback(graph, target.hubId, scope)
                }
                is PlayerTarget.HubCatchUp -> { scope ->
                    DefaultHubPlayerPlayback(graph, target.hubId, scope)
                }
            }
            PlayerViewModel(source, target, playbackFactory)
        }
    },
)
