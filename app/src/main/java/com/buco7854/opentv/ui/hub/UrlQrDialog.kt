package com.buco7854.opentv.ui.hub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
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
import com.buco7854.opentv.ui.components.OtvTextButton
import com.buco7854.opentv.ui.components.QrCode
import com.buco7854.opentv.ui.components.RequestInitialFocusOnTv

/**
 * Shows a URL as a QR code for a device that cannot usefully browse it — a TV
 * with no browser, or one where typing a long address with a remote is
 * unreasonable. The URL is printed underneath so it stays usable when a camera
 * is not to hand.
 */
@Composable
fun UrlQrDialog(
    url: String,
    title: String,
    message: String = stringResource(R.string.hub_open_in_browser_qr),
    onDismiss: () -> Unit,
) {
    val closeFocusRequester = remember { FocusRequester() }
    RequestInitialFocusOnTv(closeFocusRequester, url)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(message, textAlign = TextAlign.Center)
                QrCode(content = url, contentDescription = null)
                Text(
                    url,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        },
        confirmButton = {
            OtvTextButton(
                onClick = onDismiss,
                modifier = Modifier.focusRequester(closeFocusRequester),
            ) { Text(stringResource(android.R.string.ok)) }
        },
    )
}
