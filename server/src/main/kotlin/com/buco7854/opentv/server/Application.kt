package com.buco7854.opentv.server

import com.buco7854.opentv.core.log.CoreLog
import com.buco7854.opentv.core.repo.AccountRepository
import com.buco7854.opentv.core.repo.EpgRepository
import com.buco7854.opentv.core.repo.MetadataRepository
import com.buco7854.opentv.core.repo.PlaylistRepository
import com.buco7854.opentv.core.repo.XtreamRepository
import com.buco7854.opentv.core.repo.XtreamUnreachableException
import com.buco7854.opentv.core.storage.Storage
import com.buco7854.opentv.core.util.nowMs
import com.buco7854.opentv.core.xtream.XtreamApi
import com.buco7854.opentv.core.xtream.XtreamAuthException
import com.buco7854.opentv.data.createRoomStorage
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.http.content.singlePageApplication
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.util.cio.ChannelIOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

@Serializable
data class HealthDto(
    val status: String,
    val ffmpegAvailable: Boolean? = null,
)

/** Owns every long-lived server component and closes them in dependency order. */
class ServerRuntime(
    val graph: ServerGraph,
    private val storage: Storage,
    private val connections: ProviderConnections,
) : AutoCloseable {
    private val closed = AtomicBoolean()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        graph.downloads.close()
        graph.liveRelay.close()
        graph.remux.close()
        graph.streamGate.close()
        connections.closeAll()
        storage.close()
    }
}

/** Manual composition root: platform adapters are wired to domain services in one place. */
object ServerBootstrap {
    fun create(config: ServerConfig): ServerRuntime {
        Files.createDirectories(config.dataDir)
        val log = LoggerFactory.getLogger("opentv")
        val storage = createRoomStorage(config.dataDir.resolve("opentv.db").toString())
        val settings = ServerSettings(config.dataDir, config.pageSize)
        val http = ServerHttp().apply {
            settings.userAgent.takeIf { it.isNotBlank() }?.let { userAgent = it }
        }
        val cipher = StreamCipher(settings.streamKey)
        val coreLog = CoreLog { context, error -> log.warn("{}: {}", context, error.message) }
        val xtreamApi = XtreamApi(http.fetcher)
        val playlists = PlaylistRepository(storage, xtreamApi, http.conditionalFetcher, coreLog)
        val epg = EpgRepository(storage, http.conditionalFetcher)
        val account = AccountRepository(xtreamApi, coreLog)
        val connections = ProviderConnections()
        val processRunner = JvmMediaProcessRunner
        val connectionLimit: suspend (String) -> Int = limit@{ url ->
            if (!url.startsWith("http")) return@limit Int.MAX_VALUE
            val playlist = storage.playlists.getAll().firstOrNull { candidate ->
                val base = candidate.xtreamBase
                val user = candidate.xtreamUser
                base != null && user != null && url.startsWith(base) && url.contains(user)
            } ?: return@limit config.fallbackProviderConnections
            (account.accountInfo(playlist)?.maxConnections ?: 0)
                .takeIf { it > 0 } ?: config.fallbackProviderConnections
        }
        val downloads = DownloadManager(
            storage, http, settings, config.dataDir, connections, connectionLimit
        )
        val streamGate = StreamGate(connections)
        val remux = RemuxService(
            http,
            connections,
            videoEncoder = config.videoEncoder,
            x264Preset = config.x264Preset,
            processRunner = processRunner,
        )
        val sessions = PlaybackSessionRegistry()
        val liveRelay = LiveRelay(http, connections, { remux.available }, processRunner)
        sessions.onMemberLeave(liveRelay::drop)
        val xtream = XtreamRepository(storage, xtreamApi, epg, account, coreLog)
        val metadata = MetadataRepository(storage.metadata, http.fetcher, coreLog)
        val apiServices = ApiServices(
            playlists = PlaylistApplicationService(
                storage,
                playlists,
                epg,
                xtream,
                account,
                cipher,
            ),
            library = LibraryApplicationService(
                storage,
                xtream,
                metadata,
                cipher,
                settings,
                http,
            ),
            downloads = DownloadApplicationService(storage, downloads, cipher),
            sessions = SessionApplicationService(
                storage,
                sessions,
                remux,
                cipher,
                streamGate,
                connectionLimit,
            ),
        )
        val proxy = StreamProxy(http, cipher, streamGate, connectionLimit)
        val transcoder = AudioTranscoder(http, processRunner)
        val mediaApi = MediaRouteDependencies(
            proxy,
            cipher,
            downloads,
            sessions,
            streamGate,
            liveRelay,
            transcoder,
            remux,
            connectionLimit,
        )
        val graph = ServerGraph(
            apiServices = apiServices,
            mediaApi = mediaApi,
            storage = storage,
            http = http,
            playlists = playlists,
            epg = epg,
            xtream = xtream,
            account = account,
            metadata = metadata,
            proxy = proxy,
            settings = settings,
            downloads = downloads,
            remux = remux,
            transcoder = transcoder,
            cipher = cipher,
            sessions = sessions,
            streamGate = streamGate,
            liveRelay = liveRelay,
            trustedProxies = TrustedProxies.fromSpec(config.trustedProxies.orEmpty()),
            connectionLimit = connectionLimit,
        )
        downloads.start()
        runBlocking {
            storage.resume.prune(nowMs() - 90L * 24 * 60 * 60 * 1000)
        }
        log.warn("OpenTV web has no authentication - put it behind an authenticated reverse proxy.")
        return ServerRuntime(graph, storage, connections)
    }
}

/** Installs the HTTP adapter around an already-constructed runtime graph. */
fun Application.openTvModule(
    graph: ServerGraph,
    runtime: ServerRuntime,
    apiSecurity: ApiSecurity = ApiSecurity.openAccess(),
) {
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        })
    }
    install(Compression)
    install(PartialContent)
    install(WebSockets)
    install(StatusPages) {
        exception<ResourceNotFound> { call, cause ->
            call.respond(
                HttpStatusCode.NotFound,
                ApiErrorDto("not_found", cause.message ?: "Resource not found"),
            )
        }
        exception<UnauthenticatedApiException> { call, _ ->
            call.respond(
                HttpStatusCode.Unauthorized,
                ApiErrorDto("unauthenticated", "Authentication is required"),
            )
        }
        exception<ForbiddenApiException> { call, _ ->
            call.respond(
                HttpStatusCode.Forbidden,
                ApiErrorDto("forbidden", "You are not allowed to perform this action"),
            )
        }
        exception<IllegalArgumentException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ApiErrorDto("invalid_request", cause.message ?: "Bad request"),
            )
        }
        exception<XtreamAuthException> { call, cause ->
            call.respond(
                HttpStatusCode.BadGateway,
                ApiErrorDto("provider_login_rejected", cause.message ?: "Login rejected"),
            )
        }
        exception<XtreamUnreachableException> { call, cause ->
            call.respond(
                HttpStatusCode.BadGateway,
                ApiErrorDto("provider_unreachable", cause.message ?: "Provider unreachable"),
            )
        }
        exception<CancellationException> { _, cause -> throw cause }
        exception<Throwable> { call, cause ->
            if (cause.isClientAbort()) return@exception
            LoggerFactory.getLogger("opentv").warn("Request failed", cause)
            runCatching {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ApiErrorDto("internal_error", "Internal error"),
                )
            }
        }
    }
    monitor.subscribe(ApplicationStopped) { runtime.close() }
    routing {
        healthRoutes { graph.remux.available }
        api(graph, apiSecurity)
        singlePageApplication {
            useResources = true
            filesPath = "web"
            defaultPage = "index.html"
        }
    }
}

internal fun Route.healthRoutes(ffmpegAvailable: () -> Boolean) {
    get("/health/live") { call.respond(HealthDto("ok")) }
    get("/health/ready") {
        call.respond(HealthDto("ready", ffmpegAvailable = ffmpegAvailable()))
    }
}

/** True when the peer dropped the connection mid-response (stop, seek, tab closed). */
private fun Throwable.isClientAbort(): Boolean =
    generateSequence(this) { it.cause?.takeIf { cause -> cause !== it } }.any { error ->
        error is ChannelIOException || (error is java.io.IOException && error.message?.let { message ->
            "Cannot write to a channel" in message ||
                "Broken pipe" in message ||
                "Connection reset" in message
        } == true)
    }
