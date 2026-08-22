package com.buco7854.opentv.core.meta

import com.buco7854.opentv.core.net.HttpFetcher
import com.buco7854.opentv.core.net.Urls
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class MetaInfo(
    val title: String?,
    val year: String?,
    val overview: String?,
    val rating: Double?,
    /** Pre-labelled credits line, e.g. "Cast: A, B, C" or "Director: X". */
    val credits: String?,
    val posterUrl: String?,
    /** Structured cast with photos when the source provides them. */
    val castList: List<CastMember> = emptyList(),
    /** A movie-cast lookup completed, including a legitimate empty result. */
    val castLookupCompleted: Boolean = false,
    /** Extra facts: genres, runtime, status, network/rated - " · " separated. */
    val infoLine: String? = null,
    /** Source-side id (TVMaze show id) enabling per-episode lookups. */
    val sourceId: Long? = null,
)

/**
 * Keyless movie-cast enrichment from Wikidata and Wikimedia Commons.
 *
 * The indexed Action API first finds candidates, then validates an exact French/English title
 * and, when known, release year. More than one matching item is ambiguous and deliberately
 * produces no cast. Portraits are built from P18 file names under a fixed Commons origin;
 * arbitrary URLs in remote JSON never reach the image proxy.
 */
class WikidataMovieCastApi(private val http: HttpFetcher) {

    suspend fun fetch(title: String, year: String?): List<CastMember> {
        val safeTitle = title.trim().take(MAX_TITLE_LENGTH)
        if (safeTitle.isEmpty()) return emptyList()
        val numericYear = year?.toIntOrNull()?.takeIf { it in 1888..2100 }
        val candidateIds = SEARCH_LANGUAGES
            .flatMap { language -> search(safeTitle, language) }
            .distinct()
            .take(MAX_CANDIDATES)
        if (candidateIds.isEmpty()) return emptyList()

        val candidates = entities(
            ids = candidateIds,
            properties = "labels|aliases|claims",
        )
        val wantedTitle = normalizeTitle(safeTitle)
        val matches = candidateIds.mapNotNull { id ->
            val entity = candidates[id] as? JsonObject ?: return@mapNotNull null
            entity.takeIf {
                exactTitles(it).any { candidate -> normalizeTitle(candidate) == wantedTitle } &&
                    it.claims(P161).isNotEmpty() &&
                    (numericYear == null || numericYear in it.releaseYears())
            }
        }
        if (matches.size != 1) return emptyList()

        val actors = castIds(matches.single())
        if (actors.isEmpty()) return emptyList()
        val actorEntities = entities(
            ids = actors.map(Actor::id),
            properties = "labels|claims",
        )
        return actors.mapNotNull { actor ->
            val entity = actorEntities[actor.id] as? JsonObject ?: return@mapNotNull null
            val name = label(entity)?.takeIf { it.isNotBlank() && !WIKIDATA_ID.matches(it) }
                ?: return@mapNotNull null
            CastMember(
                name = name.take(MAX_NAME_LENGTH),
                photo = entity.claims(P18).firstNotNullOfOrNull(::commonsPhoto),
            )
        }
    }

    private data class Actor(
        val id: String,
        val ordinal: Int?,
        val statementIndex: Int,
    )

    private suspend fun search(title: String, language: String): List<String> {
        val root = parseObject(http.getText(apiUrl(
            "action" to "wbsearchentities",
            "search" to title,
            "language" to language,
            "uselang" to language,
            "type" to "item",
            "limit" to SEARCH_LIMIT.toString(),
            "format" to "json",
        ))) ?: error("Invalid Wikidata search response")
        val results = root.array("search") ?: error("Missing Wikidata search results")
        return results.mapNotNull { result ->
            (result as? JsonObject)?.text("id")?.takeIf(WIKIDATA_ID::matches)
        }
    }

    private suspend fun entities(ids: List<String>, properties: String): JsonObject {
        if (ids.isEmpty() || ids.any { !WIKIDATA_ID.matches(it) }) return JsonObject(emptyMap())
        val root = parseObject(http.getText(apiUrl(
            "action" to "wbgetentities",
            "ids" to ids.joinToString("|"),
            "props" to properties,
            "languages" to SEARCH_LANGUAGES.joinToString("|"),
            "languagefallback" to "1",
            "format" to "json",
        ))) ?: error("Invalid Wikidata entity response")
        return root.obj("entities") ?: error("Missing Wikidata entities")
    }

    private fun exactTitles(entity: JsonObject): List<String> = buildList {
        val labels = entity.obj("labels")
        val aliases = entity.obj("aliases")
        SEARCH_LANGUAGES.forEach { language ->
            labels?.obj(language)?.text("value")?.let(::add)
            aliases?.array(language).orEmpty().forEach { alias ->
                (alias as? JsonObject)?.text("value")?.let(::add)
            }
        }
    }

    private fun label(entity: JsonObject): String? {
        val labels = entity.obj("labels") ?: return null
        return SEARCH_LANGUAGES.firstNotNullOfOrNull { labels.obj(it)?.text("value") }
            ?: labels.values.firstNotNullOfOrNull { (it as? JsonObject)?.text("value") }
    }

    private fun JsonObject.claims(property: String): JsonArray =
        obj("claims")?.array(property) ?: JsonArray(emptyList())

    private fun JsonObject.releaseYears(): Set<Int> = claims(P577).mapNotNull { statement ->
        (statement as? JsonObject)
            ?.obj("mainsnak")
            ?.obj("datavalue")
            ?.obj("value")
            ?.text("time")
            ?.let(YEAR_IN_TIME::find)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }.toSet()

    private fun castIds(film: JsonObject): List<Actor> = film.claims(P161)
        .mapIndexedNotNull { index, statementElement ->
            val statement = statementElement as? JsonObject ?: return@mapIndexedNotNull null
            val id = statement.obj("mainsnak")
                ?.obj("datavalue")
                ?.obj("value")
                ?.text("id")
                ?.takeIf(WIKIDATA_ID::matches)
                ?: return@mapIndexedNotNull null
            val ordinal = statement.obj("qualifiers")
                ?.array(P1545)
                ?.firstOrNull()
                ?.let { it as? JsonObject }
                ?.obj("datavalue")
                ?.text("value")
                ?.toIntOrNull()
                ?.takeIf { it > 0 }
            Actor(id, ordinal, index)
        }
        .sortedWith(compareBy<Actor> { it.ordinal ?: Int.MAX_VALUE }.thenBy(Actor::statementIndex))
        .distinctBy(Actor::id)
        .take(MAX_CAST)

    private fun commonsPhoto(statementElement: kotlinx.serialization.json.JsonElement): String? {
        val fileName = (statementElement as? JsonObject)
            ?.obj("mainsnak")
            ?.obj("datavalue")
            ?.text("value")
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= MAX_FILE_NAME_LENGTH }
            ?: return null
        return "$COMMONS_FILE_PATH${Urls.encodePathSegment(fileName)}?width=$PHOTO_WIDTH"
    }

    private fun normalizeTitle(value: String): String =
        value.trim().lowercase().replace(TITLE_SEPARATORS, " ")

    private fun apiUrl(vararg parameters: Pair<String, String>): String =
        "$ENDPOINT?" + parameters.joinToString("&") { (key, value) ->
            "${Urls.percentEncode(key)}=${Urls.percentEncode(value)}"
        }

    private companion object {
        const val ENDPOINT = "https://www.wikidata.org/w/api.php"
        const val COMMONS_FILE_PATH = "https://commons.wikimedia.org/wiki/Special:FilePath/"
        const val P18 = "P18"
        const val P161 = "P161"
        const val P577 = "P577"
        const val P1545 = "P1545"
        const val MAX_TITLE_LENGTH = 200
        const val MAX_NAME_LENGTH = 160
        const val MAX_FILE_NAME_LENGTH = 500
        const val SEARCH_LIMIT = 8
        const val MAX_CANDIDATES = SEARCH_LIMIT * 2
        const val MAX_CAST = 10
        const val PHOTO_WIDTH = 240
        val SEARCH_LANGUAGES = listOf("fr", "en")
        val WIKIDATA_ID = Regex("Q[1-9][0-9]*")
        val YEAR_IN_TIME = Regex("^[+]?(\\d{4})-")
        val TITLE_SEPARATORS = Regex("[\\s._:–—-]+")
    }
}

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray
private fun JsonObject.int(key: String): Int? = text(key)?.toDoubleOrNull()?.toInt()
private fun JsonObject.double(key: String): Double? = text(key)?.toDoubleOrNull()
private val PROVIDER_ID = Regex("""[1-9][0-9]*""")
private fun JsonObject.providerId(key: String): Long? {
    val raw = text(key) ?: return null
    if (!PROVIDER_ID.matches(raw)) return null
    return raw.toLongOrNull()?.takeIf { it.toString() == raw }
}

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

        val id = show.providerId("id")
        val cast: List<CastMember> = if (id != null) {
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
            sourceId = id,
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
