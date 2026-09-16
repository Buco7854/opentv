package com.buco7854.opentv.hub

import android.content.SharedPreferences

data class PendingDeviceLink(
    val hubId: Long?,
    val baseUrl: String,
    val pollToken: String,
    val verificationUri: String,
    val intervalMs: Long,
    val expiresAtMs: Long,
    val mode: DeviceLinkMode,
)

/**
 * Persists the single in-flight device-link/browser-sign-in attempt so a process that gets
 * reclaimed while a Custom Tab is in front (common right after the app was backgrounded a
 * long time) can pick the same poll token back up on restart, instead of the sign-in screen
 * assuming it is starting fresh and immediately relaunching the browser.
 */
class PendingDeviceLinkStore(private val prefs: SharedPreferences) {
    fun save(link: PendingDeviceLink) {
        prefs.edit()
            .putString(KEY_HUB_ID, link.hubId?.toString())
            .putString(KEY_BASE_URL, link.baseUrl)
            .putString(KEY_POLL_TOKEN, link.pollToken)
            .putString(KEY_VERIFICATION_URI, link.verificationUri)
            .putLong(KEY_INTERVAL_MS, link.intervalMs)
            .putLong(KEY_EXPIRES_AT_MS, link.expiresAtMs)
            .putString(KEY_MODE, link.mode.name)
            .apply()
    }

    fun load(): PendingDeviceLink? {
        val pollToken = prefs.getString(KEY_POLL_TOKEN, null) ?: return null
        val baseUrl = prefs.getString(KEY_BASE_URL, null) ?: return null
        val verificationUri = prefs.getString(KEY_VERIFICATION_URI, null) ?: return null
        val mode = prefs.getString(KEY_MODE, null)?.let {
            runCatching { DeviceLinkMode.valueOf(it) }.getOrNull()
        } ?: return null
        val expiresAtMs = prefs.getLong(KEY_EXPIRES_AT_MS, 0L)
        if (expiresAtMs <= 0L) return null
        return PendingDeviceLink(
            hubId = prefs.getString(KEY_HUB_ID, null)?.toLongOrNull(),
            baseUrl = baseUrl,
            pollToken = pollToken,
            verificationUri = verificationUri,
            intervalMs = prefs.getLong(KEY_INTERVAL_MS, 2_000L),
            expiresAtMs = expiresAtMs,
            mode = mode,
        )
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        const val PREFS_NAME = "hub_pending_device_link"
        private const val KEY_HUB_ID = "hub_id"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_POLL_TOKEN = "poll_token"
        private const val KEY_VERIFICATION_URI = "verification_uri"
        private const val KEY_INTERVAL_MS = "interval_ms"
        private const val KEY_EXPIRES_AT_MS = "expires_at_ms"
        private const val KEY_MODE = "mode"
    }
}
