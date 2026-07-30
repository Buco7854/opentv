package com.buco7854.opentv.hub

import com.buco7854.opentv.contract.PlaylistCapabilitiesDto
import com.buco7854.opentv.contract.PlaylistOperationCapabilityDto
import com.buco7854.opentv.contract.PlaylistOperationExecution

data class HubPlaylistCapabilities(
    val operations: Map<String, HubPlaylistOperation>,
) {
    operator fun get(operation: String): HubPlaylistOperation? = operations[operation]
}

sealed interface HubPlaylistOperation {
    data object InApp : HubPlaylistOperation
    data class Browser(val url: String) : HubPlaylistOperation
}

internal fun PlaylistCapabilitiesDto.forHub(baseUrl: String): HubPlaylistCapabilities {
    val resolved = operations.associate { capability ->
        capability.operation to capability.forHub(baseUrl)
    }
    require(resolved.size == operations.size) { "Hub returned duplicate playlist operations" }
    return HubPlaylistCapabilities(resolved)
}

private fun PlaylistOperationCapabilityDto.forHub(baseUrl: String): HubPlaylistOperation =
    when (execution) {
        PlaylistOperationExecution.IN_APP -> {
            require(browserPath == null) { "An in-app operation must not carry a browser path" }
            HubPlaylistOperation.InApp
        }
        PlaylistOperationExecution.BROWSER -> HubPlaylistOperation.Browser(
            HubEndpoints.webPath(baseUrl, requireNotNull(browserPath) {
                "A browser operation must carry a browser path"
            }),
        )
        else -> throw IllegalArgumentException("Unknown playlist operation execution: $execution")
    }
