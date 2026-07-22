package com.buco7854.opentv.server

import com.buco7854.opentv.core.log.CoreLog
import com.buco7854.opentv.core.repo.AccountRepository
import com.buco7854.opentv.core.repo.EpgRepository
import com.buco7854.opentv.core.repo.MetadataRepository
import com.buco7854.opentv.core.repo.PlaylistRepository
import com.buco7854.opentv.core.repo.XtreamRepository
import com.buco7854.opentv.core.repo.XtreamUnreachableException
import com.buco7854.opentv.core.util.nowMs
import com.buco7854.opentv.core.xtream.XtreamApi
import com.buco7854.opentv.core.xtream.XtreamAuthException
import com.buco7854.opentv.data.createRoomStorage
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.singlePageApplication
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.util.cio.ChannelIOException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

/** True when the peer dropped the connection mid-response (stop, seek, tab closed). */
private fun Throwable.isClientAbort(): Boolean =
    generateSequence(this) { it.cause?.takeIf { cause -> cause !== it } }.any { error ->
        error is ChannelIOException || (error is java.io.IOException && error.message?.let { message ->
            "Cannot write to a channel" in message ||
                "Broken pipe" in message ||
                "Connection reset" in message
        } == true)
    }

/** The web server's only local state outside the shared database. */
class ServerSettings(private val file: Path) {
    private val props = java.util.Properties().apply {
        if (file.exists()) file.toFile().inputStream().use { load(it) }
    }

    private fun save() = file.toFile().outputStream().use { props.store(it, null) }

    var userAgent: String = props.getProperty("userAgent", "")
        set(value) {
            field = value
            props.setProperty("userAgent", value)
            save()
        }

    /** Simultaneous transfers; kept low to respect provider connection caps. */
    var downloadLimit: Int = props.getProperty("downloadLimit", "1").toIntOrNull() ?: 1
        set(value) {
            field = value
            props.setProperty("downloadLimit", value.toString())
            save()
        }

    /** Items per page in the web client's long lists (OPENTV_PAGE_SIZE). */
    val pageSize: Int = System.getenv("OPENTV_PAGE_SIZE")?.toIntOrNull()?.coerceIn(10, 1000) ?: 50

    /** Persisted key for tokenizing provider URLs (StreamCipher); stable so old tokens resolve. */
    val streamKey: String = props.getProperty("streamKey") ?: run {
        val bytes = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        java.util.Base64.getEncoder().encodeToString(bytes).also {
            props.setProperty("streamKey", it)
            save()
        }
    }
}

/**
 * OpenTV web server: same :core/:data domain layer as the Android app, plus a REST
 * API, a CORS/mixed-content stream proxy, and the static web client.
 * No authentication - run it behind an authenticating reverse proxy.
 */
fun main() {
    val log = LoggerFactory.getLogger("opentv")
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val dataDir = Path.of(System.getenv("OPENTV_DATA") ?: "./data")
    Files.createDirectories(dataDir)

    val storage = createRoomStorage(dataDir.resolve("opentv.db").toString())
    val settings = ServerSettings(dataDir.resolve("settings.properties"))
    val http = ServerHttp().apply {
        settings.userAgent.takeIf { it.isNotBlank() }?.let { userAgent = it }
    }
    val cipher = StreamCipher(settings.streamKey)
    val coreLog = CoreLog { context, error -> log.warn("{}: {}", context, error.message) }
    val xtreamApi = XtreamApi(http.fetcher)
    val playlists = PlaylistRepository(storage, xtreamApi, http.conditionalFetcher, coreLog)
    val epg = EpgRepository(storage, http.conditionalFetcher)
    val account = AccountRepository(xtreamApi, coreLog)
    // One provider-connection budget shared by playback and downloads, capped at the
    // panel's max_connections (default 1 when it can't be told; OPENTV_REMUX_CONNECTIONS overrides).
    val connections = ProviderConnections()
    val connectionLimit: suspend (String) -> Int = limit@{ url ->
        // Local files (played-back downloads) touch no provider - never cap them.
        if (!url.startsWith("http")) return@limit Int.MAX_VALUE
        val fallback = System.getenv("OPENTV_REMUX_CONNECTIONS")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val playlist = storage.playlists.getAll().firstOrNull { pl ->
            val base = pl.xtreamBase
            val user = pl.xtreamUser
            base != null && user != null && url.startsWith(base) && url.contains(user)
        } ?: return@limit fallback
        (account.accountInfo(playlist)?.maxConnections ?: 0).takeIf { it > 0 } ?: fallback
    }
    val downloads = DownloadManager(storage, http, settings, dataDir, connections, connectionLimit)
    val streamGate = StreamGate(connections)
    val graph = ServerGraph(
        storage = storage,
        http = http,
        playlists = playlists,
        epg = epg,
        xtream = XtreamRepository(storage, xtreamApi, epg, account, coreLog),
        account = account,
        metadata = MetadataRepository(storage.metadata, http.fetcher, coreLog),
        proxy = StreamProxy(http, cipher, streamGate, connectionLimit),
        settings = settings,
        downloads = downloads,
        remux = RemuxService(http, connections),
        transcoder = AudioTranscoder(http),
        cipher = cipher,
        sessions = PlaybackSessionRegistry(),
        streamGate = streamGate,
        liveRelay = LiveRelay(http, connections),
        trustedProxies = TrustedProxies.fromEnv(),
        connectionLimit = connectionLimit,
    )
    downloads.start()

    // Saved VOD positions older than 90 days are noise, not resume points.
    kotlinx.coroutines.runBlocking {
        storage.resume.prune(nowMs() - 90L * 24 * 60 * 60 * 1000)
    }

    log.warn("OpenTV web has no authentication - put it behind an authenticated reverse proxy.")

    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            })
        }
        install(Compression)
        // Range support for serving downloaded files.
        install(PartialContent)
        install(WebSockets)
        install(StatusPages) {
            exception<IllegalArgumentException> { call, cause ->
                call.respond(HttpStatusCode.BadRequest, MessageDto(cause.message ?: "Bad request"))
            }
            exception<XtreamAuthException> { call, cause ->
                call.respond(HttpStatusCode.BadGateway, MessageDto(cause.message ?: "Login rejected"))
            }
            exception<XtreamUnreachableException> { call, cause ->
                call.respond(HttpStatusCode.BadGateway, MessageDto(cause.message ?: "Provider unreachable"))
            }
            exception<Throwable> { call, cause ->
                // Client abort is routine and the dead connection can't take a reply.
                if (cause.isClientAbort()) return@exception
                LoggerFactory.getLogger("opentv").warn("Request failed", cause)
                runCatching {
                    call.respond(HttpStatusCode.InternalServerError, MessageDto(cause.message ?: "Internal error"))
                }
            }
        }
        routing {
            api(graph)
            singlePageApplication {
                useResources = true
                filesPath = "web"
                defaultPage = "index.html"
            }
        }
    }.start(wait = true)
}
