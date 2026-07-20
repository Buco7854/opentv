package com.buco7854.opentv.core.xtream

import com.buco7854.opentv.core.catchup.Catchup
import com.buco7854.opentv.core.net.HttpFetcher
import com.buco7854.opentv.core.net.Urls
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class XtreamCredentials(val base: String, val user: String, val pass: String)

/** Panel rejected the credentials (auth=0), distinct from a transport error so callers don't fall back to M3U. */
class XtreamAuthException(message: String) : Exception(message)

/** The panel answered, but not with the JSON shape the API promises. */
class XtreamApiException(message: String) : Exception(message)

@Serializable
class AccountInfo(
    val activeConnections: Int,
    val maxConnections: Int,
    val status: String,
    val expiresAtMs: Long?,
    val username: String? = null,
    val isTrial: Boolean = false,
    val createdAtMs: Long? = null,
    /** server_info.timezone - timeshift timestamps are read in it. */
    val timezone: String? = null,
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

// Lenient accessors: panels send numbers as strings and vice versa, so read via text form.
private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content

private fun JsonObject.textOr(key: String, default: String = ""): String = text(key) ?: default
private fun JsonObject.long(key: String, default: Long = 0): Long = text(key)?.toLongOrNull() ?: default
private fun JsonObject.int(key: String, default: Int = 0): Int = text(key)?.toDoubleOrNull()?.toInt() ?: default
private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

/** Pure URL construction and detection for Xtream-codes panels. */
object Xtream {

    /** "host:port" or a full URL -> normalized "scheme://host:port", or null if unusable. */
    fun normalizeServer(input: String): String? {
        var server = input.trim().trimEnd('/')
        if (server.isEmpty()) return null
        if (!server.startsWith("http://") && !server.startsWith("https://")) {
            server = "http://$server"
        }
        val url = Urls.parse(server) ?: return null
        return "${url.scheme}://${url.host}:${url.port}"
    }

    fun detect(playlistUrl: String): XtreamCredentials? {
        val url = Urls.parse(playlistUrl) ?: return null
        if (!url.path.endsWith("get.php")) return null
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

    /** Catch-up (timeshift) stream for a past programme. [tz] must be the panel's timezone. */
    fun catchupUrl(
        c: XtreamCredentials,
        streamId: Long,
        startMs: Long,
        durationMinutes: Int,
        tz: TimeZone = TimeZone.currentSystemDefault(),
    ): String = Catchup.xtreamTimeshift(c.base, c.user, c.pass, streamId, startMs, durationMinutes, tz)

    fun apiUrl(creds: XtreamCredentials, action: String?, vararg params: Pair<String, String>): String =
        buildString {
            append(creds.base)
            append("/player_api.php?username=")
            append(Urls.percentEncode(creds.user))
            append("&password=")
            append(Urls.percentEncode(creds.pass))
            action?.let { append("&action=").append(Urls.percentEncode(it)) }
            params.forEach { (k, v) ->
                append('&').append(k).append('=').append(Urls.percentEncode(v))
            }
        }
}

/**
 * Client for the Xtream-codes panel API, which exposes Live/Movies/Series as
 * separate categorized databases (no classification guesswork).
 *
 * Request budget: a refresh costs six requests (3 category + 3 stream lists);
 * series episodes and VOD details are fetched lazily per opened item and cached.
 */
class XtreamApi(private val http: HttpFetcher) {

    private suspend fun api(creds: XtreamCredentials, action: String?, vararg params: Pair<String, String>): String =
        http.getText(Xtream.apiUrl(creds, action, *params))

    private fun jsonElement(text: String): JsonElement = try {
        Json.parseToJsonElement(text)
    } catch (_: Exception) {
        throw XtreamApiException("Provider API returned an unexpected (non-JSON) response")
    }

    private fun jsonArray(text: String): JsonArray =
        jsonElement(text) as? JsonArray
            ?: throw XtreamApiException("Provider API returned an unexpected (non-JSON) response")

    private fun jsonObject(text: String): JsonObject =
        jsonElement(text) as? JsonObject
            ?: throw XtreamApiException("Provider API returned an unexpected (non-JSON) response")

    private fun ratingOf(obj: JsonObject, vararg keys: String): Double? {
        for (key in keys) {
            obj.text(key)?.toDoubleOrNull()?.takeIf { it > 0 }?.let { return it }
        }
        return null
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeMaybeBase64(value: String): String {
        if (value.isBlank()) return value
        return try {
            Base64.decode(value).decodeToString()
        } catch (_: IllegalArgumentException) {
            value // not base64 (some panels send plain text)
        }
    }

    /**
     * Per-channel EPG (get_simple_data_table). Includes past programmes and a
     * per-entry `has_archive` flag, which is what catch-up needs.
     */
    suspend fun fetchChannelEpg(creds: XtreamCredentials, streamId: Long): List<XtreamEpgEntry> {
        val text = api(creds, "get_simple_data_table", "stream_id" to streamId.toString())
        val listings = try {
            (Json.parseToJsonElement(text) as? JsonObject)?.array("epg_listings")
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        return buildList {
            for (element in listings) {
                val o = element as? JsonObject ?: continue
                val startSec = o.text("start_timestamp")?.toLongOrNull()
                val endSec = o.text("stop_timestamp")?.toLongOrNull()
                if (startSec == null || endSec == null) continue
                add(
                    XtreamEpgEntry(
                        title = decodeMaybeBase64(o.textOr("title")).ifBlank { "Programme" },
                        description = decodeMaybeBase64(o.textOr("description")).takeIf { it.isNotBlank() },
                        startMs = startSec * 1000,
                        endMs = endSec * 1000,
                        hasArchive = o.int("has_archive") == 1,
                    )
                )
            }
        }
    }

    suspend fun fetchAccountInfo(creds: XtreamCredentials): AccountInfo {
        val text = api(creds, action = null)
        // Blocked accounts get HTML error pages; never leak raw page content into exceptions.
        val json = jsonObject(text)
        val info = json.obj("user_info")
            ?: throw XtreamApiException("Provider API response is missing user_info")
        if ("auth" in info && info.int("auth", 1) == 0) {
            throw XtreamAuthException("Login rejected by provider - check server, username and password")
        }
        return AccountInfo(
            activeConnections = info.text("active_cons")?.toIntOrNull() ?: 0,
            maxConnections = info.text("max_connections")?.toIntOrNull() ?: 0,
            status = info.textOr("status", "Unknown"),
            expiresAtMs = info.text("exp_date")?.toLongOrNull()?.times(1000),
            username = info.text("username")?.takeIf { it.isNotBlank() },
            isTrial = info.text("is_trial") == "1",
            createdAtMs = info.text("created_at")?.toLongOrNull()?.times(1000),
            timezone = json.obj("server_info")?.text("timezone")?.takeIf { it.isNotBlank() },
        )
    }

    /** category_id -> category_name for one of get_live/vod/series_categories. */
    suspend fun fetchCategories(creds: XtreamCredentials, action: String): Map<String, String> {
        val array = jsonArray(api(creds, action))
        return buildMap {
            for (element in array) {
                val obj = element as? JsonObject ?: continue
                val id = obj.textOr("category_id")
                val name = obj.textOr("category_name")
                if (id.isNotBlank() && name.isNotBlank()) put(id, name)
            }
        }
    }

    suspend fun fetchLiveStreams(creds: XtreamCredentials): List<XtreamLiveStream> {
        val array = jsonArray(api(creds, "get_live_streams"))
        return buildList {
            for (element in array) {
                val obj = element as? JsonObject ?: continue
                val id = obj.long("stream_id", -1)
                if (id <= 0) continue
                add(
                    XtreamLiveStream(
                        streamId = id,
                        name = obj.textOr("name").ifBlank { "Channel $id" },
                        icon = obj.text("stream_icon")?.takeIf { it.isNotBlank() },
                        epgChannelId = obj.text("epg_channel_id")?.takeIf { it.isNotBlank() },
                        categoryId = obj.text("category_id")?.takeIf { it.isNotBlank() },
                        archiveDays = if (obj.int("tv_archive") == 1) {
                            obj.text("tv_archive_duration")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                        } else 0,
                    )
                )
            }
        }
    }

    suspend fun fetchVodStreams(creds: XtreamCredentials): List<XtreamVodStream> {
        val array = jsonArray(api(creds, "get_vod_streams"))
        return buildList {
            for (element in array) {
                val obj = element as? JsonObject ?: continue
                val id = obj.long("stream_id", -1)
                if (id <= 0) continue
                add(
                    XtreamVodStream(
                        streamId = id,
                        name = obj.textOr("name").ifBlank { "Movie $id" },
                        icon = obj.text("stream_icon")?.takeIf { it.isNotBlank() },
                        categoryId = obj.text("category_id")?.takeIf { it.isNotBlank() },
                        containerExtension = obj.textOr("container_extension").ifBlank { "mp4" },
                    )
                )
            }
        }
    }

    suspend fun fetchSeriesList(creds: XtreamCredentials): List<XtreamSeriesItem> {
        val array = jsonArray(api(creds, "get_series"))
        return buildList {
            for (element in array) {
                val obj = element as? JsonObject ?: continue
                val id = obj.long("series_id", -1)
                if (id <= 0) continue
                add(
                    XtreamSeriesItem(
                        seriesId = id,
                        name = obj.textOr("name").ifBlank { "Series $id" },
                        cover = obj.text("cover")?.takeIf { it.isNotBlank() },
                        plot = obj.text("plot")?.takeIf { it.isNotBlank() },
                        cast = obj.text("cast")?.takeIf { it.isNotBlank() },
                        genre = obj.text("genre")?.takeIf { it.isNotBlank() },
                        // "rating" is 10-based; some panels only fill "rating_5based".
                        rating = obj.text("rating")?.toDoubleOrNull()?.takeIf { it > 0 }
                            ?: obj.text("rating_5based")?.toDoubleOrNull()
                                ?.takeIf { it > 0 }?.times(2),
                        categoryId = obj.text("category_id")?.takeIf { it.isNotBlank() },
                    )
                )
            }
        }
    }

    suspend fun fetchSeriesEpisodes(creds: XtreamCredentials, seriesId: Long): List<XtreamEpisode> {
        val text = api(creds, "get_series_info", "series_id" to seriesId.toString())
        val json = jsonObject(text)
        val episodes = mutableListOf<XtreamEpisode>()

        fun parseEpisodeArray(array: JsonArray, seasonHint: Int?) {
            for ((i, element) in array.withIndex()) {
                val ep = element as? JsonObject ?: continue
                val id = ep.textOr("id")
                if (id.isBlank()) continue
                val info = ep.obj("info")
                // duration comes as seconds ("duration_secs") or "HH:MM:SS"
                val durationSecs = info?.int("duration_secs")?.takeIf { it > 0 }
                    ?: info?.text("duration")?.split(':')
                        ?.mapNotNull { it.toIntOrNull() }
                        ?.takeIf { it.size == 3 }
                        ?.let { (h, m, s) -> h * 3600 + m * 60 + s }
                        ?.takeIf { it > 0 }
                episodes.add(
                    XtreamEpisode(
                        episodeId = id,
                        title = ep.textOr("title").ifBlank { "Episode ${ep.int("episode_num", i + 1)}" },
                        season = ep.int("season", seasonHint ?: 1),
                        episodeNum = ep.int("episode_num", i + 1),
                        containerExtension = ep.textOr("container_extension").ifBlank { "mp4" },
                        image = info?.text("movie_image")?.takeIf { it.isNotBlank() },
                        plot = info?.text("plot")?.takeIf { it.isNotBlank() },
                        durationSecs = durationSecs,
                        airDate = info?.text("releasedate")?.takeIf { it.isNotBlank() }
                            ?: info?.text("air_date")?.takeIf { it.isNotBlank() },
                    )
                )
            }
        }

        // "episodes" is usually an object keyed by season number, but some
        // panels serve it as an array of arrays.
        when (val eps = json["episodes"]) {
            is JsonObject -> for ((key, value) in eps) {
                (value as? JsonArray)?.let { parseEpisodeArray(it, key.toIntOrNull()) }
            }
            is JsonArray -> for (seasons in eps) {
                (seasons as? JsonArray)?.let { parseEpisodeArray(it, null) }
            }
            else -> {}
        }
        return episodes
    }

    suspend fun fetchVodInfo(creds: XtreamCredentials, vodId: Long): XtreamVodInfo? {
        val text = api(creds, "get_vod_info", "vod_id" to vodId.toString())
        val json = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return null
        val info = json.obj("info") ?: return null
        return XtreamVodInfo(
            plot = info.text("plot")?.takeIf { it.isNotBlank() }
                ?: info.text("description")?.takeIf { it.isNotBlank() },
            cast = info.text("cast")?.takeIf { it.isNotBlank() }
                ?: info.text("actors")?.takeIf { it.isNotBlank() },
            director = info.text("director")?.takeIf { it.isNotBlank() },
            genre = info.text("genre")?.takeIf { it.isNotBlank() },
            rating = ratingOf(info, "rating"),
            image = info.text("movie_image")?.takeIf { it.isNotBlank() },
        )
    }
}
