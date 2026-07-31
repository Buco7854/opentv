package com.buco7854.opentv.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.buco7854.opentv.R
import com.buco7854.opentv.core.xtream.AccountInfo
import com.buco7854.opentv.ui.components.EmptyState
import com.buco7854.opentv.ui.components.OtvProgressBar
import com.buco7854.opentv.ui.components.Pill
import androidx.compose.ui.text.style.TextOverflow
import com.buco7854.opentv.source.SourceId
import com.buco7854.opentv.ui.components.sourceViewModel
import java.text.DateFormat
import java.util.Date

private const val DAY_MS = 86_400_000L

internal fun accountDaysLeft(expiryMs: Long, nowMs: Long): Int =
    Math.floorDiv(expiryMs - nowMs, DAY_MS).toInt()

/**
 * Account / connection-monitoring page for one playlist, local or server-hosted.
 *
 * A server-hosted playlist's provider figures come from the server rather than from the
 * provider directly, but they answer the same question, so this page is shared.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(source: SourceId, onBack: () -> Unit) {
    val viewModel = sourceViewModel(source, ::AccountViewModel)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val info = state.info
    val updatedAtMs = state.updatedAtMs
    val busy = state.refreshing
    val error = state.error

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.account_title))
                        state.title?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        state.serverName?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 16.dp).size(22.dp),
                            strokeWidth = 2.5.dp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    } else {
                        IconButton(onClick = { viewModel.refresh() }, enabled = state.hasProviderAccount) {
                            Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.account_refresh_info))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        contentWindowInsets = WindowInsets(0.dp),
    ) { padding ->
        when {
            state.loading -> Box(
                Modifier.padding(padding).fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.onSurface)
            }

            !state.hasProviderAccount -> Column(Modifier.padding(padding)) {
                EmptyState(
                    stringResource(R.string.account_no_api_title),
                    stringResource(R.string.account_no_api_subtitle),
                )
            }

            else -> Column(
                Modifier
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp),
            ) {
                val account = info
                if (account == null) {
                    Spacer(Modifier.height(40.dp))
                    Text(
                        error ?: stringResource(R.string.account_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (error != null) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    ConnectionsCard(account)
                    Spacer(Modifier.height(14.dp))
                    DetailsCard(account)
                    Spacer(Modifier.height(14.dp))
                    error?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(8.dp))
                    }
                    updatedAtMs?.let {
                        Text(
                            stringResource(R.string.account_updated, DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(it))),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.account_cache_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectionsCard(account: AccountInfo) {
    val atLimit = account.maxConnections in 1..account.activeConnections
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "${account.activeConnections} / ${account.maxConnections}",
                style = MaterialTheme.typography.displaySmall,
                color = if (atLimit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                stringResource(R.string.account_connections_label),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            OtvProgressBar(
                progress = {
                    if (account.maxConnections > 0) {
                        (account.activeConnections.toFloat() / account.maxConnections).coerceIn(0f, 1f)
                    } else 0f
                },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = if (atLimit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun DetailsCard(account: AccountInfo) {
    val dateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM)
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp)) {
            DetailRow(stringResource(R.string.account_status)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        account.status,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (account.status.equals("Active", ignoreCase = true)) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.error,
                    )
                    if (account.isTrial) Pill(stringResource(R.string.account_trial))
                }
            }
            account.username?.let {
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
                DetailRow(stringResource(R.string.account_username)) { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
            account.expiresAtMs?.let { expiry ->
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
                val daysLeft = accountDaysLeft(expiry, System.currentTimeMillis())
                DetailRow(stringResource(R.string.account_expires)) {
                    Text(
                        if (daysLeft >= 0) {
                            pluralStringResource(
                                R.plurals.account_expires_in,
                                daysLeft,
                                dateFormat.format(Date(expiry)),
                                daysLeft,
                            )
                        } else {
                            stringResource(R.string.account_expired, dateFormat.format(Date(expiry)))
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (daysLeft < 7) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            account.createdAtMs?.let {
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
                DetailRow(stringResource(R.string.account_member_since)) {
                    Text(dateFormat.format(Date(it)), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        value()
    }
}
