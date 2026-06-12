package com.buco7854.opentv.data.meta

import com.buco7854.opentv.data.net.Http
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
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

class MetaInfo(
    val title: String?,
    val year: String?,
    val overview: String?,
    val rating: Double?,
    /** Pre-labelled credits line, e.g. "Cast: A, B, C" or "Director: X". */
    val credits: String?,
    val posterUrl: String?,
)

private fun getJson(url: String): String {
    val request = Request.Builder()
        .url(url)
        .header("User-Agent", Http.USER_AGENT)
        .build()
    Http.ok.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IOException("Metadata API returned HTTP ${response.code}")
        return response.body?.string().orEmpty()
    }
}

private val HTML_TAGS = Regex("""<[^>]*>""")
private fun String.stripHtml(): String =
    replace(HTML_TAGS, " ").replace(Regex("""\s+"""), " ").trim()

/**
 * Series metadata from TVMaze (api.tvmaze.com) - free, no API key required.
 * One search request plus one cast request per title; callers cache hard.
 */
object TvMaze {
    fun fetch(title: String): MetaInfo? {
        val searchUrl = "https://api.tvmaze.com/search/shows".toHttpUrl().newBuilder()
            .addQueryParameter("q", title)
            .build()
        val show = JSONArray(getJson(searchUrl.toString()))
            .optJSONObject(0)?.optJSONObject("show") ?: return null

        val id = show.optLong("id", -1)
        val cast = if (id > 0) {
            runCatching {
                val people = JSONArray(getJson("https://api.tvmaze.com/shows/$id/cast"))
                (0 until minOf(people.length(), 8))
                    .mapNotNull { people.optJSONObject(it)?.optJSONObject("person")?.optString("name") }
                    .filter { it.isNotBlank() }
                    .joinToString(", ")
                    .takeIf { it.isNotEmpty() }
            }.getOrNull()
        } else null

        return MetaInfo(
            title = show.optString("name").takeIf { it.isNotBlank() },
            year = show.optString("premiered").take(4).takeIf { it.length == 4 },
            overview = show.optString("summary").takeIf { it.isNotBlank() }?.stripHtml(),
            rating = show.optJSONObject("rating")?.optDouble("average", 0.0)?.takeIf { it > 0 },
            credits = cast?.let { "Cast: $it" },
            posterUrl = show.optJSONObject("image")?.let { image ->
                image.optString("original").takeIf { it.isNotBlank() }
                    ?: image.optString("medium").takeIf { it.isNotBlank() }
            },
        )
    }
}

/**
 * Movie metadata from the iTunes Search API (itunes.apple.com) - free, no API
 * key required. Gives synopsis, poster, genre and director; cast lists are not
 * available without a keyed service, which we deliberately avoid.
 */
object ITunesStore {
    fun fetch(title: String, year: String?): MetaInfo? {
        val searchUrl = "https://itunes.apple.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("term", title)
            .addQueryParameter("media", "movie")
            .addQueryParameter("limit", "5")
            .build()
        val results = JSONObject(getJson(searchUrl.toString())).optJSONArray("results") ?: return null
        if (results.length() == 0) return null

        // Prefer the result matching the year hint from the playlist title.
        var movie: JSONObject? = null
        if (year != null) {
            for (i in 0 until results.length()) {
                val candidate = results.optJSONObject(i) ?: continue
                if (candidate.optString("releaseDate").take(4) == year) {
                    movie = candidate
                    break
                }
            }
        }
        movie = movie ?: results.optJSONObject(0) ?: return null

        val overview = movie.optString("longDescription").takeIf { it.isNotBlank() }
            ?: movie.optString("shortDescription").takeIf { it.isNotBlank() }
        val genre = movie.optString("primaryGenreName").takeIf { it.isNotBlank() }
        val director = movie.optString("artistName").takeIf { it.isNotBlank() }
        val credits = listOfNotNull(
            director?.let { "Director: $it" },
            genre?.let { "Genre: $it" },
        ).joinToString(" · ").takeIf { it.isNotEmpty() }

        return MetaInfo(
            title = movie.optString("trackName").takeIf { it.isNotBlank() },
            year = movie.optString("releaseDate").take(4).takeIf { it.length == 4 },
            overview = overview,
            rating = null,
            credits = credits,
            posterUrl = movie.optString("artworkUrl100").takeIf { it.isNotBlank() }
                ?.replace("100x100", "600x600"),
        )
    }
}
