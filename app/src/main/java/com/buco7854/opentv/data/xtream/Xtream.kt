package com.buco7854.opentv.data.xtream

import com.buco7854.opentv.data.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

class XtreamCredentials(val base: String, val user: String, val pass: String)

class AccountInfo(
    val activeConnections: Int,
    val maxConnections: Int,
    val status: String,
    val expiresAtMs: Long?,
)

/**
 * Most providers serve M3U playlists from an Xtream-codes panel
 * (http://host:port/get.php?username=U&password=P&type=m3u_plus). When we spot
 * that shape we can also query player_api.php for account metadata - including
 * active vs. maximum concurrent connections.
 */
object Xtream {

    fun detect(playlistUrl: String): XtreamCredentials? {
        val url = playlistUrl.toHttpUrlOrNull() ?: return null
        if (!url.encodedPath.endsWith("get.php")) return null
        val user = url.queryParameter("username") ?: return null
        val pass = url.queryParameter("password") ?: return null
        val base = "${url.scheme}://${url.host}:${url.port}"
        return XtreamCredentials(base, user, pass)
    }

    suspend fun fetchAccountInfo(creds: XtreamCredentials): AccountInfo = withContext(Dispatchers.IO) {
        val apiUrl = creds.base.toHttpUrlOrNull()!!.newBuilder()
            .addPathSegment("player_api.php")
            .addQueryParameter("username", creds.user)
            .addQueryParameter("password", creds.pass)
            .build()
        val request = Request.Builder()
            .url(apiUrl)
            .header("User-Agent", Http.USER_AGENT)
            .build()
        Http.ok.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Provider API returned HTTP ${response.code}")
            val text = response.body?.string().orEmpty()
            // Panels answer with HTML error pages when the account is blocked;
            // never let raw page content escape into an exception message.
            val json = try {
                JSONObject(text)
            } catch (_: JSONException) {
                throw IOException("Provider API returned an unexpected (non-JSON) response")
            }
            val info = json.optJSONObject("user_info")
                ?: throw IOException("Provider API response is missing user_info")
            AccountInfo(
                activeConnections = info.optString("active_cons", "0").toIntOrNull() ?: 0,
                maxConnections = info.optString("max_connections", "0").toIntOrNull() ?: 0,
                status = info.optString("status", "Unknown"),
                expiresAtMs = info.optString("exp_date").toLongOrNull()?.times(1000),
            )
        }
    }
}
