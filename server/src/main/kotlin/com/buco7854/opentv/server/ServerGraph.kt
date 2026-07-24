package com.buco7854.opentv.server

import com.buco7854.opentv.core.repo.AccountRepository
import com.buco7854.opentv.core.repo.EpgRepository
import com.buco7854.opentv.core.repo.MetadataRepository
import com.buco7854.opentv.core.repo.PlaylistRepository
import com.buco7854.opentv.core.repo.XtreamRepository
import com.buco7854.opentv.core.storage.Storage

/** Server composition: shared repositories plus web-only adapters and orchestrators. */
class ServerGraph(
    val apiServices: ApiServices,
    val mediaApi: MediaRouteDependencies,
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
    val streamGate: StreamGate,
    val liveRelay: LiveRelay,
    val trustedProxies: TrustedProxies,
    /** Concurrent reads the provider behind a source URL permits. */
    val connectionLimit: suspend (String) -> Int,
)

data class ApiServices(
    val playlists: PlaylistApplicationService,
    val library: LibraryApplicationService,
    val downloads: DownloadApplicationService,
    val sessions: SessionApplicationService,
)

/** Narrow dependency bundle for the streaming transport, which must write Ktor responses. */
data class MediaRouteDependencies(
    val proxy: StreamProxy,
    val cipher: StreamCipher,
    val downloads: DownloadManager,
    val sessions: PlaybackSessionRegistry,
    val streamGate: StreamGate,
    val liveRelay: LiveRelay,
    val transcoder: AudioTranscoder,
    val remux: RemuxService,
    val connectionLimit: suspend (String) -> Int,
)
