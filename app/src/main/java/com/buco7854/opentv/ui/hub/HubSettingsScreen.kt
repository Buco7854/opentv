package com.buco7854.opentv.ui.hub

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.buco7854.opentv.OpenTvApp
import com.buco7854.opentv.R
import com.buco7854.opentv.core.model.HubSource
import com.buco7854.opentv.ui.components.OtvTextButton
import com.buco7854.opentv.ui.components.Pill
import com.buco7854.opentv.ui.components.RequestInitialFocusOnTv
import com.buco7854.opentv.ui.components.focusHighlight
import kotlinx.coroutines.launch

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
fun HubSettingsScreen(hubId: Long, onBack: () -> Unit, onRemoved: () -> Unit) {
    val graph = OpenTvApp.graph
    val accounts = graph.hubAccounts
    val context = LocalContext.current
    val handoff = remember(context) { HubBrowserHandoff(context) }
    val backFocusRequester = remember { FocusRequester() }
    val removeCancelFocusRequester = remember { FocusRequester() }
    RequestInitialFocusOnTv(backFocusRequester)

    val source by produceState<HubSource?>(initialValue = null, hubId) {
        value = graph.storage.hubSources.get(hubId)
        // Refresh identity so the administration entry reflects the server, not
        // a role cached when the account was first added.
        runCatching { accounts.refresh(hubId) }
        value = graph.storage.hubSources.get(hubId)
    }

    var qrUrl by remember { mutableStateOf<String?>(null) }
    var rejected by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
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
            TopAppBar(
                title = { Text(source?.name ?: stringResource(R.string.hub_settings_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .focusRequester(backFocusRequester)
                            .focusHighlight(),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        val hub = source
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (hub == null) return@Column

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(hub.baseUrl, style = MaterialTheme.typography.bodyMedium)
                    hub.username?.let {
                        Text(
                            stringResource(R.string.hub_signed_in_as, it),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (accounts.canAdminister(hub)) Pill(text = "ADMIN")
                }
            }

            if (rejected) {
                Text(
                    stringResource(R.string.hub_handoff_rejected),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f).padding(end = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            stringResource(R.string.hub_remove_download_after_pull),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            stringResource(R.string.hub_remove_download_after_pull_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = removeAfterDownload,
                        onCheckedChange = {
                            removeAfterDownload = it
                            graph.hubDownloadPreferences
                                .setRemoveFromServerAfterDownload(hubId, it)
                        },
                        modifier = Modifier.focusHighlight(),
                    )
                }
            }

            // These open the server's own web UI rather than being rebuilt natively, so
            // they are navigation, not actions. As full-width filled buttons they read as
            // three competing primary calls to action stacked down the page; as rows in a
            // card they read as a settings list, which is what they are.
            Card(modifier = Modifier.fillMaxWidth()) {
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

            HorizontalDivider()
            OtvTextButton(
                // Nothing on this screen reflects an ended session, and the sign-out
                // itself must outlive the composition: leaving would otherwise cancel
                // the logout before the local token is forgotten.
                onClick = {
                    graph.applicationScope.launch { accounts.signOut(hubId) }
                    onBack()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.hub_sign_out)) }
            OtvTextButton(
                onClick = { confirmRemove = true },
                danger = true,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.hub_remove)) }
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
                        // Removal ends with the same HTTP logout, so it runs where a
                        // navigation cannot cancel it half-done.
                        graph.applicationScope.launch { accounts.remove(hubId) }
                        onRemoved()
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
