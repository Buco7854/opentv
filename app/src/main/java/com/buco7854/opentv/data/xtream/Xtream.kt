package com.buco7854.opentv.data.xtream

import com.buco7854.opentv.data.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

class XtreamCredentials(val base: String, val user: String, val pass: String)

class AccountInfo(
    val activeConnections: Int,
    val maxConnections: Int,
    val status: String,
    val expiresAtMs: Long?,
    val username: String? = null,
    val isTrial: Boolean = false,
    val createdAtMs: Long? = null,
)

class XtreamLiveStream(
    val streamId: Long,
    val name: String,
    val icon: String?,
    val epgChannelId: String?,
    val categoryId: String?,
    /** Days of catch-up archive (tv_archive); 0 = none. */
    val archiveDays: Int,
)

class XtreamVodStream(
    val streamId: Long,
    val name: String,
    val icon: String?,
    val categoryId: String?,
    val containerExtension: String,
)

class XtreamSeriesItem(
    val seriesId: Long,
    val name: String,
    val cover: String?,
    val plot: String?,
    val cast: String?,
    val genre: String?,
    val rating: Double?,
    val categoryId: String?,
)

class XtreamEpgEntry(
    val title: String,
    val description: String?,
    val startMs: Long,
    val endMs: Long,
    val hasArchive: Boolean,
)

class XtreamEpisode(
    val episodeId: String,
    val title: String,
    val season: Int,
    val episodeNum: Int,
    val containerExtension: String,
    val image: String? = null,
    val plot: String? = null,
    val durationSecs: Int? = null,
    val airDate: String? = null,
)

class XtreamVodInfo(
    val plot: String?,
    val cast: String?,
    val director: String?,
    val genre: String?,
    val rating: Double?,
    val image: String?,
)

/**
 * Client for the Xtream-codes panel API. Unlike a flat M3U, the panel exposes
 * Live / Movies / Series as cleanly separated, categorized databases - so
 * playlists added via Xtream login never need classification guesswork.
 *
 * Request budget: a refresh costs exactly six requests (3 category lists +
 * 3 stream lists); series episodes and VOD details are fetched lazily, one
 * request per item the user actually opens, and cached.
 */
object Xtream {

    /** "host:port" or a full URL -> normalized "scheme://host:port", or null if unusable. */
    fun normalizeServer(input: String): String? {
        var server = input.trim().trimEnd('/')
        if (server.isEmpty()) return null
        if (!server.startsWith("http://") && !server.startsWith("https://")) {
            server = "http://$server"
        }
        val url = server.toHttpUrlOrNull() ?: return null
        return "${url.scheme}://${url.host}:${url.port}"
    }

    fun detect(playlistUrl: String): XtreamCredentials? {
        val url = playlistUrl.toHttpUrlOrNull() ?: return null
        if (!url.encodedPath.endsWith("get.php")) return null
        val user = url.queryParameter("username") ?: return null
        val pass = url.queryParameter("password") ?: return null
        val base = "${url.scheme}://${url.host}:${url.port}"
        return XtreamCredentials(base, user, pass)
    }

    fun liveUrl(c: XtreamCredentials, streamId: Long) =
        "${c.base}/live/${c.user}/${c.pass}/$streamId.ts"

    fun vodUrl(c: XtreamCredentials, streamId: Long, extension: String) =
        "${c.base}/movie/${c.user}/${c.pass}/$streamId.${extension.ifBlank { "mp4" }}"

    fun episodeUrl(c: XtreamCredentials, episodeId: String, extension: String) =
        "${c.base}/series/${c.user}/${c.pass}/$episodeId.${extension.ifBlank { "mp4" }}"

    fun xmltvUrl(c: XtreamCredentials) =
        "${c.base}/xmltv.php?username=${c.user}&password=${c.pass}"

    /** Catch-up (timeshift) stream for a past programme. */
    fun catchupUrl(c: XtreamCredentials, streamId: Long, startMs: Long, durationMinutes: Int): String =
        com.buco7854.opentv.data.catchup.Catchup.xtreamTimeshift(
            c.base, c.user, c.pass, streamId, startMs, durationMinutes,
        )

    private fun api(creds: XtreamCredentials, action: String?, vararg params: Pair<String, String>): String {
        val builder = creds.base.toHttpUrlOrNull()!!.newBuilder()
            .addPathSegment("player_api.php")
            .addQueryParameter("username", creds.user)
            .addQueryParameter("password", creds.pass)
        action?.let { builder.addQueryParameter("action", it) }
        params.forEach { builder.addQueryParameter(it.first, it.second) }
        val request = Request.Builder()
            .url(builder.build())
            .header("User-Agent", Http.USER_AGENT)
            .build()
        Http.ok.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Provider API returned HTTP ${response.code}")
            return response.body?.string().orEmpty()
        }
    }

    private fun jsonArray(text: String): JSONArray = try {
        JSONArray(text)
    } catch (_: JSONException) {
        throw IOException("Provider API returned an unexpected (non-JSON) response")
    }

    private fun ratingOf(obj: JSONObject, vararg keys: String): Double? {
        for (key in keys) {
            obj.optString(key).toDoubleOrNull()?.takeIf { it > 0 }?.let { return it }
        }
        return null
    }

    private fun decodeMaybeBase64(value: String): String {
        if (value.isBlank()) return value
        return try {
            String(java.util.Base64.getDecoder().decode(value), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            value // not base64 (some panels send plain text)
        }
    }

    /**
     * Per-channel EPG (get_simple_data_table). Unlike the bulk xmltv.php this
     * includes past programmes and an explicit `has_archive` flag per entry,
     * which is what catch-up needs to know exactly what can be replayed.
     */
    suspend fun fetchChannelEpg(creds: XtreamCredentials, streamId: Long): List<XtreamEpgEntry> =
        withContext(Dispatchers.IO) {
            val text = api(creds, "get_simple_data_table", "stream_id" to streamId.toString())
            val listings = try {
                JSONObject(text).optJSONArray("epg_listings")
            } catch (_: JSONException) {
                null
            } ?: return@withContext emptyList()
            buildList {
                for (i in 0 until listings.length()) {
                    val o = listings.optJSONObject(i) ?: continue
                    val startSec = o.optString("start_timestamp").toLongOrNull()
                    val endSec = o.optString("stop_timestamp").toLongOrNull()
                    if (startSec == null || endSec == null) continue
                    add(
                        XtreamEpgEntry(
                            title = decodeMaybeBase64(o.optString("title")).ifBlank { "Programme" },
                            description = decodeMaybeBase64(o.optString("description")).takeIf { it.isNotBlank() },
                            startMs = startSec * 1000,
                            endMs = endSec * 1000,
                            hasArchive = o.optInt("has_archive", 0) == 1,
                        )
                    )
                }
            }
        }

    suspend fun fetchAccountInfo(creds: XtreamCredentials): AccountInfo = withContext(Dispatchers.IO) {
        val text = api(creds, action = null)
        // Panels answer with HTML error pages when the account is blocked;
        // never let raw page content escape into an exception message.
        val json = try {
            JSONObject(text)
        } catch (_: JSONException) {
            throw IOException("Provider API returned an unexpected (non-JSON) response")
        }
        val info = json.optJSONObject("user_info")
            ?: throw IOException("Provider API response is missing user_info")
        if (info.has("auth") && info.optInt("auth", 1) == 0) {
            throw IOException("Login rejected by provider - check server, username and password")
        }
        AccountInfo(
            activeConnections = info.optString("active_cons", "0").toIntOrNull() ?: 0,
            maxConnections = info.optString("max_connections", "0").toIntOrNull() ?: 0,
            status = info.optString("status", "Unknown"),
            expiresAtMs = info.optString("exp_date").toLongOrNull()?.times(1000),
            username = info.optString("username").takeIf { it.isNotBlank() },
            isTrial = info.optString("is_trial") == "1",
            createdAtMs = info.optString("created_at").toLongOrNull()?.times(1000),
        )
    }

    /** category_id -> category_name for one of get_live/vod/series_categories. */
    suspend fun fetchCategories(creds: XtreamCredentials, action: String): Map<String, String> =
        withContext(Dispatchers.IO) {
            val array = jsonArray(api(creds, action))
            buildMap {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val id = obj.optString("category_id")
                    val name = obj.optString("category_name")
                    if (id.isNotBlank() && name.isNotBlank()) put(id, name)
                }
            }
        }

    suspend fun fetchLiveStreams(creds: XtreamCredentials): List<XtreamLiveStream> =
        withContext(Dispatchers.IO) {
            val array = jsonArray(api(creds, "get_live_streams"))
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val id = obj.optLong("stream_id", -1)
                    if (id <= 0) continue
                    add(
                        XtreamLiveStream(
                            streamId = id,
                            name = obj.optString("name").ifBlank { "Channel $id" },
                            icon = obj.optString("stream_icon").takeIf { it.isNotBlank() },
                            epgChannelId = obj.optString("epg_channel_id").takeIf { it.isNotBlank() },
                            categoryId = obj.optString("category_id").takeIf { it.isNotBlank() },
                            archiveDays = if (obj.optInt("tv_archive", 0) == 1) {
                                obj.optString("tv_archive_duration").toIntOrNull()?.coerceAtLeast(1) ?: 1
                            } else 0,
                        )
                    )
                }
            }
        }

    suspend fun fetchVodStreams(creds: XtreamCredentials): List<XtreamVodStream> =
        withContext(Dispatchers.IO) {
            val array = jsonArray(api(creds, "get_vod_streams"))
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val id = obj.optLong("stream_id", -1)
                    if (id <= 0) continue
                    add(
                        XtreamVodStream(
                            streamId = id,
                            name = obj.optString("name").ifBlank { "Movie $id" },
                            icon = obj.optString("stream_icon").takeIf { it.isNotBlank() },
                            categoryId = obj.optString("category_id").takeIf { it.isNotBlank() },
                            containerExtension = obj.optString("container_extension").ifBlank { "mp4" },
                        )
                    )
                }
            }
        }

    suspend fun fetchSeriesList(creds: XtreamCredentials): List<XtreamSeriesItem> =
        withContext(Dispatchers.IO) {
            val array = jsonArray(api(creds, "get_series"))
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val id = obj.optLong("series_id", -1)
                    if (id <= 0) continue
                    add(
                        XtreamSeriesItem(
                            seriesId = id,
                            name = obj.optString("name").ifBlank { "Series $id" },
                            cover = obj.optString("cover").takeIf { it.isNotBlank() },
                            plot = obj.optString("plot").takeIf { it.isNotBlank() },
                            cast = obj.optString("cast").takeIf { it.isNotBlank() },
                            genre = obj.optString("genre").takeIf { it.isNotBlank() },
                            // "rating" is 10-based; some panels only fill "rating_5based".
                            rating = obj.optString("rating").toDoubleOrNull()?.takeIf { it > 0 }
                                ?: obj.optString("rating_5based").toDoubleOrNull()
                                    ?.takeIf { it > 0 }?.times(2),
                            categoryId = obj.optString("category_id").takeIf { it.isNotBlank() },
                        )
                    )
                }
            }
        }

    suspend fun fetchSeriesEpisodes(creds: XtreamCredentials, seriesId: Long): List<XtreamEpisode> =
        withContext(Dispatchers.IO) {
            val text = api(creds, "get_series_info", "series_id" to seriesId.toString())
            val json = try {
                JSONObject(text)
            } catch (_: JSONException) {
                throw IOException("Provider API returned an unexpected (non-JSON) response")
            }
            val episodes = mutableListOf<XtreamEpisode>()

            fun parseEpisodeArray(array: JSONArray, seasonHint: Int?) {
                for (i in 0 until array.length()) {
                    val ep = array.optJSONObject(i) ?: continue
                    val id = ep.optString("id")
                    if (id.isBlank()) continue
                    val info = ep.optJSONObject("info")
                    // duration comes as seconds ("duration_secs") or "HH:MM:SS"
                    val durationSecs = info?.optInt("duration_secs", 0)?.takeIf { it > 0 }
                        ?: info?.optString("duration")?.split(':')
                            ?.mapNotNull { it.toIntOrNull() }
                            ?.takeIf { it.size == 3 }
                            ?.let { (h, m, s) -> h * 3600 + m * 60 + s }
                            ?.takeIf { it > 0 }
                    episodes.add(
                        XtreamEpisode(
                            episodeId = id,
                            title = ep.optString("title").ifBlank { "Episode ${ep.optInt("episode_num", i + 1)}" },
                            season = ep.optInt("season", seasonHint ?: 1),
                            episodeNum = ep.optInt("episode_num", i + 1),
                            containerExtension = ep.optString("container_extension").ifBlank { "mp4" },
                            image = info?.optString("movie_image")?.takeIf { it.isNotBlank() },
                            plot = info?.optString("plot")?.takeIf { it.isNotBlank() },
                            durationSecs = durationSecs,
                            airDate = info?.optString("releasedate")?.takeIf { it.isNotBlank() }
                                ?: info?.optString("air_date")?.takeIf { it.isNotBlank() },
                        )
                    )
                }
            }

            // "episodes" is usually an object keyed by season number, but some
            // panels serve it as an array of arrays.
            json.optJSONObject("episodes")?.let { bySeason ->
                for (key in bySeason.keys()) {
                    bySeason.optJSONArray(key)?.let { parseEpisodeArray(it, key.toIntOrNull()) }
                }
            } ?: json.optJSONArray("episodes")?.let { seasons ->
                for (i in 0 until seasons.length()) {
                    seasons.optJSONArray(i)?.let { parseEpisodeArray(it, null) }
                }
            }
            episodes
        }

    suspend fun fetchVodInfo(creds: XtreamCredentials, vodId: Long): XtreamVodInfo? =
        withContext(Dispatchers.IO) {
            val text = api(creds, "get_vod_info", "vod_id" to vodId.toString())
            val json = try {
                JSONObject(text)
            } catch (_: JSONException) {
                return@withContext null
            }
            val info = json.optJSONObject("info") ?: return@withContext null
            XtreamVodInfo(
                plot = info.optString("plot").takeIf { it.isNotBlank() }
                    ?: info.optString("description").takeIf { it.isNotBlank() },
                cast = info.optString("cast").takeIf { it.isNotBlank() }
                    ?: info.optString("actors").takeIf { it.isNotBlank() },
                director = info.optString("director").takeIf { it.isNotBlank() },
                genre = info.optString("genre").takeIf { it.isNotBlank() },
                rating = ratingOf(info, "rating"),
                image = info.optString("movie_image").takeIf { it.isNotBlank() },
            )
        }
}
