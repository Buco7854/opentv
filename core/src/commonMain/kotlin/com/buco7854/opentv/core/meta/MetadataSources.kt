package com.buco7854.opentv.core.meta

import com.buco7854.opentv.core.net.HttpFetcher
import com.buco7854.opentv.core.net.Urls
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class MetaInfo(
    val title: String?,
    val year: String?,
    val overview: String?,
    val rating: Double?,
    /** Pre-labelled credits line, e.g. "Cast: A, B, C" or "Director: X". */
    val credits: String?,
    val posterUrl: String?,
    /** Structured cast with photos when the source provides them. */
    val castList: List<CastMember> = emptyList(),
    /** Extra facts: genres, runtime, status, network/rated - " · " separated. */
    val infoLine: String? = null,
    /** Source-side id (TVMaze show id) enabling per-episode lookups. */
    val sourceId: Long? = null,
)

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray
private fun JsonObject.int(key: String): Int? = text(key)?.toDoubleOrNull()?.toInt()
private fun JsonObject.double(key: String): Double? = text(key)?.toDoubleOrNull()

private val HTML_TAGS = Regex("""<[^>]*>""")
private fun String.stripHtml(): String =
    replace(HTML_TAGS, " ").replace(Regex("""\s+"""), " ").trim()

private fun parseObject(text: String): JsonObject? =
    runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()

private fun parseArray(text: String): JsonArray? =
    runCatching { Json.parseToJsonElement(text) as? JsonArray }.getOrNull()

private fun formatMinutes(minutes: Int): String =
    if (minutes >= 60) "${minutes / 60}h ${(minutes % 60).toString().padStart(2, '0')}min" else "$minutes min"

/** Series metadata from TVMaze (keyless): one search + one cast request per title. */
class TvMazeApi(private val http: HttpFetcher) {

    suspend fun fetch(title: String): MetaInfo? {
        val searchUrl = "https://api.tvmaze.com/search/shows?q=${Urls.percentEncode(title)}"
        val show = parseArray(http.getText(searchUrl))
            ?.firstOrNull()?.let { it as? JsonObject }?.obj("show") ?: return null

        val id = show.text("id")?.toLongOrNull() ?: -1
        val cast: List<CastMember> = if (id > 0) {
            runCatching {
                val people = parseArray(http.getText("https://api.tvmaze.com/shows/$id/cast"))
                buildList {
                    for (element in people.orEmpty().take(10)) {
                        val person = (element as? JsonObject)?.obj("person") ?: continue
                        val name = person.text("name")
                        if (name.isNullOrBlank()) continue
                        add(
                            CastMember(
                                name = name,
                                photo = person.obj("image")?.text("medium")?.takeIf { it.isNotBlank() },
                            )
                        )
                    }
                }.distinctBy { it.name }
            }.getOrDefault(emptyList())
        } else emptyList()

        val genres = show.array("genres")
            ?.mapNotNull { (it as? JsonPrimitive)?.content?.takeIf { g -> g.isNotBlank() } }
            .orEmpty()
        val infoLine = listOfNotNull(
            genres.take(3).joinToString(" · ").takeIf { it.isNotEmpty() },
            (show.int("averageRuntime")?.takeIf { it > 0 }
                ?: show.int("runtime")?.takeIf { it > 0 })?.let { "$it min" },
            show.text("status")?.takeIf { it.isNotBlank() && it != "Running" },
            show.obj("network")?.text("name")?.takeIf { it.isNotBlank() },
        ).joinToString(" · ").takeIf { it.isNotEmpty() }

        return MetaInfo(
            title = show.text("name")?.takeIf { it.isNotBlank() },
            year = show.text("premiered")?.take(4)?.takeIf { it.length == 4 },
            overview = show.text("summary")?.takeIf { it.isNotBlank() }?.stripHtml(),
            rating = show.obj("rating")?.double("average")?.takeIf { it > 0 },
            credits = cast.takeIf { it.isNotEmpty() }
                ?.joinToString(", ") { it.name }?.let { "Cast: $it" },
            posterUrl = show.obj("image")?.let { image ->
                image.text("original")?.takeIf { it.isNotBlank() }
                    ?: image.text("medium")?.takeIf { it.isNotBlank() }
            },
            castList = cast,
            infoLine = infoLine,
            sourceId = id.takeIf { it > 0 },
        )
    }

    /** Details for one episode (still image, synopsis, air date, runtime, rating). */
    suspend fun episode(showId: Long, season: Int, number: Int): MetaInfo? {
        val url = "https://api.tvmaze.com/shows/$showId/episodebynumber?season=$season&number=$number"
        val ep = try {
            parseObject(http.getText(url))
        } catch (_: Exception) {
            return null // unknown episode numbering, not an error
        } ?: return null
        return MetaInfo(
            title = ep.text("name")?.takeIf { it.isNotBlank() },
            year = ep.text("airdate")?.takeIf { it.isNotBlank() },
            overview = ep.text("summary")?.takeIf { it.isNotBlank() }?.stripHtml(),
            rating = ep.obj("rating")?.double("average")?.takeIf { it > 0 },
            credits = null,
            posterUrl = ep.obj("image")?.let { image ->
                image.text("original")?.takeIf { it.isNotBlank() }
                    ?: image.text("medium")?.takeIf { it.isNotBlank() }
            },
            infoLine = ep.int("runtime")?.takeIf { it > 0 }?.let { "$it min" },
        )
    }
}

/** Movie metadata from the iTunes Search API (keyless): synopsis, poster, genre, director. No cast. */
class ITunesApi(private val http: HttpFetcher) {

    suspend fun fetch(title: String, year: String?): MetaInfo? {
        val searchUrl =
            "https://itunes.apple.com/search?term=${Urls.percentEncode(title)}&media=movie&limit=5"
        val results = parseObject(http.getText(searchUrl))?.array("results") ?: return null
        if (results.isEmpty()) return null

        // Prefer the result matching the year hint.
        var movie: JsonObject? = null
        if (year != null) {
            for (element in results) {
                val candidate = element as? JsonObject ?: continue
                if (candidate.text("releaseDate")?.take(4) == year) {
                    movie = candidate
                    break
                }
            }
        }
        movie = movie ?: results.firstOrNull() as? JsonObject ?: return null

        val overview = movie.text("longDescription")?.takeIf { it.isNotBlank() }
            ?: movie.text("shortDescription")?.takeIf { it.isNotBlank() }
        val genre = movie.text("primaryGenreName")?.takeIf { it.isNotBlank() }
        val director = movie.text("artistName")?.takeIf { it.isNotBlank() }
        val credits = listOfNotNull(
            director?.let { "Director: $it" },
            genre?.let { "Genre: $it" },
        ).joinToString(" · ").takeIf { it.isNotEmpty() }
        val durationMs = movie.text("trackTimeMillis")?.toLongOrNull() ?: 0
        val infoLine = listOfNotNull(
            durationMs.takeIf { it > 0 }?.let { formatMinutes((it / 60_000).toInt()) },
            movie.text("contentAdvisoryRating")?.takeIf { it.isNotBlank() && it != "Unrated" },
        ).joinToString(" · ").takeIf { it.isNotEmpty() }

        return MetaInfo(
            title = movie.text("trackName")?.takeIf { it.isNotBlank() },
            year = movie.text("releaseDate")?.take(4)?.takeIf { it.length == 4 },
            overview = overview,
            rating = null,
            credits = credits,
            posterUrl = movie.text("artworkUrl100")?.takeIf { it.isNotBlank() }
                ?.replace("100x100", "600x600"),
            infoLine = infoLine,
        )
    }
}
