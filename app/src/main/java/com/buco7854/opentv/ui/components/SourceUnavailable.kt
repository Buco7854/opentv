package com.buco7854.opentv.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.buco7854.opentv.R

/**
 * Why a source has nothing to show, and what the viewer can do about it.
 *
 * A server-backed source is not cached, so an unreachable server means an empty
 * screen. Showing a spinner forever reads as a broken app; this states the
 * situation and always offers the one action that helps.
 */
@Composable
fun SourceUnavailable(
    title: String,
    subtitle: String?,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val actionFocusRequester = remember { FocusRequester() }
    RequestInitialFocusOnTv(actionFocusRequester, title)
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        subtitle?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        OtvButton(
            onClick = onAction,
            modifier = Modifier
                .padding(top = 4.dp)
                .focusRequester(actionFocusRequester),
        ) { Text(actionLabel) }
    }
}

/** The server could not be reached; retrying is the only useful action. */
@Composable
fun SourceUnreachable(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    SourceUnavailable(
        title = stringResource(R.string.source_unreachable_title),
        subtitle = stringResource(R.string.hub_unreachable),
        actionLabel = stringResource(R.string.common_retry),
        onAction = onRetry,
        modifier = modifier,
    )
}

/** The session ended: retrying would only fail again, so offer sign-in. */
@Composable
fun SourceSignedOut(onSignIn: () -> Unit, modifier: Modifier = Modifier) {
    SourceUnavailable(
        title = stringResource(R.string.source_signed_out_title),
        subtitle = stringResource(R.string.source_signed_out_subtitle),
        actionLabel = stringResource(R.string.hub_sign_in_again),
        onAction = onSignIn,
        modifier = modifier,
    )
}

/** Anything else that failed to load. */
@Composable
fun SourceLoadFailed(message: String?, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    SourceUnavailable(
        title = stringResource(R.string.source_load_failed),
        subtitle = message,
        actionLabel = stringResource(R.string.common_retry),
        onAction = onRetry,
        modifier = modifier,
    )
}
