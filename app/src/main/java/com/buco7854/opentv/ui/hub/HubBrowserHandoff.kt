package com.buco7854.opentv.ui.hub

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.buco7854.opentv.hub.HubEndpoints

/**
 * What happened when the app tried to hand a hub page to a browser.
 *
 * Account and administration screens are deliberately not reimplemented
 * natively, so every such entry point ends here.
 */
sealed interface HandoffResult {
    /** A browser took the page; nothing more to show. */
    data object Opened : HandoffResult

    /** No usable browser (the normal case on a TV): show [url] as a QR instead. */
    data class ShowQrInstead(val url: String) : HandoffResult

    /** The hub offered a page outside its own origin; refused. */
    data object Rejected : HandoffResult
}

/**
 * Opens hub pages in a real browser, falling back to a QR code on devices
 * without one.
 *
 * A hub-supplied URL (the device-link verification page) is checked against the
 * hub's own origin first: the user chose to trust one server, and a reply that
 * points somewhere else must not be turned into a browser visit or a QR code
 * that a phone would happily scan.
 */
class HubBrowserHandoff(private val context: Context) {

    fun open(hubBaseUrl: String, target: String): HandoffResult {
        if (!HubEndpoints.isSameOrigin(hubBaseUrl, target)) return HandoffResult.Rejected
        if (isTelevision()) return HandoffResult.ShowQrInstead(target)
        return try {
            CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
                .launchUrl(context, Uri.parse(target))
            HandoffResult.Opened
        } catch (_: android.content.ActivityNotFoundException) {
            // A phone can lack a browser too; the QR is still scannable by another device.
            HandoffResult.ShowQrInstead(target)
        }
    }

    /** TV browsers are rare and unusable with a remote, so treat TV as "no browser". */
    private fun isTelevision(): Boolean {
        val uiMode = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        if (uiMode?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true
        return context.packageManager.hasSystemFeature("android.software.leanback")
    }

    /** True when a browser exists at all; lets callers word a button honestly. */
    fun hasBrowser(): Boolean {
        if (isTelevision()) return false
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
        return probe.resolveActivity(context.packageManager) != null
    }
}
