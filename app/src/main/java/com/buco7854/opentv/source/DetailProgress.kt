package com.buco7854.opentv.source

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A persisted playback-position change made by this application process. */
data class CatalogProgressUpdate(
    val source: SourceId,
    val ref: ContentRef,
    val progress: Float?,
)

/**
 * Process-local invalidation seam between the player and retained catalog ViewModels.
 *
 * The notification is emitted only after the owning store accepted the write: Room for a
 * local source, or the hub API for a server source. It never substitutes local storage for
 * hub progress. Detail pages still refresh from their source when they become visible again;
 * this notification closes the race where that refresh can beat the player's asynchronous
 * final write while navigation is returning to the retained page.
 */
class CatalogProgressUpdates {
    private val mutableUpdates = MutableSharedFlow<CatalogProgressUpdate>(
        extraBufferCapacity = 32,
    )
    val updates: SharedFlow<CatalogProgressUpdate> = mutableUpdates.asSharedFlow()

    fun publish(source: SourceId, ref: ContentRef, progress: Float?) {
        mutableUpdates.tryEmit(CatalogProgressUpdate(source, ref, progress))
    }
}

/**
 * Progress newer than the catalog item embedded in a retained detail ViewModel.
 *
 * [refreshed] makes an absent row meaningful: after a successful source refresh, absence
 * means progress was cleared rather than "keep showing the old embedded value". Process-local
 * [overrides] win over a refresh that may have started just before the player's final save.
 */
internal data class DetailProgressState(
    val refreshed: Boolean = false,
    val points: Map<String, Float> = emptyMap(),
    val overrides: Map<String, DetailProgressOverride> = emptyMap(),
) {
    fun progressFor(item: CatalogItem): Float? {
        val key = item.ref.progressIdentity()
        overrides[key]?.let { return it.progress }
        return if (refreshed) points[key] else item.progress
    }
}

internal data class DetailProgressOverride(
    val progress: Float?,
    val revision: Long,
)

/**
 * Owns source-authoritative detail progress without polling.
 *
 * Detail payloads never wait for the source's resume collection. Every resume, including the
 * first, refreshes progress independently so title, artwork, metadata, and actions can render
 * while that source-owned state catches up.
 */
internal class DetailProgressTracker(
    private val source: SourceId,
    private val gateway: CatalogGateway,
    private val scope: CoroutineScope,
    updates: CatalogProgressUpdates,
) {
    private val mutableState = MutableStateFlow(DetailProgressState())
    val state: StateFlow<DetailProgressState> = mutableState.asStateFlow()

    private var refreshJob: Job? = null
    private var updateRevision = 0L

    init {
        scope.launch {
            updates.updates
                .filter { it.source == source }
                .collect { update ->
                    val key = update.ref.progressIdentity()
                    val revision = ++updateRevision
                    mutableState.update { current ->
                        current.copy(
                            overrides = current.overrides + (
                                key to DetailProgressOverride(update.progress, revision)
                            ),
                        )
                    }
                }
        }
    }

    fun onResumed() {
        refresh()
    }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            val revisionAtStart = updateRevision
            val result = try {
                gateway.resumePoints()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                return@launch
            }
            val rows = (result as? CatalogResult.Success)?.value ?: return@launch
            mutableState.update { current ->
                current.copy(
                    refreshed = true,
                    points = rows.mapNotNull { point ->
                        point.progress?.let { point.ref.progressIdentity() to it }
                    }.toMap(),
                    // A completed write published while this request was in flight is
                    // newer than its response. Older overrides have now been confirmed by
                    // the source and can be dropped, allowing a later refresh (including a
                    // change from another device) to become visible.
                    overrides = current.overrides.filterValues {
                        it.revision > revisionAtStart
                    },
                )
            }
        }
    }
}

/** Channel ids are refresh-generation values; a local resume point is owned by its URL. */
private fun ContentRef.progressIdentity(): String = when (this) {
    is ContentRef.LocalUrl -> "local:$url"
    is ContentRef.HubContent -> "hub:$contentId"
}
