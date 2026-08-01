package com.buco7854.opentv.hub

import com.buco7854.opentv.contract.CurrentUserDto
import com.buco7854.opentv.core.model.HubSource
import com.buco7854.opentv.download.HubDownloadCoordinator
import com.buco7854.opentv.download.HubDownloadPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

class HubAccountRepository(
    private val registry: HubRegistry,
    private val hubDownloads: HubDownloadCoordinator,
    private val downloadPreferences: HubDownloadPreferences,
) {
    val sources: Flow<List<HubSource>>
        get() = registry.observeAll()

    suspend fun refresh(hubId: Long): CurrentUserDto? =
        registry.refreshIdentity(hubId)

    suspend fun health(hubId: Long): StateFlow<HubHealth>? =
        registry.clientFor(hubId)?.health

    suspend fun signOut(hubId: Long) {
        registry.signOut(hubId)
    }

    suspend fun remove(hubId: Long): HubRemovalResult {
        var unreleased = downloadPreferences.pendingServerDeleteCount(hubId)
        registry.remove(
            hubId = hubId,
            beforeLogout = { hubDownloads.flushPendingServerDeletes(hubId) },
            beforeForget = {
                // Capture this while the row and token still exist; the final prune is
                // intentionally destructive and must not erase what the UI needs to report.
                unreleased = downloadPreferences.pendingServerDeleteCount(hubId)
            },
        )
        downloadPreferences.pruneHub(hubId)
        return HubRemovalResult(unreleased)
    }

    fun webSecurity(source: HubSource): String =
        HubEndpoints.webSecurity(source.baseUrl)

    fun webAdmin(source: HubSource): String =
        HubEndpoints.webAdmin(source.baseUrl)

    fun webSessions(source: HubSource): String =
        HubEndpoints.webSessions(source.baseUrl)

    fun canAdminister(source: HubSource): Boolean =
        source.role == ADMIN_ROLE

    private companion object {
        const val ADMIN_ROLE = "ADMIN"
    }
}

data class HubRemovalResult(val unreleasedDownloadAssociations: Int)
