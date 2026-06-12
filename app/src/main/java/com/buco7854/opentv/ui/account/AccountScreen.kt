package com.buco7854.opentv.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.buco7854.opentv.OpenTvApp
import com.buco7854.opentv.data.db.PlaylistEntity
import com.buco7854.opentv.data.xtream.AccountInfo
import com.buco7854.opentv.ui.components.EmptyState
import com.buco7854.opentv.ui.components.Pill
import com.buco7854.opentv.ui.theme.Mint
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Dedicated account / connection-monitoring page for one playlist. This is the
 * only place that talks to the provider's account API on a user action; the
 * repository still caches results for 60 s, and the refresh button forces a
 * fresh request.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(playlistId: Long, onBack: () -> Unit) {
    val graph = OpenTvApp.graph
    var playlist by remember { mutableStateOf<PlaylistEntity?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<AccountInfo?>(null) }
    var updatedAtMs by remember { mutableStateOf<Long?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun load(force: Boolean) {
        val target = playlist ?: return
        scope.launch {
            busy = true
            error = null
            val result = graph.account.accountInfo(target, force)
            if (result != null) {
                info = result
                updatedAtMs = System.currentTimeMillis()
            } else {
                error = "Couldn't reach the provider's account API. Details are in the error log."
            }
            busy = false
        }
    }

    LaunchedEffect(playlistId) {
        playlist = graph.db.playlistDao().get(playlistId)
        loaded = true
        if (playlist?.xtreamBase != null) load(force = false)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Account")
                        playlist?.let {
                            Text(
                                it.name,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 16.dp).size(22.dp),
                            strokeWidth = 2.5.dp,
                        )
                    } else {
                        IconButton(onClick = { load(force = true) }, enabled = playlist?.xtreamBase != null) {
                            Icon(Icons.Rounded.Refresh, contentDescription = "Refresh account info")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        when {
            !loaded -> {}

            playlist?.xtreamBase == null -> Column(Modifier.padding(padding)) {
                EmptyState(
                    "No account API",
                    "Connection monitoring needs an Xtream-style playlist URL " +
                        "(get.php?username=…&password=…). This playlist doesn't have one.",
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
                        error ?: "Loading account information…",
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
                            "Updated ${DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(it))}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Results are cached for a minute; the refresh button forces a fresh request to the provider.",
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
                color = if (atLimit) MaterialTheme.colorScheme.error else Mint,
            )
            Text(
                "active / maximum connections",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            LinearProgressIndicator(
                progress = {
                    if (account.maxConnections > 0) {
                        (account.activeConnections.toFloat() / account.maxConnections).coerceIn(0f, 1f)
                    } else 0f
                },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = if (atLimit) MaterialTheme.colorScheme.error else Mint,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
            if (atLimit) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "At the limit — another stream now may get the account flagged.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
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
            DetailRow("Status") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        account.status,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (account.status.equals("Active", ignoreCase = true)) Mint
                        else MaterialTheme.colorScheme.error,
                    )
                    if (account.isTrial) Pill("Trial")
                }
            }
            account.username?.let {
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
                DetailRow("Username") { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
            account.expiresAtMs?.let { expiry ->
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
                val daysLeft = ((expiry - System.currentTimeMillis()) / 86_400_000L).toInt()
                DetailRow("Expires") {
                    Text(
                        dateFormat.format(Date(expiry)) +
                            if (daysLeft >= 0) " · in $daysLeft days" else " · expired",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (daysLeft < 7) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            account.createdAtMs?.let {
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
                DetailRow("Member since") {
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
