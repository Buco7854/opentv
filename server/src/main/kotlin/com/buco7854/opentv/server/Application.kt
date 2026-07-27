package com.buco7854.opentv.server

import com.buco7854.opentv.core.log.CoreLog
import com.buco7854.opentv.core.log.ProviderSecrets
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
import com.buco7854.opentv.serverdata.createServerUserDatabase
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.http.ContentType
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.deflate
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.compression.matchContentType
import io.ktor.server.plugins.compression.minimumSize
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.request.path
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
    private val userDatabase: com.buco7854.opentv.serverdata.db.ServerUserDatabase,
    private val connections: ProviderConnections,
) : AutoCloseable {
    private val closed = AtomicBoolean()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        graph.sessions.close()
        graph.downloads.close()
        graph.liveRelay.close()
        graph.remux.close()
        graph.streamGate.close()
        connections.closeAll()
        userDatabase.close()
        storage.close()
    }
}

/** Manual composition root: platform adapters are wired to domain services in one place. */
object ServerBootstrap {
    fun create(config: ServerConfig): ServerRuntime {
        Files.createDirectories(config.dataDir)
        val log = LoggerFactory.getLogger("opentv")
        val storage = createRoomStorage(config.dataDir.resolve("opentv.db").toString())
        val userDatabase = createServerUserDatabase(config.dataDir.resolve("server-users.db").toString())
        val cleanup = RuntimeUserStateCleanupCoordinator()
        val contentIdentities = ContentIdentityService(userDatabase, storage)
        val auth = AuthService(
            userDatabase,
            config.auth,
            config.dataDir,
            playlistExists = { storage.playlists.get(it) != null },
            cleanup = cleanup,
            resumeTitles = contentIdentities::titlesByContentId,
        )
        val oidc = OidcService(auth, config.auth)
        val webAuthn = WebAuthnService(userDatabase, auth, config.auth)
        val deviceLink = DeviceLinkService(userDatabase, auth, config.auth)
        val userActivity = UserActivityService(userDatabase, auth, contentIdentities)
        val settings = ServerSettings(config.dataDir, config.pageSize)
        val http = ServerHttp().apply {
            // A stored agent predates the validation, or was hand-edited: fall back rather than
            // start a server whose every provider request throws.
            settings.userAgent
                .takeIf { it.isNotBlank() && ServerHttp.isUsableUserAgent(it) }
                ?.let { userAgent = it }
        }
        val cipher = StreamCipher(settings.streamKey)
        val coreLog = CoreLog { context, error -> log.warn("{}: {}", context, error.message) }
        val xtreamApi = XtreamApi(http.fetcher)
        val account = AccountRepository(xtreamApi, coreLog)
        val playlists = PlaylistRepository(storage, xtreamApi, http.conditionalFetcher, coreLog, account)
        val epg = EpgRepository(storage, http.conditionalFetcher)
        val connections = ProviderConnections()
        val processRunner = JvmMediaProcessRunner
        val connectionLimits = ProviderConnectionLimits(
            storage, account, config.fallbackProviderConnections,
        )
        val connectionLimit: suspend (String) -> Int = connectionLimits::forUrl
        val downloads = DownloadManager(
            userDatabase, http, settings, config.dataDir, connections, connectionLimit
        )
        val streamGate = StreamGate(connections)
        val remux = RemuxService(
            http,
            connections,
            videoEncoder = config.videoEncoder,
            x264Preset = config.x264Preset,
            processRunner = processRunner,
        )
        val playbackCleanup = RuntimePlaybackLeaseCleanup()
        val sessions = PlaybackSessionRegistry(cleanup = playbackCleanup)
        val mediaGrants = PlaybackMediaGrants(sessions)
        val liveRelay = LiveRelay(http, connections, { remux.available }, processRunner)
        val proxy = StreamProxy(http, cipher, streamGate, connectionLimit)
        val transcoder = AudioTranscoder(http, processRunner)
        cleanup.bind(sessions, downloads)
        playbackCleanup.bind(mediaGrants, proxy, liveRelay, transcoder, streamGate, remux)
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
                auth,
                contentIdentities,
                userActivity,
                userDatabase,
                downloads,
                cleanup,
            ),
            library = LibraryApplicationService(
                storage,
                xtream,
                metadata,
                cipher,
                settings,
                http,
                auth,
                contentIdentities,
                userActivity,
            ),
            downloads = DownloadApplicationService(downloads, contentIdentities, auth),
            sessions = SessionApplicationService(
                storage,
                sessions,
                remux,
                cipher,
                streamGate,
                connectionLimit,
                auth,
                contentIdentities,
                mediaGrants,
                xtream,
                downloads,
                cleanup,
            ),
        )
        val mediaApi = MediaRouteDependencies(
            proxy,
            cipher,
            downloads,
            sessions,
            streamGate,
            liveRelay,
            transcoder,
            remux,
            mediaGrants,
            connectionLimit,
            auth,
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
            auth = auth,
            oidc = oidc,
            webAuthn = webAuthn,
            deviceLink = deviceLink,
            authConfig = config.auth,
            userDatabase = userDatabase,
            contentIdentities = contentIdentities,
            userActivity = userActivity,
        )
        val runtime = ServerRuntime(graph, storage, userDatabase, connections)
        try {
            runBlocking {
                auth.initialize()
                oidc.validateConfiguration()
                apiServices.playlists.reconcilePendingDeletions()
                userActivity.prune()
            }
            downloads.start()
            return runtime
        } catch (error: Throwable) {
            runtime.close()
            throw error
        }
    }
}

/** Installs the HTTP adapter around an already-constructed runtime graph. */
fun Application.openTvModule(
    graph: ServerGraph,
    runtime: ServerRuntime,
    apiSecurity: ApiSecurity = ApiSecurity.authenticated(graph.auth, graph.authConfig),
) {
    val origins = PublicOrigin(graph.authConfig, graph.trustedProxies::trustsPeer)
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        })
    }
    installOpenTvSecurityHeaders(origins::secure)
    installOpenTvRequestBodyLimit()
    installOpenTvCompression()
    install(PartialContent)
    install(WebSockets)
    installOpenTvErrorResponses()
    monitor.subscribe(ApplicationStopped) { runtime.close() }
    routing {
        healthRoutes { graph.remux.available }
        api(graph, apiSecurity)
        webClient()
    }
}

/** Maps every domain failure to its `ApiErrorDto` contract, in one place. */
internal fun Application.installOpenTvErrorResponses() {
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
        exception<CsrfException> { call, _ ->
            call.respond(
                HttpStatusCode.Forbidden,
                ApiErrorDto("csrf_rejected", "The CSRF token was missing or stale"),
            )
        }
        // Almost always a deployment detail rather than an attack, and invisible from the
        // browser: log both sides so the operator can see which address to configure.
        exception<RejectedOriginException> { call, cause ->
            LoggerFactory.getLogger("opentv").warn(
                "Rejected origin {} on {}: it is neither the requested host {} nor OPENTV_PUBLIC_URL {}",
                cause.received ?: "(absent)",
                call.request.path(),
                call.request.headers[HttpHeaders.Host] ?: "(absent)",
                RequestOrigin.expected(cause.publicUrl),
            )
            call.respond(
                HttpStatusCode.Forbidden,
                ApiErrorDto(
                    "origin_rejected",
                    "This request's origin is not served by this OpenTV instance. " +
                        "Set OPENTV_PUBLIC_URL to the address browsers use.",
                ),
            )
        }
        exception<WebAuthnUnavailableException> { call, cause ->
            call.respond(
                HttpStatusCode.Conflict,
                ApiErrorDto(
                    "webauthn_unavailable",
                    "Passkeys need an HTTPS address with a hostname. This server was reached " +
                        "at ${cause.origin}.",
                ),
            )
        }
        exception<InvalidCredentialsException> { call, _ ->
            call.respond(
                HttpStatusCode.Unauthorized,
                ApiErrorDto("invalid_credentials", "Authentication failed"),
            )
        }
        exception<InvalidChallengeException> { call, _ ->
            call.respond(
                HttpStatusCode.Conflict,
                ApiErrorDto("challenge_invalid", "Authentication challenge expired or was already used"),
            )
        }
        exception<LastFactorException> { call, cause ->
            call.respond(
                HttpStatusCode.Conflict,
                ApiErrorDto("last_factor", cause.message ?: "The last authentication factor cannot be removed"),
            )
        }
        exception<LastAdminException> { call, cause ->
            call.respond(
                HttpStatusCode.Conflict,
                ApiErrorDto("last_admin", cause.message ?: "The final administrator cannot lose access"),
            )
        }
        exception<SelfLockoutForbiddenException> { call, cause ->
            call.respond(
                HttpStatusCode.Conflict,
                ApiErrorDto(
                    "self_lockout_forbidden",
                    cause.message ?: "Ask another administrator to make this change",
                    cause.field,
                ),
            )
        }
        exception<UsernameTakenException> { call, cause ->
            call.respond(
                HttpStatusCode.Conflict,
                ApiErrorDto("username_taken", cause.message ?: "Username is already in use"),
            )
        }
        exception<UnknownPlaylistException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ApiErrorDto("unknown_playlist", cause.message ?: "No such playlist"),
            )
        }
        exception<TotpExistsException> { call, cause ->
            call.respond(
                HttpStatusCode.Conflict,
                ApiErrorDto("totp_exists", cause.message ?: "A TOTP authenticator is already enrolled"),
            )
        }
        exception<PasswordCredentialRequiredException> { call, cause ->
            call.respond(
                HttpStatusCode.Conflict,
                ApiErrorDto(
                    "password_required_for_mfa",
                    cause.message ?: "Add a password before configuring multi-factor authentication",
                    "password",
                ),
            )
        }
        exception<PasswordAuthenticationDisabledException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ApiErrorDto(
                    "password_auth_disabled",
                    cause.message ?: "Password authentication is disabled",
                    "password",
                ),
            )
        }
        exception<LocalAccountProvisioningDisabledException> { call, cause ->
            call.respond(
                HttpStatusCode.Conflict,
                ApiErrorDto(
                    "local_account_provisioning_disabled",
                    cause.message ?: "Local account provisioning is disabled",
                ),
            )
        }
        exception<UserStatusNotSettableException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ApiErrorDto(
                    "user_status_not_settable",
                    cause.message ?: "This user status cannot be set by an administrator",
                    "status",
                ),
            )
        }
        exception<AuthRateLimitedException> { call, cause ->
            val retrySeconds = ((cause.retryAtMs - System.currentTimeMillis()).coerceAtLeast(0) + 999) / 1000
            call.response.headers.append(HttpHeaders.RetryAfter, retrySeconds.toString())
            call.respond(
                HttpStatusCode.TooManyRequests,
                ApiErrorDto("auth_rate_limited", "Authentication is temporarily rate limited"),
            )
        }
        exception<PlaybackRevokedException> { call, _ ->
            call.respond(
                HttpStatusCode.Gone,
                ApiErrorDto("playback_revoked", "Playback lease or media grant has expired"),
            )
        }
        exception<PayloadTooLargeException> { call, _ ->
            call.respond(
                HttpStatusCode.PayloadTooLarge,
                ApiErrorDto("request_too_large", "Request body exceeds the allowed size"),
            )
        }
        // These three echo a message that may have been built from a provider URL, and those
        // URLs carry the account's credentials. Mask before it reaches the browser.
        exception<IllegalArgumentException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ApiErrorDto("invalid_request", ProviderSecrets.redact(cause)),
            )
        }
        exception<XtreamAuthException> { call, cause ->
            call.respond(
                HttpStatusCode.BadGateway,
                ApiErrorDto("provider_login_rejected", ProviderSecrets.redact(cause)),
            )
        }
        exception<XtreamUnreachableException> { call, cause ->
            call.respond(
                HttpStatusCode.BadGateway,
                ApiErrorDto("provider_unreachable", ProviderSecrets.redact(cause)),
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
}

/**
 * The only responses worth compressing. Everything else - all media - is streamed for as long
 * as someone is watching, and no continuous stream survives being run through an encoder.
 */
private val COMPRESSIBLE_TYPES = arrayOf(
    ContentType.Text.Any,
    ContentType.Application.Json,
    ContentType.Application.JavaScript,
    ContentType.Image.SVG,
)

/** Whether a response of [type] would be compressed by [installOpenTvCompression]. */
internal fun isCompressible(type: ContentType): Boolean =
    COMPRESSIBLE_TYPES.any { type.match(it) }

internal fun Application.installOpenTvCompression() {
    install(Compression) {
        gzip()
        deflate()
        matchContentType(*COMPRESSIBLE_TYPES)
        minimumSize(1024)
    }
}

internal const val MAX_REQUEST_BODY_BYTES = 1_048_576L

internal fun Application.installOpenTvRequestBodyLimit() {
    install(RequestBodyLimit) {
        bodyLimit { call -> requestBodyLimit(call.request.path()) }
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
