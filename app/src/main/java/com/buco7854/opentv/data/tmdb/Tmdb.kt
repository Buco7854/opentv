package com.buco7854.opentv.data.tmdb

import com.buco7854.opentv.data.net.Http
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/**
 * Provider playlists decorate titles with junk that breaks lookups:
 * "FR - Oppenheimer (2023) [1080p x265]". This strips language prefixes,
 * bracketed chunks, quality/codec tags, and pulls the year out as a separate
 * search hint.
 */
object TitleCleaner {
    private val BRACKETED = Regex("""[\[(][^)\]]*[)\]]""")
    private val QUALITY = Regex(
        """(?i)\b(4K|UHD|2160p|1080p|FHD|720p|480p|HEVC|x26[45]|H\.?26[45]|HDR(?:10)?|WEB-?DL|WEBRip|BluRay|BRRip|HDTV|MULTI|VOSTFR|HD|SD)\b"""
    )
    private val LANG_PREFIX = Regex("""^\s*[A-Z]{2,3}\s*[-|:•]\s*""")
    private val YEAR = Regex("""\b(19|20)\d{2}\b""")
    private val SEPARATOR_RUNS = Regex("""[\s._\-|]{2,}""")

    fun clean(raw: String): Pair<String, String?> {
        val year = YEAR.find(raw)?.value
        // Release-style names use dots instead of spaces ("The.Matrix.1999").
        var title = if (!raw.contains(' ') && raw.count { it == '.' } >= 2) {
            raw.replace('.', ' ')
        } else raw
        title = title
            .replace(BRACKETED, " ")
            .replace(QUALITY, " ")
            .replace(LANG_PREFIX, "")
        year?.let { title = title.replace(it, " ") }
        title = title
            .replace(SEPARATOR_RUNS, " ")
            .trim(' ', '-', '_', '|', ':', '.', ',')
            .replace(Regex("""\s+"""), " ")
        return title to year
    }
}

class TmdbInfo(
    val title: String?,
    val year: String?,
    val overview: String?,
    val rating: Double?,
    val castNames: String?,
    val posterUrl: String?,
)

/**
 * Minimal TMDB v3 client: one search request plus one credits request per
 * (cleaned) title. Callers are expected to cache aggressively - see
 * MetadataRepository.
 */
object Tmdb {
    private const val BASE = "https://api.themoviedb.org/3"
    private const val POSTER_BASE = "https://image.tmdb.org/t/p/w500"

    fun fetch(apiKey: String, isSeries: Boolean, title: String, year: String?): TmdbInfo? {
        val type = if (isSeries) "tv" else "movie"
        val searchUrl = "$BASE/search/$type".toHttpUrl().newBuilder()
            .addQueryParameter("api_key", apiKey)
            .addQueryParameter("query", title)
            .apply {
                if (year != null) {
                    addQueryParameter(if (isSeries) "first_air_date_year" else "year", year)
                }
            }
            .build()
        val result = getJson(searchUrl.toString())
            .optJSONArray("results")?.optJSONObject(0) ?: return null

        val id = result.optLong("id", -1)
        val castNames = if (id > 0) {
            val credits = getJson(
                "$BASE/$type/$id/credits".toHttpUrl().newBuilder()
                    .addQueryParameter("api_key", apiKey).build().toString()
            )
            credits.optJSONArray("cast")?.let { cast ->
                (0 until minOf(cast.length(), 8))
                    .mapNotNull { cast.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() } }
                    .joinToString(", ")
                    .takeIf { it.isNotEmpty() }
            }
        } else null

        return TmdbInfo(
            title = result.optString(if (isSeries) "name" else "title").takeIf { it.isNotBlank() },
            year = result.optString(if (isSeries) "first_air_date" else "release_date")
                .take(4).takeIf { it.length == 4 },
            overview = result.optString("overview").takeIf { it.isNotBlank() },
            rating = result.optDouble("vote_average", 0.0).takeIf { it > 0 },
            castNames = castNames,
            posterUrl = result.optString("poster_path").takeIf { it.isNotBlank() }
                ?.let { POSTER_BASE + it },
        )
    }

    private fun getJson(url: String): JSONObject {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", Http.USER_AGENT)
            .build()
        Http.ok.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("TMDB returned HTTP ${response.code}")
            return JSONObject(response.body?.string().orEmpty())
        }
    }
}
