package com.buco7854.opentv.server

import com.buco7854.opentv.core.model.Channel
import com.buco7854.opentv.core.model.ChannelKind
import com.buco7854.opentv.core.model.Favorite
import com.buco7854.opentv.core.model.Metadata
import com.buco7854.opentv.core.model.Playlist
import com.buco7854.opentv.core.model.Programme
import com.buco7854.opentv.core.model.ResumePoint
import com.buco7854.opentv.core.model.SeriesGroup
import com.buco7854.opentv.core.model.XtreamSeries
import com.buco7854.opentv.core.meta.decodeCast
import com.buco7854.opentv.core.meta.encodeCast
import com.buco7854.opentv.core.repo.AccountRepository
import com.buco7854.opentv.core.repo.EpgRepository
import com.buco7854.opentv.core.repo.MetadataRepository
import com.buco7854.opentv.core.repo.PlaylistRepository
import com.buco7854.opentv.core.repo.XtreamRepository
import com.buco7854.opentv.core.repo.xtreamFavoriteKey
import com.buco7854.opentv.core.repo.xtreamSeriesKey
import com.buco7854.opentv.core.storage.Storage
import com.buco7854.opentv.core.util.nowMs
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondFile
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Server composition: shared repositories + storage, plus web-only pieces. */
class ServerGraph(
    val storage: Storage,
    val http: ServerHttp,
    val playlists: PlaylistRepository,
    val epg: EpgRepository,
    val xtream: XtreamRepository,
    val account: AccountRepository,
    val metadata: MetadataRepository,
    val proxy: StreamProxy,
    val settings: ServerSettings,
    val downloads: DownloadManager,
    val remux: RemuxService,
    val transcoder: AudioTranscoder,
    val cipher: StreamCipher,
    val sessions: PlaybackSessionRegistry,
    val trustedProxies: TrustedProxies,
    /** Concurrent reads the provider behind a source URL permits (its max_connections). */
    val connectionLimit: suspend (String) -> Int,
)

@Serializable
data class MessageDto(val message: String)

@Serializable
data class RemuxAvailableDto(val available: Boolean)

@Serializable
data class RemuxStartDto(
    val id: String,
    val playlistUrl: String,
    val duration: Double? = null,
    val audioTracks: List<String> = emptyList(),
    val subtitleTracks: List<String> = emptyList(),
    val nativeVideoCopy: Boolean = false,
)

@Serializable
data class PlaylistUpsertRequest(
    val mode: String, // "xtream" | "url" | "file"
    val name: String = "",
    val server: String = "",
    val username: String = "",
    val password: String = "",
    val url: String = "",
    val epgUrl: String = "",
    /** Raw .m3u text for mode = "file" (uploaded from the browser). */
    val content: String = "",
)

/** Credential-free playlist view; URL/login/EPG omitted so listings carry no secrets. */
@Serializable
data class PlaylistDto(
    val id: Long,
    val name: String,
    /** "xtream" | "url" | "file" - drives which controls the client shows. */
    val mode: String,
    /** An Xtream panel API is available (account page, connection line). */
    val hasXtreamPanel: Boolean,
    val lastRefreshedMs: Long,
    val channelCount: Int,
)

/** Sensitive fields, served only from the credentials endpoint for the edit dialog. */
@Serializable
data class PlaylistCredentialsDto(
    val mode: String,
    val url: String? = null,
    val epgUrl: String? = null,
    val xtreamBase: String? = null,
    val xtreamUser: String? = null,
    val xtreamPass: String? = null,
)

@Serializable
data class PlaylistDetailDto(
    val playlist: PlaylistDto,
    val isXtreamNative: Boolean,
    val liveCount: Int,
    val movieCount: Int,
    val seriesCount: Int,
)

@Serializable
data class SeriesHitDto(
    val seriesKey: String,
    val count: Int,
    val logo: String? = null,
    val groupTitle: String,
    val xtreamSeriesId: Long? = null,
)

@Serializable
data class SearchResultsDto(
    val live: List<Channel> = emptyList(),
    val movies: List<Channel> = emptyList(),
    val series: List<SeriesHitDto> = emptyList(),
)

@Serializable
data class XtreamSeriesDetailDto(
    val series: XtreamSeries,
    val episodes: List<Channel>,
    val error: String? = null,
)

@Serializable
data class FavoritesResolvedDto(
    val live: List<Channel> = emptyList(),
    val movies: List<Channel> = emptyList(),
    val series: List<SeriesHitDto> = emptyList(),
)

@Serializable
data class CatchupUrlDto(val url: String?)

@Serializable
data class GroupKindRequest(val groupTitle: String, val kind: Int? = null)

@Serializable
data class SettingsDto(val userAgent: String = "", val downloadLimit: Int = 1, val pageSize: Int = 50)

@Serializable
data class EnqueueDownloadRequest(val channelId: Long)

private val Playlist.isXtreamNative get() = url == null && xtreamBase != null

private val Playlist.mode: String get() = when {
    url != null -> "url"
    xtreamBase != null -> "xtream"
    else -> "file"
}

private fun Playlist.toDto() = PlaylistDto(
    id = id, name = name, mode = mode, hasXtreamPanel = xtreamBase != null,
    lastRefreshedMs = lastRefreshedMs, channelCount = channelCount,
)

private fun Playlist.toCredentials() = PlaylistCredentialsDto(
    mode = mode, url = url, epgUrl = epgUrl,
    xtreamBase = xtreamBase, xtreamUser = xtreamUser, xtreamPass = xtreamPass,
)

private fun ApplicationCall.id(name: String = "id"): Long =
    parameters[name]?.toLongOrNull() ?: throw IllegalArgumentException("Bad id")

/** The download id when [url] is this server's own downloaded-file endpoint. */
private val DOWNLOAD_FILE_URL = Regex("""/api/downloads/(\d+)/file""")
private fun downloadFileId(url: String): Long? =
    DOWNLOAD_FILE_URL.find(url)?.groupValues?.get(1)?.toLongOrNull()

/** Resolve the remux `u` param to a source ffmpeg may open: a provider token or this
 *  server's own download-file URL only, never an arbitrary value (no local-file/SSRF). */
private suspend fun remuxSource(g: ServerGraph, call: ApplicationCall): String? {
    val raw = call.request.queryParameters["u"] ?: throw IllegalArgumentException("Missing target url")
    val decoded = g.cipher.tryDecrypt(raw)
    val source = downloadFileId(decoded ?: raw)?.let { g.downloads.fileFor(it)?.second?.toString() }
        ?: decoded?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    if (source == null) call.respond(HttpStatusCode.BadRequest, MessageDto("Invalid or missing target url"))
    return source
}

// Every provider URL leaves the server only as an opaque token (StreamCipher).
private fun Channel.forClient(c: StreamCipher) = copy(url = c.encrypt(url), logo = c.encryptOrNull(logo))
private fun List<Channel>.forClient(c: StreamCipher) = map { it.forClient(c) }
private fun XtreamSeries.forClient(c: StreamCipher) = copy(cover = c.encryptOrNull(cover))
private fun SeriesGroup.forClient(c: StreamCipher) = copy(logo = c.encryptOrNull(logo))
private fun SeriesHitDto.forClient(c: StreamCipher) = copy(logo = c.encryptOrNull(logo))
private fun Metadata.forClient(c: StreamCipher) = copy(
    posterUrl = c.encryptOrNull(posterUrl),
    castJson = castJson?.let { encodeCast(decodeCast(it).map { m -> m.copy(photo = c.encryptOrNull(m.photo)) }) },
)
/** Live/movie favorite keys are provider URLs (tokenized); series keys are not. */
private fun Favorite.tokenized(c: StreamCipher) =
    if (kind == ChannelKind.SERIES) this else copy(key = c.encrypt(key))

private fun RemuxService.RemuxDiagnostics.toDto() = RemuxDiagDto(
    videoCodec = videoCodec, transcodeVideo = transcodeVideo, videoEncoder = videoEncoder,
    nativeVideoCopy = nativeVideoCopy, audioCodec = audioCodec, audioChannels = audioChannels,
    audioLabel = audioLabel, subtitleCount = subtitleCount, segmentCount = segmentCount,
    timeshift = timeshift, providerKey = providerKey, connectionLimit = connectionLimit,
    ffmpegRunning = ffmpegRunning, durationSec = durationSec, lastLog = lastLog,
)

private fun escapeLike(q: String) = q.trim()
    .replace("\\", "\\\\")
    .replace("%", "\\%")
    .replace("_", "\\_")

private suspend fun sessionDtos(g: ServerGraph): List<SessionDto> = g.sessions.active().map { live ->
    val s = live.state
    val diag = s.remuxId?.let { g.remux.diagnostics(it) }?.toDto()
    SessionDto(
        id = live.id, ip = live.ip, userAgent = live.userAgent,
        playlistName = s.playlistId?.let { g.storage.playlists.get(it)?.name },
        title = s.title, kind = s.kind, logo = s.logo,
        positionMs = s.positionMs, durationMs = s.durationMs, paused = s.paused, live = s.live,
        startedAtMs = live.startedAtMs, lastSeenMs = live.lastSeenMs,
        stream = SessionStreamDto(s.engine, s.direct, s.audioTranscoded, s.preparing, diag),
    )
}

fun Route.api(g: ServerGraph) = route("/api") {

    route("/playlists") {
        get {
            call.respond(g.storage.playlists.getAll().map { it.toDto() })
        }
        post {
            val req = call.receive<PlaylistUpsertRequest>()
            val id = when (req.mode) {
                "xtream" -> g.playlists.addFromXtream(req.name, req.server, req.username, req.password)
                "url" -> g.playlists.addFromUrl(req.name, req.url, req.epgUrl)
                "file" -> g.playlists.importFromLines(req.name, req.content.lineSequence())
                else -> throw IllegalArgumentException("Unknown mode")
            }
            // First EPG pull right after adding.
            runCatching { g.epg.refresh(id) }
            call.respond((g.storage.playlists.get(id) ?: throw IllegalArgumentException("Missing playlist")).toDto())
        }

        route("/{id}") {
            put {
                val id = call.id()
                val req = call.receive<PlaylistUpsertRequest>()
                when (req.mode) {
                    "xtream" -> g.playlists.updateXtream(id, req.name, req.server, req.username, req.password)
                    "url" -> g.playlists.updateUrl(id, req.name, req.url, req.epgUrl)
                    "file" ->
                        if (req.content.isNotBlank()) g.playlists.replaceFromLines(id, req.name, req.content.lineSequence())
                        else g.playlists.rename(id, req.name)
                    else -> throw IllegalArgumentException("Unknown mode")
                }
                call.respond((g.storage.playlists.get(id) ?: throw IllegalArgumentException("Missing playlist")).toDto())
            }
            delete {
                g.playlists.delete(call.id())
                call.respond(HttpStatusCode.NoContent)
            }
            // Sensitive fields for the edit dialog, off the always-loaded list.
            get("/credentials") {
                val playlist = g.storage.playlists.get(call.id())
                    ?: return@get call.respond(HttpStatusCode.NotFound, MessageDto("No such playlist"))
                call.respond(playlist.toCredentials())
            }
            post("/refresh") {
                val id = call.id()
                val force = call.request.queryParameters["force"] == "true"
                g.playlists.refresh(id, force)
                runCatching { g.epg.refresh(id, force) }
                call.respond((g.storage.playlists.get(id) ?: throw IllegalArgumentException("Missing playlist")).toDto())
            }
            // Wipe saved watch progress for this playlist's channels (only).
            post("/clear-progress") {
                g.storage.resume.deleteForPlaylist(call.id())
                call.respond(HttpStatusCode.NoContent)
            }
            get {
                val id = call.id()
                val playlist = g.storage.playlists.get(id) ?: run {
                    call.respond(HttpStatusCode.NotFound, MessageDto("No such playlist"))
                    return@get
                }
                val seriesCount = if (playlist.isXtreamNative) {
                    g.storage.xtreamSeries.observeCount(id).first()
                } else {
                    g.storage.channels.observeCount(id, ChannelKind.SERIES).first()
                }
                call.respond(
                    PlaylistDetailDto(
                        playlist = playlist.toDto(),
                        isXtreamNative = playlist.isXtreamNative,
                        liveCount = g.storage.channels.observeCount(id, ChannelKind.LIVE).first(),
                        movieCount = g.storage.channels.observeCount(id, ChannelKind.MOVIE).first(),
                        seriesCount = seriesCount,
                    )
                )
            }
            get("/groups") {
                val id = call.id()
                val kind = call.request.queryParameters["kind"]?.toIntOrNull() ?: ChannelKind.LIVE
                val playlist = g.storage.playlists.get(id)
                val groups = if (kind == ChannelKind.SERIES && playlist?.isXtreamNative == true) {
                    g.storage.xtreamSeries.observeCategories(id).first()
                } else {
                    g.storage.channels.observeGroups(id, kind).first()
                }
                call.respond(groups)
            }
            get("/channels") {
                val id = call.id()
                val kind = call.request.queryParameters["kind"]?.toIntOrNull() ?: ChannelKind.LIVE
                val group = call.request.queryParameters["group"] ?: ""
                call.respond(g.storage.channels.observeInGroup(id, kind, group).first().forClient(g.cipher))
            }
            get("/series-groups") {
                val id = call.id()
                val group = call.request.queryParameters["group"]
                val groups = if (group != null) {
                    g.storage.channels.observeSeriesInGroup(id, group).first()
                } else {
                    g.storage.channels.observeAllSeries(id).first()
                }
                // Exclude cached Xtream episodes (xs: keys): they belong to the panel catalog.
                call.respond(groups.filterNot { it.seriesKey.startsWith("xs:") }.map { it.forClient(g.cipher) })
            }
            get("/xtream-series") {
                val id = call.id()
                val category = call.request.queryParameters["category"]
                val series = if (category != null) {
                    g.storage.xtreamSeries.observeInCategory(id, category).first()
                } else {
                    g.storage.xtreamSeries.observeAll(id).first()
                }
                call.respond(series.map { it.forClient(g.cipher) })
            }
            get("/now-airing") {
                call.respond(g.epg.nowAiring(call.id()))
            }
            get("/guide-ids") {
                call.respond(g.epg.observeGuideIds(call.id()).first())
            }
            get("/search") {
                val id = call.id()
                val q = call.request.queryParameters["q"]?.trim().orEmpty()
                if (q.length < 2) {
                    call.respond(SearchResultsDto())
                    return@get
                }
                val escaped = escapeLike(q)
                val rows = g.storage.channels.search(id, escaped)
                // Collapse episodes to one row per show; xs: keys excluded (catalog searched separately).
                val m3uSeries = rows.filter { it.kind == ChannelKind.SERIES }
                    .filterNot { it.seriesKey?.startsWith("xs:") == true }
                    .groupBy { it.seriesKey ?: it.name }
                    .map { (key, episodes) ->
                        SeriesHitDto(
                            seriesKey = key,
                            count = episodes.size,
                            logo = episodes.firstOrNull { it.logo != null }?.logo,
                            groupTitle = episodes.first().groupTitle,
                        )
                    }
                val xtreamSeries = g.storage.xtreamSeries.search(id, escaped).map {
                    SeriesHitDto(
                        seriesKey = it.name,
                        count = 0,
                        logo = it.cover,
                        groupTitle = it.categoryName,
                        xtreamSeriesId = it.seriesId,
                    )
                }
                call.respond(
                    SearchResultsDto(
                        live = rows.filter { it.kind == ChannelKind.LIVE }.forClient(g.cipher),
                        movies = rows.filter { it.kind == ChannelKind.MOVIE }.forClient(g.cipher),
                        series = (xtreamSeries + m3uSeries).map { it.forClient(g.cipher) },
                    )
                )
            }
            get("/account") {
                val playlist = g.storage.playlists.get(call.id())
                val force = call.request.queryParameters["force"] == "true"
                val info = playlist?.let { g.account.accountInfo(it, force) }
                if (info == null) call.respond(HttpStatusCode.NotFound, MessageDto("No account API for this playlist"))
                else call.respond(info)
            }
            put("/group-kind") {
                val req = call.receive<GroupKindRequest>()
                g.playlists.setGroupOverride(call.id(), req.groupTitle, req.kind)
                call.respond(HttpStatusCode.NoContent)
            }
            get("/favorites") {
                call.respond(g.storage.favorites.getAll(call.id()).map { it.tokenized(g.cipher) })
            }
            put("/favorites") {
                val req = call.receive<Favorite>()
                g.storage.favorites.add(Favorite(call.id(), g.cipher.resolve(req.key), req.kind, nowMs()))
                call.respond(HttpStatusCode.NoContent)
            }
            delete("/favorites") {
                val raw = call.request.queryParameters["key"] ?: throw IllegalArgumentException("Missing key")
                g.storage.favorites.remove(call.id(), g.cipher.resolve(raw))
                call.respond(HttpStatusCode.NoContent)
            }
            get("/favorites/resolved") {
                val id = call.id()
                val favorites = g.storage.favorites.getAll(id)

                suspend fun channelsFor(kind: Int): List<Channel> {
                    val urls = favorites.filter { it.kind == kind }.map { it.key }
                    if (urls.isEmpty()) return emptyList()
                    return g.storage.channels.observeByUrls(id, kind, urls.take(900)).first().forClient(g.cipher)
                }

                val seriesHits = favorites.filter { it.kind == ChannelKind.SERIES }.mapNotNull { fav ->
                    if (fav.key.startsWith("x:")) {
                        val seriesId = fav.key.removePrefix("x:").toLongOrNull() ?: return@mapNotNull null
                        g.storage.xtreamSeries.get(id, seriesId)
                            ?.let { SeriesHitDto(it.name, 0, it.cover, it.categoryName, it.seriesId) }
                    } else {
                        g.storage.channels.observeAllSeries(id).first()
                            .firstOrNull { it.seriesKey == fav.key }
                            ?.let { SeriesHitDto(it.seriesKey, it.count, it.logo, it.groupTitle) }
                            ?: SeriesHitDto(fav.key, 0, null, "Series")
                    }
                }.sortedBy { it.seriesKey.lowercase() }.map { it.forClient(g.cipher) }

                call.respond(
                    FavoritesResolvedDto(
                        live = channelsFor(ChannelKind.LIVE),
                        movies = channelsFor(ChannelKind.MOVIE),
                        series = seriesHits,
                    )
                )
            }
            get("/series/{seriesKey}/episodes") {
                val id = call.id()
                val seriesKey = call.parameters["seriesKey"] ?: throw IllegalArgumentException("Missing key")
                call.respond(g.storage.channels.observeEpisodes(id, seriesKey).first().forClient(g.cipher))
            }
            get("/xseries/{seriesId}") {
                val id = call.id()
                val seriesId = call.id("seriesId")
                val series = g.storage.xtreamSeries.get(id, seriesId) ?: run {
                    call.respond(HttpStatusCode.NotFound, MessageDto("No such series"))
                    return@get
                }
                val error = runCatching { g.xtream.ensureEpisodes(id, seriesId) }.exceptionOrNull()
                val episodes = g.storage.channels.observeEpisodes(id, xtreamSeriesKey(seriesId)).first()
                call.respond(
                    XtreamSeriesDetailDto(
                        series = series.forClient(g.cipher),
                        episodes = episodes.forClient(g.cipher),
                        error = error?.let { "Couldn't load episodes: ${it.message}" }
                            ?.takeIf { episodes.isEmpty() },
                    )
                )
            }
        }
    }

    route("/channels/{id}") {
        suspend fun ApplicationCall.channelOr404(): Channel? {
            val channel = g.storage.channels.get(id())
            if (channel == null) respond(HttpStatusCode.NotFound, MessageDto("No such channel"))
            return channel
        }

        get {
            val ch = call.channelOr404() ?: return@get
            call.respond(ch.forClient(g.cipher))
        }
        get("/guide") {
            val ch = call.channelOr404() ?: return@get
            call.respond(g.xtream.guideFor(ch))
        }
        get("/catchup-url") {
            val ch = call.channelOr404() ?: return@get
            val start = call.request.queryParameters["start"]?.toLongOrNull()
                ?: throw IllegalArgumentException("Missing start")
            val end = call.request.queryParameters["end"]?.toLongOrNull()
                ?: throw IllegalArgumentException("Missing end")
            call.respond(CatchupUrlDto(g.xtream.catchupUrlFor(ch, start, end)?.let { g.cipher.encrypt(it) }))
        }
        get("/vod-info") {
            val ch = call.channelOr404() ?: return@get
            // Xtream movies have panel-provided details; keyless lookups are the fallback.
            val meta = ch.xtreamStreamId?.let { g.xtream.vodMetadata(ch) }
                ?: g.metadata.forTitle(isSeries = false, rawName = ch.name)
            call.respond(meta?.forClient(g.cipher) ?: Metadata(cacheKey = "", fetchedAtMs = 0))
        }
    }

    get("/meta") {
        val type = call.request.queryParameters["type"] ?: "movie"
        val title = call.request.queryParameters["title"] ?: throw IllegalArgumentException("Missing title")
        call.respond(
            g.metadata.forTitle(isSeries = type == "series", rawName = title)?.forClient(g.cipher)
                ?: Metadata(cacheKey = "", fetchedAtMs = 0)
        )
    }
    get("/meta/episode") {
        val series = call.request.queryParameters["series"] ?: throw IllegalArgumentException("Missing series")
        val season = call.request.queryParameters["season"]?.toIntOrNull()
        val episode = call.request.queryParameters["episode"]?.toIntOrNull()
        if (season == null || episode == null) {
            call.respond(Metadata(cacheKey = "", fetchedAtMs = 0))
            return@get
        }
        call.respond(g.metadata.episodeInfo(series, season, episode)?.forClient(g.cipher) ?: Metadata(cacheKey = "", fetchedAtMs = 0))
    }

    route("/resume") {
        get {
            call.respond(g.storage.resume.getAll().map { it.copy(url = g.cipher.encrypt(it.url)) })
        }
        put {
            val req = call.receive<ResumePoint>()
            g.storage.resume.upsert(ResumePoint(g.cipher.resolve(req.url), req.positionMs, req.durationMs, nowMs()))
            call.respond(HttpStatusCode.NoContent)
        }
        delete {
            val raw = call.request.queryParameters["url"] ?: throw IllegalArgumentException("Missing url")
            g.storage.resume.delete(g.cipher.resolve(raw))
            call.respond(HttpStatusCode.NoContent)
        }
    }

    route("/settings") {
        get {
            call.respond(
                SettingsDto(
                    userAgent = g.settings.userAgent,
                    downloadLimit = g.settings.downloadLimit,
                    pageSize = g.settings.pageSize,
                )
            )
        }
        put {
            val req = call.receive<SettingsDto>()
            g.settings.userAgent = req.userAgent.trim()
            g.settings.downloadLimit = req.downloadLimit.coerceIn(1, 3)
            g.http.userAgent = req.userAgent.trim().ifBlank { ServerHttp.DEFAULT_USER_AGENT }
            call.respond(HttpStatusCode.NoContent)
        }
    }

    route("/downloads") {
        get {
            call.respond(g.storage.downloads.observeAll().first().map { it.copy(url = g.cipher.encrypt(it.url)) })
        }
        post {
            val req = call.receive<EnqueueDownloadRequest>()
            val channel = g.storage.channels.get(req.channelId)
                ?: throw IllegalArgumentException("No such channel")
            val blocked = g.downloads.enqueue(channel)
            call.respond(MessageDto(blocked ?: "Download started: ${channel.name}"))
        }
        route("/{id}") {
            post("/pause") {
                g.downloads.pause(call.id())
                call.respond(HttpStatusCode.NoContent)
            }
            post("/resume") {
                g.downloads.resume(call.id())
                call.respond(HttpStatusCode.NoContent)
            }
            post("/retry") {
                g.downloads.retry(call.id())
                call.respond(HttpStatusCode.NoContent)
            }
            delete {
                g.downloads.delete(call.id())
                call.respond(HttpStatusCode.NoContent)
            }
            get("/file") {
                val (item, path) = g.downloads.fileFor(call.id()) ?: run {
                    call.respond(HttpStatusCode.NotFound, MessageDto("Download not finished"))
                    return@get
                }
                if (call.request.queryParameters["save"] == "1") {
                    call.response.header(
                        HttpHeaders.ContentDisposition,
                        ContentDisposition.Attachment
                            .withParameter(ContentDisposition.Parameters.FileName, path.fileName.toString())
                            .toString(),
                    )
                }
                call.respondFile(path.toFile())
            }
        }
    }

    // Active web-client playback sessions. Viewers report state and receive
    // pause/play/message over a WebSocket; the REST routes stay as a fallback.
    route("/sessions") {
        get { call.respond(sessionDtos(g)) }
        post("/heartbeat") {
            val dto = call.receive<SessionHeartbeatDto>()
            val ip = g.trustedProxies.clientIp(call)
            val userAgent = call.request.headers[HttpHeaders.UserAgent].orEmpty()
            call.respond(HeartbeatResponseDto(g.sessions.heartbeat(ip, userAgent, dto)))
        }
        post("/{id}/command") {
            val id = call.parameters["id"] ?: throw IllegalArgumentException("Missing id")
            val cmd = call.receive<SessionCommandDto>()
            if (cmd.type !in setOf("pause", "play", "message")) {
                throw IllegalArgumentException("Unknown command")
            }
            if (g.sessions.enqueue(id, cmd)) call.respond(HttpStatusCode.NoContent)
            else call.respond(HttpStatusCode.NotFound, MessageDto("No such session"))
        }
        // Live session list for the dashboard: pushed on connect and on every change.
        webSocket("/ws") {
            suspend fun push() = send(Frame.Text(Json.encodeToString(ListSerializer(SessionDto.serializer()), sessionDtos(g))))
            push()
            g.sessions.changes().collect { push() }
        }
        // Viewer channel: reports state (incoming heartbeats) and receives commands (outgoing).
        webSocket("/{id}/ws") {
            val id = call.parameters["id"] ?: return@webSocket
            val ip = g.trustedProxies.clientIp(call)
            val userAgent = call.request.headers[HttpHeaders.UserAgent].orEmpty()
            val sender = launch {
                suspend fun flush() = g.sessions.drainCommands(id).forEach {
                    send(Frame.Text(Json.encodeToString(SessionCommandDto.serializer(), it)))
                }
                flush()
                for (signal in g.sessions.commandSignal(id)) flush()
            }
            try {
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    runCatching { Json.decodeFromString(SessionHeartbeatDto.serializer(), frame.readText()) }
                        .getOrNull()?.let { g.sessions.heartbeat(ip, userAgent, it) }
                }
            } finally {
                sender.cancel()
            }
        }
        delete("/{id}") {
            g.sessions.remove(call.parameters["id"] ?: "")
            call.respond(HttpStatusCode.NoContent)
        }
    }

    get("/stream") { g.proxy.handle(call) }
    get("/img") { g.proxy.handle(call, cache = true) }

    // Live audio rescue: client fallback for an undecodable audio track (AudioTranscoder).
    get("/transcode") {
        if (!g.remux.available) {
            call.respond(HttpStatusCode.NotImplemented, MessageDto("ffmpeg is not installed on the server"))
            return@get
        }
        // Live transcode always receives a token; a raw URL is not accepted.
        val url = call.request.queryParameters["u"]?.let { g.cipher.tryDecrypt(it) }
        if (url.isNullOrBlank() || !(url.startsWith("http://") || url.startsWith("https://"))) {
            call.respond(HttpStatusCode.BadRequest, MessageDto("Invalid or missing target url"))
            return@get
        }
        g.transcoder.stream(url, call)
    }

    // ffmpeg-backed VOD playback: a pre-listed VOD HLS playlist whose fMP4 segments one
    // ffmpeg produces on demand, played by hls.js (RemuxService).
    get("/remux/available") { call.respond(RemuxAvailableDto(g.remux.available)) }
    get("/remux/start") {
        if (!g.remux.available) {
            call.respond(HttpStatusCode.NotImplemented, MessageDto("ffmpeg is not installed on the server"))
            return@get
        }
        val source = remuxSource(g, call) ?: return@get
        val audio = call.request.queryParameters["audio"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        // hevc=1: browser can decode HEVC, so copy non-H.264 video instead of transcoding.
        val clientHevc = call.request.queryParameters["hevc"] == "1"
        // Catch-up forces the remux on so its finite recording becomes seekable.
        val force = call.request.queryParameters["force"] == "1"
        val limit = g.connectionLimit(source)
        try {
            val result = withContext(Dispatchers.IO) { g.remux.start(source, audio, clientHevc, force, limit) }
            call.respond(RemuxStartDto(result.id, result.playlistUrl, result.durationSec,
                result.audioTracks, result.subtitleTracks, result.nativeVideoCopy))
        } catch (e: RemuxService.NoExtraTracksException) {
            call.respond(HttpStatusCode.NotFound, MessageDto(e.message ?: "No extra tracks"))
        } catch (e: RemuxService.ConnectionLimitException) {
            call.respond(HttpStatusCode.TooManyRequests, MessageDto(e.message ?: "Connection limit reached"))
        } catch (e: IllegalStateException) {
            call.respond(HttpStatusCode.BadGateway, MessageDto(e.message ?: "Remux failed"))
        }
    }
    delete("/remux/{id}") {
        g.remux.stop(call.parameters["id"]!!)
        call.respond(HttpStatusCode.NoContent)
    }
    // Master/media playlists, fMP4 init, numbered segments and WebVTT subtitle renditions.
    get("/remux/{id}/{file}") {
        val id = call.parameters["id"]!!
        val file = call.parameters["file"]!!
        when {
            file == "master.m3u8" -> g.remux.master(id, call)
            file == "main.m3u8" -> g.remux.playlist(id, call)
            file == "init.mp4" -> g.remux.initSegment(id, call)
            file.startsWith("main") && (file.endsWith(".m4s") || file.endsWith(".ts")) ->
                file.removePrefix("main").substringBefore('.').toIntOrNull()
                    ?.let { g.remux.segment(id, it, call) }
                    ?: call.respond(HttpStatusCode.NotFound, MessageDto("Unknown segment"))
            // sub_<i>.m3u8 (a rendition's playlist) and sub_<i>_<n>.vtt (its segments).
            file.startsWith("sub_") && file.endsWith(".m3u8") ->
                file.removePrefix("sub_").removeSuffix(".m3u8").toIntOrNull()
                    ?.let { g.remux.subtitlePlaylist(id, it, call) }
                    ?: call.respond(HttpStatusCode.NotFound, MessageDto("Unknown subtitle"))
            file.startsWith("sub_") && file.endsWith(".vtt") -> {
                val parts = file.removePrefix("sub_").removeSuffix(".vtt").split('_')
                val i = parts.getOrNull(0)?.toIntOrNull()
                val n = parts.getOrNull(1)?.toIntOrNull()
                if (i != null && n != null) g.remux.subtitleSegment(id, i, n, call)
                else call.respond(HttpStatusCode.NotFound, MessageDto("Unknown subtitle"))
            }
            else -> call.respond(HttpStatusCode.NotFound, MessageDto("Unknown remux file"))
        }
    }
}
