package com.buco7854.opentv.ui.hub

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.CircularProgressIndicator
import com.buco7854.opentv.source.SourceId
import com.buco7854.opentv.ui.home.CatalogSourceEntry
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.buco7854.opentv.OpenTvApp
import com.buco7854.opentv.R
import com.buco7854.opentv.ui.components.ScreenHeader
import com.buco7854.opentv.core.model.HubSource
import com.buco7854.opentv.ui.components.OtvButton
import com.buco7854.opentv.ui.components.OtvTextButton
import com.buco7854.opentv.ui.components.Pill
import com.buco7854.opentv.ui.components.RequestInitialFocusOnTv
import com.buco7854.opentv.ui.components.focusHighlight
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Settings for one connected server.
 *
 * Account and administration are deliberately not rebuilt natively: those are
 * rare, deliberate, keyboard-heavy tasks whose every future feature would
 * otherwise have to be mirrored here. They open the server's own pages in a
 * browser instead, and the administration entry only appears when the cached
 * role says this user has it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HubSettingsScreen(
    hubId: Long,
    onBack: () -> Unit,
    onRemoved: () -> Unit,
    onSignIn: () -> Unit,
    onOpenPlaylistAccount: (SourceId) -> Unit,
) {
    val graph = OpenTvApp.graph
    val accounts = graph.hubAccounts
    val context = LocalContext.current
    val handoff = remember(context) { HubBrowserHandoff(context) }
    val backFocusRequester = remember { FocusRequester() }
    val removeCancelFocusRequester = remember { FocusRequester() }
    RequestInitialFocusOnTv(backFocusRequester)

    val viewModel: HubSettingsViewModel = viewModel(
        key = "HubSettings-$hubId",
        factory = viewModelFactory {
            initializer {
                HubSettingsViewModel(
                    hubId = hubId,
                    observeSources = accounts.sources,
                    readSource = { graph.storage.hubSources.get(hubId) },
                    refreshIdentity = { accounts.refresh(hubId) },
                    loadPlaylists = {
                        val client = graph.hubs.clientFor(hubId) ?: error("Server session unavailable")
                        client.call { playlists(it) }.map {
                            CatalogSourceEntry(SourceId.Hub(hubId, it.id), it.name, itemCount = it.channelCount)
                        }
                    },
                )
            }
        },
    )
    val workspace by viewModel.state.collectAsStateWithLifecycle()
    val source = workspace.source
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose {}
    }

    val snackbar = androidx.compose.runtime.remember { androidx.compose.material3.SnackbarHostState() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var qrUrl by remember { mutableStateOf<String?>(null) }
    var rejected by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    var unreleasedAssociations by remember { mutableStateOf<Int?>(null) }
    var removeAfterDownload by remember(hubId) {
        mutableStateOf(graph.hubDownloadPreferences.removeFromServerAfterDownload(hubId))
    }

    fun openPage(url: String) {
        val hub = source ?: return
        when (val result = handoff.open(hub.baseUrl, url)) {
            is HandoffResult.ShowQrInstead -> qrUrl = result.url
            HandoffResult.Rejected -> rejected = true
            HandoffResult.Opened -> Unit
        }
    }

    Scaffold(
        topBar = {
            ScreenHeader(
                source?.name ?: stringResource(R.string.hub_settings_title),
                onBack,
                backModifier = Modifier.focusRequester(backFocusRequester),
            )
        },
    ) { padding ->
        val hub = source
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier.widthIn(max = 960.dp).fillMaxSize()
                    .verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                if (!workspace.sourceLoaded) {
                    CircularProgressIndicator()
                } else if (hub == null) {
                    Text(stringResource(R.string.management_server_removed), style = MaterialTheme.typography.bodyLarge)
                    OtvTextButton(onClick = onBack) { Text(stringResource(R.string.common_back)) }
                } else {
                    ServerWorkspaceHeader(
                        hub = hub,
                        onSignIn = onSignIn,
                    )
                    if (rejected) Text(
                        stringResource(R.string.hub_handoff_rejected),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (hub.userId != null) {
                        ServerPlaylistSection(
                            state = workspace,
                            onRetry = viewModel::refresh,
                            onManage = if (accounts.canAdminister(hub)) {
                                { openPage(com.buco7854.opentv.hub.HubEndpoints.webPath(hub.baseUrl, "/?manage=playlist")) }
                            } else null,
                        ) { entry ->
                            ServerPlaylistActionsRow(
                                source = entry,
                                hubBaseUrl = hub.baseUrl,
                                selected = false,
                                onOpenAccount = { onOpenPlaylistAccount(entry.sourceId) },
                                onNotify = { message -> scope.launch { snackbar.showSnackbar(message) } },
                                onChanged = { scope.launch { viewModel.refresh() } },
                            )
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            SectionTitle(stringResource(R.string.management_account))
                            Card(Modifier.fillMaxWidth()) {
                                Column {
                                    BrowserEntry(
                                        icon = Icons.Outlined.Person,
                                        title = stringResource(R.string.hub_open_account),
                                        description = stringResource(R.string.hub_open_account_description),
                                        onClick = { openPage(accounts.webSecurity(hub)) },
                                    )
                                    if (accounts.canAdminister(hub)) {
                                        HorizontalDivider()
                                        BrowserEntry(
                                            icon = Icons.Outlined.AdminPanelSettings,
                                            title = stringResource(R.string.hub_open_admin),
                                            description = stringResource(R.string.hub_open_admin_description),
                                            onClick = { openPage(accounts.webAdmin(hub)) },
                                        )
                                        HorizontalDivider()
                                        BrowserEntry(
                                            icon = Icons.Outlined.Devices,
                                            title = stringResource(R.string.hub_open_sessions),
                                            description = null,
                                            onClick = { openPage(accounts.webSessions(hub)) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SectionTitle(stringResource(R.string.management_device_preferences))
                        Card(Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(stringResource(R.string.hub_remove_download_after_pull), style = MaterialTheme.typography.titleSmall)
                                    Text(stringResource(R.string.hub_remove_download_after_pull_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(checked = removeAfterDownload, onCheckedChange = {
                                    removeAfterDownload = it
                                    graph.hubDownloadPreferences.setRemoveFromServerAfterDownload(hubId, it)
                                }, modifier = Modifier.focusHighlight())
                            }
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionTitle(stringResource(R.string.management_connection))
                        Text(stringResource(R.string.management_connection_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (hub.userId != null) OtvTextButton(onClick = {
                            graph.applicationScope.launch { accounts.signOut(hubId) }
                            onBack()
                        }) { Text(stringResource(R.string.hub_sign_out)) }
                        OtvTextButton(onClick = { confirmRemove = true }, enabled = !removing, danger = true) {
                            Text(stringResource(R.string.hub_remove))
                        }
                    }
                }
            }
            androidx.compose.material3.SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }

    qrUrl?.let { url ->
        UrlQrDialog(
            url = url,
            title = stringResource(R.string.hub_settings_title),
            onDismiss = { qrUrl = null },
        )
    }

    if (confirmRemove) {
        RequestInitialFocusOnTv(removeCancelFocusRequester)
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.hub_remove)) },
            text = { Text(stringResource(R.string.hub_remove_confirm)) },
            confirmButton = {
                OtvTextButton(
                    onClick = {
                        confirmRemove = false
                        removing = true
                        // Removal ends with the same HTTP logout, so it runs where a
                        // navigation cannot cancel it half-done.
                        graph.applicationScope.launch {
                            val result = accounts.remove(hubId)
                            withContext(Dispatchers.Main.immediate) {
                                removing = false
                                if (result.unreleasedDownloadAssociations == 0) {
                                    onRemoved()
                                } else {
                                    unreleasedAssociations =
                                        result.unreleasedDownloadAssociations
                                }
                            }
                        }
                    },
                    danger = true,
                ) { Text(stringResource(R.string.hub_remove)) }
            },
            dismissButton = {
                OtvTextButton(
                    onClick = { confirmRemove = false },
                    modifier = Modifier.focusRequester(removeCancelFocusRequester),
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    unreleasedAssociations?.let { count ->
        AlertDialog(
            onDismissRequest = {
                unreleasedAssociations = null
                onRemoved()
            },
            title = { Text(stringResource(R.string.hub_remove_cleanup_title)) },
            text = {
                Text(
                    pluralStringResource(
                        R.plurals.hub_remove_cleanup_failed,
                        count,
                        count,
                    ),
                )
            },
            confirmButton = {
                OtvTextButton(
                    onClick = {
                        unreleasedAssociations = null
                        onRemoved()
                    },
                ) {
                    Text(stringResource(R.string.common_close))
                }
            },
        )
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
}

/** Identity first; account actions never hide among individual playlists. */
@Composable
internal fun ServerWorkspaceHeader(hub: HubSource, onSignIn: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(hub.name, style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, lineHeight = 24.sp))
            Text(hub.baseUrl, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                if (hub.userId == null) stringResource(R.string.hub_status_signed_out)
                else stringResource(R.string.hub_signed_in_as, hub.username.orEmpty()),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (hub.userId == null) OtvButton(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.hub_sign_in_again))
            }
        }
    }
}

@Composable
internal fun ServerPlaylistSection(
    state: HubWorkspaceState,
    onRetry: () -> Unit,
    onManage: (() -> Unit)?,
    playlist: @Composable (CatalogSourceEntry) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle(stringResource(R.string.management_server_playlists))
        Text(stringResource(R.string.management_server_playlists_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.loading) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.common_loading), style = MaterialTheme.typography.bodyMedium)
        }
        if (state.failed) {
            Text(stringResource(R.string.management_playlists_failed), style = MaterialTheme.typography.bodyMedium)
            OtvTextButton(onClick = onRetry) { Text(stringResource(R.string.hub_retry)) }
        }
        if (state.playlists.isNotEmpty()) Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(8.dp)) { state.playlists.forEach { playlist(it) } }
        } else if (!state.loading && !state.failed) {
            Text(stringResource(R.string.management_no_playlists), style = MaterialTheme.typography.bodyMedium)
        }
        if (onManage != null) {
            OtvButton(onClick = onManage, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.management_server_playlists_browser))
                Spacer(Modifier.width(8.dp))
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = stringResource(R.string.hub_opens_in_browser), modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** A row that hands off to the server's web UI, marked as leaving the app. */
@Composable
private fun BrowserEntry(
    icon: ImageVector,
    title: String,
    description: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusHighlight(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            description?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Icon(
            Icons.AutoMirrored.Outlined.OpenInNew,
            contentDescription = stringResource(R.string.hub_opens_in_browser),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}
