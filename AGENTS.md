# OpenTV repository memory

This file records stable repository facts that are expensive to rediscover.
Source and build files remain authoritative when this summary becomes stale.

## Project shape

OpenTV is a modular monolith with two independent clients:

- `:app`: standalone Android/Android TV IPTV reader.
- `:server`: Kotlin/JVM Ktor server that embeds the React web client.
- `:server-contract`: Kotlin Multiplatform wire DTOs shared by the server and
  hub clients.
- `:core`: platform-neutral logic shared by Android and server.
- `:data`: Room implementation of `:core` storage ports for Android and JVM.
- `:server-data`: JVM server persistence; its merged database composes the
  shared `:data` catalog entities with server-only account/identity entities.
- `server/webapp`: React/TypeScript/Vite client for `:server`.

Android does not bundle the server. It includes `:core`, Android `:data`,
`:server-contract`, and `:hub-client`; it does not include `:server`,
`:server-data`, or JVM `:data`. Release builds enable R8 minification and
resource shrinking.

An OpenTV server is an optional Android source adapter. Existing local
M3U/Xtream behavior remains independent.

## Where things are

### Shared

- Server wire DTOs: `server-contract/src/commonMain/.../contract/`
- Domain models: `core/.../model/Models.kt`
- Storage ports: `core/.../storage/Storage.kt`
- Repositories/use cases: `core/.../repo/`
- M3U/XMLTV/Xtream/catch-up logic: corresponding packages under `core/.../`
- Room database/DAOs: `data/.../db/OpenTvDatabase.kt`, `Daos.kt`
- Catalog DAO composition seam: `data/.../db/CatalogDaos.kt`
- Room adapter: `data/.../RoomStorage.kt`
- Exported Room schemas: `data/schemas/`

### Server

- Merged Room database/factory: `server-data/.../db/OpenTvServerDatabase.kt`,
  `OpenTvServerStorage.jvm.kt`
- Process entry point: `server/.../Main.kt`
- Environment configuration: `ServerConfig.kt`
- Composition and lifecycle: `Application.kt`, `ServerGraph.kt`
- API root: `ApiRoutes.kt` at `/api/v1`
- Feature adapters: `PlaylistRoutes.kt`, `LibraryRoutes.kt`,
  `DownloadRoutes.kt`, `SessionRoutes.kt`, `MediaRoutes.kt`
- Feature use cases: matching `*ApplicationService.kt`
- Domain-to-wire mapping helpers: `ResourceDtos.kt`
- Authentication seam: `ApiSecurity.kt`
- Media runtime: `Remux.kt`, `LiveRelay.kt`, `StreamGate.kt`,
  `SharedHlsCache.kt`, `MediaProcessRunner.kt`
- Remux collaborators: `RemuxSession.kt` (session model), `RemuxCommand.kt`
  (ffmpeg pipeline), `RemuxPlaylists.kt` (HLS documents), `RemuxSubtitles.kt`
  (WebVTT cue store), `MediaProbe.kt` (ffprobe)
- Codec/labelling policy shared by every media path: `MediaCodecs.kt`,
  `MediaTrackLabels.kt`, `FfmpegSupport.kt`

### Web

- Composition/routes: `server/webapp/src/App.tsx`
- Typed API facade and TS contracts: `src/api.ts`
- HTTP/errors/auth transport: `src/api/http.ts`
- Failure copy and the report surface: `src/errors.ts`
- Shared playlist catalog and playlist-route guard: `src/library.tsx`
- Browser-only preferences: `src/preferences.ts`
- Shared async/download state: `src/hooks.ts`
- Lightweight player navigation: `src/player/PlayerNavigation.tsx`
- Playback composition (owns the lease, wires the rest):
  `src/player/PlayerProvider.tsx`
- Playback runtime parts: `usePlaybackEngine.ts` (engine choice and wiring),
  `useRemuxSession.ts` (remux lifecycle), `useMediaElement.ts` (element state,
  native tracks, cues), `playbackStatus.ts` (what the chrome shows),
  `PlayerChrome.tsx` (overlays and control bars),
  `usePlayerShortcuts.ts` (keyboard and media keys)

All screens are lazy route boundaries. Player runtime code is intentionally
absent from the initial bundle.

### Android

- Composition root: `app/.../OpenTvApp.kt` (`AppGraph`)
- Navigation: `MainActivity.kt`
- Screens and ViewModels: `app/.../ui/`
- Player coordinator: `ui/player/PlayerScreen.kt`
- Player data: `PlayerViewModel.kt`
- ExoPlayer lifecycle: `PlayerSession.kt`
- PiP/window/lifecycle effects: `PlayerSystemEffects.kt`
- Player presentation: `PlayerControls.kt`, `PlayerSheets.kt`
- Download application API: `download/DownloadRepository.kt`
- WorkManager boundary: `DownloadScheduler.kt`,
  `DownloadWorkerFactory.kt`, `DownloadWorker.kt`

## Current contracts and decisions

- `/api/v1` is the only API prefix; there is no legacy `/api` alias.
- `GET /api/v1/server-info` is public discovery metadata and returns only
  `product="opentv"`, `apiVersion=1`, and the running server version. The server
  JAR's `Implementation-Version` comes from `-PopentvVersion`, then
  `OPENTV_VERSION`, and is `dev` when neither build input is set.
- There is no OpenAPI document. OpenAPI is only wanted if generated and
  validated from executable routes/DTOs.
- `ApiSecurity.openAccess()` is a test-only adapter; production composition uses
  `ApiSecurity.authenticated()`, so the API authenticates itself. Every client sends
  the same opaque session as `Authorization: Bearer`; session cookies, CSRF tokens,
  and auth-path Origin checks do not exist. `ApiSecurityTest`, `NativeAuthFlowTest`.
- The bundled web client keeps the bearer token in localStorage. Successful JSON auth
  flows return it as `AuthFlowDto.sessionToken`; OIDC returns it in `/#session=...`,
  alongside the browser-generated `handoff` correlation stored for that flow in
  sessionStorage. The client accepts a recent exact match, consumes both fragment values,
  and clears the URL before loading the current user. The CSP allows scripts only from
  this origin and forbids script attributes as the compensating control for a
  script-readable token. There is no compatibility transport for former cookie sessions.
- Android stores a hub's bearer only in `HubSessionVault`: AES-256-GCM under a
  non-exportable Android Keystore key. The token never enters Room.
- Browser WebSockets cannot set `Authorization`, so `/playback/{id}/ws-token` mints a
  signed 30-second capability bound to the auth session and playback lease. The client
  puts only that capability in the `ws_token` query parameter; the route-scoped
  authenticator strips and validates it before the upgrade. The long-lived bearer is
  never put in a URL.
- `X-OpenTV-Client: native` tags sessions created by public auth flows as
  `ClientKind.NATIVE`; absent or other values mean `BROWSER`. It is descriptive admin
  metadata with no authorization meaning. Device-link sessions remain
  `LINKED_DEVICE`.
- Error responses are mapped in one place: `installOpenTvErrorResponses`.
- `OPENTV_PUBLIC_URL` is optional. `PublicOrigin` answers the four questions that need an
  absolute address - OIDC callback, device-link QR, WebAuthn relying party, OIDC
  transaction-cookie `Secure`/HSTS - from the configuration when it is pinned (`AuthConfig.publicUrlPinned`,
  `webAuthnPinned`) and from the request when it is not. Forwarded scheme and host are read
  only through the `trustsPeer` predicate, i.e. `OPENTV_TRUSTED_PROXIES`. A pinned address
  is never second-guessed: the OIDC callback is registered at the provider and a passkey
  belongs to one relying party. `PublicOriginTest`.
- A ceremony's relying party travels in its challenge payload; verification never recomputes
  it from the completing request. On an address WebAuthn cannot use (bare IP, plain HTTP off
  localhost) `capabilities` stops advertising passkeys and a ceremony is refused with
  `409 webauthn_unavailable`, rather than failing inside the browser.
- The OIDC redirect URI travels in the flow's challenge payload, because the token exchange
  must repeat the value the authorization request used.
- Authentication is installed once as a route-scoped plugin. Feature handlers
  contain no credential policy.
- WebAuthn is both a second factor and a primary one. `/auth/webauthn/login/*`
  starts from a `userId`-less challenge, sends an empty `allowCredentials`,
  verifies with user verification required, and issues an `AuthMethod.WEBAUTHN`
  session. The second-factor path deliberately keeps `userVerificationRequired
  = false`; `WebAuthnServiceTest` pins both. The algorithms advertised in
  `pubKeyCredParams` and the ones accepted at verification come from one shared
  list - passing an empty list to `RegistrationParameters` rejects every
  registration.
- QR device linking reuses `auth_challenges` (`ChallengeKind.DEVICE_LINK`); it
  needed no table. The QR carries a token in the URL fragment, so it never
  reaches a log or a `Referer`; `link/lookup` claims the request for the
  scanning user and reveals the account to the waiting device, `link/approve`
  requires that same user, and `link/poll` mints the session. Linked sessions
  are `ClientKind.LINKED_DEVICE` and inherit the approver's auth method. There
  is no typed fallback code: scanning is the only path.
- Listings are paged by the server, not by the browser. Channel, series and episode
  endpoints take `offset`/`limit` (validated at the route, 1..MAX) and answer with a page
  object plus a total; `useServerPaged` drives them. Listing DTOs are projections - they
  carry no provider URLs or detail-only fields.
- Search is indexed, never a scan: a normalized `searchName` prefix B-tree, plus FTS5
  `unicode61` (word prefixes) and trigram (mid-word, three characters or more) sidecars
  maintained by triggers. Ranking is title-prefix, then word boundary, then mid-word, and
  every section is capped server-side. Room 2.x cannot export FTS5 entities, so that DDL
  lives in a database creation/open callback and is proven by `OpenTvDatabaseSchemaTest`
  rather than by the exported JSON. The catalog is rewritten wholesale on refresh, so
  index cost is paid on every refresh.
- The image proxy caches: bounded memory tier, disk tier for large posters, LRU eviction,
  single-flight so concurrent grids fetch a poster once, and upstream validator
  revalidation. The disk tier is optional - a filesystem that refuses it degrades the tier,
  never the server's ability to start. Image elements authenticate with the signed,
  expiring image capability minted only into an entitled listing; no media element depends
  on the bearer header.
- Remux fragments are served with `LocalFileContent`, so ranges work and a fragment never
  lands in heap. One `media_start` debug record per playback start carries the stage
  timings (connection limit, ffprobe, preparation, launch, init, first fragment), so
  startup latency is measurable in production instead of argued about.
- A catalog refresh deletes and re-inserts channel rows, so every numeric channel id is a
  refresh-generation value. Browser links, and anything that must outlive a refresh, use the
  stable `contentId`; `/content/{contentId}` serves the same three reads as `/channels/{id}`,
  which stays only for links made before this. `ContentIdentityServiceTest` proves a content
  link survives the renumbering.
- Nothing on the path to the first frame waits on the provider's account API.
  `ProviderConnectionLimits` bounds it, reuses the last known limit and backs off from a slow
  panel; `AccountRepository` locks per playlist and answers with stale data rather than
  queueing behind an in-flight request. The same rule applies to the panel EPG in
  `XtreamRepository.guideFor`, which falls back to stored XMLTV.
- A solo browser live-HLS player listens for HLS.js's parsed audio SourceBuffer codec.
  When Chromium's Media Source API rejects that exact container/codec pair, it changes
  to the lease-scoped `/transcode` transport: ffmpeg copies video and normalizes only
  the unsupported audio to AAC. Missing codec metadata does not trigger work, and a
  shared-HLS room never opens a private rescue connection for one member.
- `MediaProbe.inspect` is single-flighted: a remote probe costs a provider connection on the
  path to playback, so two viewers of one title share the result. It probes with bounded
  `-analyzeduration`/`-probesize` first and re-probes unbounded only when that looks short.
- Playback codec support is a lease property, reported as normalized ffprobe video/audio names
  on `POST /playback`; a missing or empty report means the exact browser baseline. Unknown,
  overlong and excess names are discarded. There is one negotiation path and no `hevc` remux
  query parameter.
- `ClientCapabilitiesDto.selectsTracksInBand` is false by default. Android reports true because
  ExoPlayer selects muxed audio and subtitle tracks itself. The room intersection ANDs the flag,
  and the media-capability fingerprint includes it. An all-in-band room can direct-play only when
  every audio stream and the video stream are decodable; a browser or mixed room retains the
  first-audio/single-audio/no-subtitles direct-play restriction.
- A watch-together room serves one media format, so its effective capabilities are the
  intersection of every member's stored lease report. An intersection change on join or leave
  uses the existing `room-audio` reload/ready/`room-go` barrier to move every member to the new
  shared read together.
- Fully capable in-band clients can direct-play multi-audio or subtitled VOD without a
  room remux. Raw-TS live rooms use `LiveRelay` and one upstream read. Playlist-only
  `.m3u8` rooms use `SharedHlsCache`: manifests and media resources are single-flighted
  and bounded per share group, then each viewer receives a lease-specific rewritten
  manifest. Both live paths replace per-viewer provider reads with one room-owned
  provider-budget seat.
- Every server-emitted playback command carries a positive, per-lease monotonic `sequence`.
  Browser and Android clients keep a high-water mark and ignore missing, duplicate, or older
  commands, which orders WebSocket delivery against a delayed HTTP-heartbeat fallback without
  turning the queue into a reliable-delivery protocol.
- Two leases carrying the same auth-session id are the same authenticated client for
  watch-together discovery and duplicate-content admission. A browser reload may leave its old
  lease alive until the best-effort unload or the reaper runs; it must not offer that page as a
  peer or force the replacement page to co-watch with itself. A different auth session remains
  another device even when it belongs to the same account.
- Each room reload barrier has its own positive, monotonically increasing `generation`.
  `room-audio` and `room-go` carry it, `/playback/{id}/ready` requires
  `ReadyBody(generation)`, and stale/missing generations are ignored by the barrier rather than
  being treated as a wildcard. Repeated current-generation `ready` and repeated `leave` are
  harmless. Android retries ready at most three times (immediately, then after 500 ms and 1 s),
  all inside the existing 12-second client fail-open window.
- Kicking removes the viewer from the room/shared read immediately, queues and wakes delivery of
  `room-ended`, then a server-owned timer terminates the lease after a fixed 750 ms notice grace.
  Draining the notice does not cancel or extend the timer, so a stalled or adversarial client
  cannot skip revocation; runtime shutdown also removes every lease.
- Remux artifacts are keyed by a short stable fingerprint of the effective codec sets in
  addition to source, audio track and share group. Remux, live relay and audio-transcode paths
  all make copy-versus-transcode decisions from that effective set; unlike capability sets
  cannot accidentally share a prepared media pipeline.
- Nothing is decodable until one HLS fragment closes, so the segment target is the floor on
  time-to-first-frame; copied video uses the same 3s target as transcoded video and can still
  only cut on a source keyframe.
- User status is a lifecycle, not a free-form field. `INVITED` is set by creating or
  resetting an account and cleared by activation; `PENDING` is legacy and unreachable, kept
  only so an existing row still reads. An administrator may set `ACTIVE` or `DISABLED` and
  nothing else - the server enforces it (`user_status_not_settable`) and publishes the set
  as `AdminUserDto.settableStatuses`, which the admin UI follows rather than hardcoding.
- An administrator cannot demote, disable or delete their own account, however many admins
  exist: `409 self_lockout_forbidden` with `field` naming role, status or account. Another
  administrator is the escape hatch, so the rule costs nothing operationally, and it sits
  next to the last-admin guard rather than in a separate conditional. The admin UI simply
  does not offer those three on your own row.
- What the account-security screen offers follows the *account's* credentials, not the
  session's method: `CurrentUserDto.hasPassword`. An authenticator is only ever asked for
  during password sign-in and recovery codes only recover that step, so both are hidden and
  server-refused (`409 password_required_for_mfa`) without a password. Passkeys stay: they
  are also a primary sign-in method, so they are worth having on an SSO-only account.
- Local account provisioning needs local passwords. Activation sets a password, so with
  `OPENTV_PASSWORD_AUTH_ENABLED=false` both creating a user and resetting credentials are
  refused before any mutation (`409 local_account_provisioning_disabled`) - reset used to
  delete the password and MFA and then hand out a link that could never be redeemed. User
  records still exist in that mode (OIDC and passkey accounts use them), which is why this
  is "provisioning disabled" rather than "no local accounts". The admin UI removes both
  actions and says why; `admin/localAccounts.ts` is the single client-side answer.
- An administrator creates an account either with a password (immediately `ACTIVE`, no
  activation token) or without one (`INVITED` plus a one-time link).
  `CreatedUserDto.activationToken` is null in the first case, and a password is refused when
  password authentication is off (`password_auth_disabled`).
- Administration screens show content by name, never by `contentId`: admin resume rows carry
  a resolved `title`, batched through one identity lookup and one `getMany`, and null when
  the catalog no longer holds it.
- API failures use `ApiErrorDto(code, message, field)`.
- API wire declarations shared with hub clients live in `:server-contract`; Ktor,
  server-domain types, validation, domain-to-DTO mapping, the health response, and
  private persistence/challenge payloads stay in `:server`.
- Server DTOs are separate from `:core` models.
- Kotlin DTO and TypeScript contract changes must remain synchronized.
- Provider-controlled numeric identities are decimal strings on every wire:
  `xtreamStreamId`, Xtream `seriesId`/`xtreamSeriesId`, and metadata `sourceId`.
  The current core/Room catalog remains `Long`-backed and accepts only canonical
  positive decimal ids that round-trip through `Long`. Xtream rows with a
  nonnumeric, noncanonical, non-positive, or oversized id are skipped at
  ingestion; invalid optional TVMaze `sourceId` is stored as null while the
  remaining metadata is kept. The Xtream-series route rejects the same invalid
  forms with a typed 400 instead of normalizing them into another identity.
- Provider URLs and credentials held by the server never appear in response contracts.
- Browser playlist credentials are write-only. There is no credential-read
  endpoint. Blank secret fields on update preserve existing values.
- Browser playback URLs are opaque `StreamCipher` capabilities. A stream token seals the
  playback lease it was minted for, so URLs the proxy derives while rewriting a lease's HLS
  manifest are usable by that lease alone. Media routes take the lease from the token, not
  from the request.
- `/stream`, `/shared-hls`, `/relay`, `/transcode`, `/remux/*`, and `/img` live
  outside the bearer boundary because native media and image elements cannot add headers.
  Stream/remux routes validate the encrypted source plus the rotating grant bound to its live
  lease and derive user/session identity from that lease. Download-file snapshots, including
  readable prefixes of a running blob, similarly use a signed, expiring,
  owner-session-and-download-bound `StreamCipher` capability and remain range-capable.
- Android hub downloads pipeline the provider fetch and device pull.
  `HubDownloadCoordinator` hands off as soon as the server's growing blob has usable bytes;
  each signed file response is a fixed snapshot, and the authenticated download DTO remains
  authoritative for status and expected size. Android marks the local row done only when the
  DTO is `DONE` and both byte counts match. The pull refreshes an expired or malformed
  short-lived capability through the authenticated hub API after a 401 and bypasses the
  provider-connection gate. A download-file capability seals the minting auth-session id as
  well as its owner and download id; every file request reuses persistent session
  authentication to check that session and user are still live. A decrypted capability whose
  session ended returns 410 `download_access_revoked`, which Android maps directly to terminal
  `HUB_GONE` without attempting a re-mint loop. The file route remains outside bearer auth.
- Revoking one auth session does not change the user's persistent download reference or shared
  server fetch, so another device's independently minted capability keeps working. Revoking
  every session suspends that user's active download references; an in-flight blob is parked
  only when no other active user reference remains, and re-enqueueing later resumes it. Deleting
  a user removes its references through the database cascade and orphan cleanup, but a blob
  referenced by another user is neither cancelled nor deleted.
- The HLS rewriter mints capabilities only for children on the manifest's own origin;
  playlists are provider-controlled input and must not be able to aim the server's HTTP
  client at another host.
- HLS live rooms use the explicit lease `sharedHlsUrl`, never the raw-TS relay. The server
  caches untouched upstream resources by room share group, URL, and Range: concurrent reads
  are single-flighted, playlists live for 1 second, and each room keeps at most 24 media
  resources/32 total entries/64 MiB (256 MiB globally), with 32 MiB per resource and
  30-second idle eviction. The existing `StreamProxy.rewriteHls` remains the only playlist
  rewriter and mints lease-specific `/shared-hls` child capabilities. Extensionless playlists
  are recognized from their `#EXTM3U` prefix, so raw provider URLs never enter a served
  manifest even when a panel labels one as generic binary data. A room owns one `StreamGate`
  seat under its share-group id;
  last-member cleanup closes readers, evicts bytes, and releases that id immediately.
- `api/http.ts` installs one localStorage-backed bearer provider for every HTTP request;
  it sends no ambient credentials and has no CSRF seam.
- Browser preferences and server settings are intentionally separate.
- Android catalog gateways are cached in a thread-safe, access-order LRU capped at 64.
  Gateway lookup/construction never reads Room: `CatalogGateway.traits()` is suspending, and
  a local gateway resolves its playlist-backed traits only after the ViewModel has entered a
  coroutine. The gateway-cache monitor is never held across suspension.
- Conditional playlist/EPG bodies are consumed through suspending `TextBody.readLines` /
  `readChars` callbacks. Platform adapters run the callback on their blocking-I/O dispatcher,
  close or cancel the underlying HTTP exchange when the coroutine is cancelled, and expose
  only a streaming `Sequence` / `TextSource`; neither Android nor server buffers the full file.
- Android hub re-authentication routes carry the existing hub row id. A successful flow replaces
  that row's vaulted token without inserting or changing its address, clears identity cached for
  the old session, and refreshes `/auth/me`. Signing into a different account on the same hub is
  allowed and replaces the cached identity; if the row was removed before the flow starts, the
  screen falls back to the ordinary add flow.
- A current-generation 401 emits one buffered, hub-id-only reauthentication request from
  `HubRegistry`; `AppShell` navigates directly to that hub's sign-in route. Concurrent failures
  on the same expired credential cannot create duplicate navigation, while replacing the token
  starts a new generation which may request sign-in again. The shell checks current health before
  consuming a buffered event, so an already completed reauthentication cannot reopen login.
  Playlist discovery must not relabel this state as an unreachable server.
- Android keeps `allowBackup=false` plus matching `backup_rules.xml` exclusions for
  pre-31 devices and uses `data_extraction_rules.xml` on Android 12+: cloud backup and
  device transfer exclude `opentv.db` plus its journal/WAL sidecars,
  `hub_sessions.xml`, and the `player_prefs.preferences_pb` DataStore.
- `HubBrowserHandoff` opens same-origin account/admin pages in a Custom Tab and falls
  back to a QR code on televisions or devices without a browser. Keep the HTTP and HTTPS
  `ACTION_VIEW` declarations in the manifest's `<queries>` block: Android 11+ package
  visibility otherwise makes the browser probe lie.
- The browser-sign-in return is the fixed `opentv://sign-in` signal and carries no query,
  fragment, token, account identity or other secret: any app can claim a custom scheme.
  It may wake the Android wait early, but the in-memory poll token and server poll remain
  authoritative; losing or intercepting the return may change latency only.
- `DownloadStateIcon` is the single contextual `POST_NOTIFICATIONS` request seam for every
  download source, including hub downloads. It asks only on Android 13+ and always invokes the
  enqueue callback immediately, so denial hides progress notification UI but never blocks work.
  The Downloads screen reuses the same seam when Resume or Retry reschedules a worker.
- `DownloadWorker` must successfully enter foreground execution before transferring any bytes.
  Later notification-content refresh failures may be logged and ignored because the worker is
  already foreground; a true initial promotion failure follows the ordinary retry/failure path.
- Playlist-dependent web routes are guarded by `LibraryProvider`; keep empty,
  missing, and failed-library states out of feature-screen loading spinners.
- `PlaylistRepository.refresh` reports whether it actually rewrote the catalog;
  reconciliation runs only when it did. Android ignores the result.
- The playback lease has exactly one owner (`PlayerProvider`). The session
  reporter never ends it, or a StrictMode remount deletes the lease it is about
  to heartbeat against.
- The web client uses Vitest; `npm test` and `npm run build` are its focused
  test and typecheck/bundle validation.

## Runtime ownership

- `ServerRuntime` owns and closes long-lived server components.
- Feature routes call `*ApplicationService`; they do not directly own Room,
  provider, or process policy.
- `MediaRoutes` owns streaming transport only; remux/relay/gating policy lives
  in the media services.
- Live playback, remuxing, and downloads share one provider-connection budget.
  Interactive streams may evict downloads, never another viewer.
- Android workers receive dependencies through `DownloadWorkerFactory`.
- Android repositories use `DownloadScheduler`, not WorkManager statics.
- `HubDownloadCoordinator` lives with the Android download application layer because it
  owns the persisted local row and the handoff between preparation and `DownloadWorker`;
  hub HTTP calls remain behind its registry-backed adapter.
- Android composables consume ViewModel state; direct graph access is confined
  to composition/ViewModel-factory boundaries.
- `PlayerSession` owns ExoPlayer, listeners, polling, progress persistence, and
  cleanup.
- Hub direct playback uses ExoPlayer's in-band track picker. A hub remux exposes the server's
  audio-track list instead; selecting one re-requests the remux and replaces the media item at
  the captured playback position. Local M3U/Xtream playback never owns a hub controller.
- Android watch-together policy lives in `WatchTogetherCoordinator`, not in Compose or
  `PlayerSession`. `room-state` replaces its roster, only a roster controller emits sync, and
  the ExoPlayer event seam suppresses echoes of applied server/admin commands. It mirrors the
  web thresholds: 750 ms for an explicit seek and 4 s for a periodic drift anchor.
- Android enters the `room-audio` reload/ready/`room-go` barrier only while its hub playback is
  remuxed or while a direct live player changes between its solo and room source. HLS changes to
  the lease's shared-HLS URL and remains direct ExoPlayer playback; TS changes to the relay. A
  direct player whose in-band source is already correct acknowledges that server generation
  without a reload; a remux/shared-source transition restores the captured position, stays
  paused after media readiness, and resumes only on the matching `room-go` (with the same bounded
  fail-open as the web client).

## Persistence and identity

- Android uses `OpenTvDatabase` version 12 in `opentv.db`, containing only the shared
  catalog/Android-local entity set. The server uses `OpenTvServerDatabase` version 1 in one
  `opentv.db`, containing that catalog entity set plus every server-only account,
  credential, session, grant, content-identity, activity and download entity. `RoomStorage`
  depends on `CatalogDaos`, so both databases expose the same catalog ports without putting
  `:server-data` or its entities on Android's dependency graph.
- Both database builders currently use
  `fallbackToDestructiveMigration(dropAllTables = true)`. On Android a schema mismatch wipes
  local playlists, connected hubs, favorites, resume points, downloads and the catalog. On
  the server it wipes the entire deployment database, including accounts, credentials,
  sessions and grants as well as playlists/catalog data. This is acceptable only while no
  one depends on persisted data: real Room migrations must replace the fallback before that
  changes. Downloaded files may remain on disk without their Room records.
- Schema export remains mandatory even during the destructive phase:
  `data/schemas/...OpenTvDatabase/12.json` and
  `server-data/schemas/...OpenTvServerDatabase/1.json` are the migration baselines and must
  stay committed. The exported JSON is not the complete SQLite file: FTS5 sidecars and their
  triggers are installed by `SEARCH_INDEX_CALLBACK` and do not appear in it. A future
  migration that changes search tables or indexed columns must recreate/alter those objects
  explicitly. `OpenTvDatabaseSchemaTest` and `OpenTvServerDatabaseSchemaTest` pin these
  policies; `ServerUserDatabaseTest` proves fresh-server indexed search.
- On the server, the catalog and the accounts now share ONE SQLite writer, so a long catalog
  write is an availability problem for authentication and playback, not just slow. Playback
  leases heartbeat every 3s and are reaped at 12s, so a writer held past that window kills
  the playback of users who have nothing to do with the playlist being written. Two measured
  cases and their remedies, both of which are easy to reintroduce:
  - Row-by-row FTS5 trigger maintenance during a bulk catalog replace blocked
    `sessions().touch()` for 3.2s at 20k channels and blew Room's 30s writer-pool timeout at
    120k. Bulk catalog mutations must drop the FTS triggers inside the transaction, mutate
    set-wise, rebuild the indexes, and restore the triggers (`RoomStorage`, `Transactions`).
  - Identity reconciliation's row-by-row `updateAll` blocked it for 26s at 120k rows.
    Insert/rebind now commits in 2,000-row chunks so other writers interleave. Reader-visible
    atomicity comes from `CatalogGate` holding across all chunks, NOT from one transaction;
    startup repair covers a crash mid-chunk. Retirement stays single-transaction because it
    is set-wise and measured 148ms at 120k.
  Any new bulk write over catalog or identity rows must be measured against a concurrent
  `sessions().touch()`, not just for throughput. `MergedDatabaseContentionTest` is the harness.
- Server playlist grants/defaults/content identities have real foreign keys to the catalog:
  playlist deletion cascades all three, and channel deletion sets an identity's current
  channel pointer to null. Room enables `PRAGMA foreign_keys`; integration tests prove this
  by rejecting orphan grants and exercising the cascades.
- `playlist_deletions` remains because deleting a playlist also terminates live runtime state
  and removes downloaded files, neither of which is transactional SQLite work. Its tombstone
  makes that external cleanup restartable and blocks new admission while deletion runs. After
  file cleanup, `deleteCatalogPlaylist` removes the catalog slice and cascade-bound server
  state in one writer transaction. The startup path resumes only real tombstones; the former
  scan/purge for cross-file orphan grants and identities is gone.
- `auth_sessions.csrfToken` remains only as an unused schema column so the bearer-only
  transport does not require a database migration; it is never exposed or validated.
- There is no audit/security-event log. Reintroducing one means designing its reader and
  retention first.
- Android local-playlist favorites, resume points, and downloads use existing URL/key
  identities; hub-backed state uses server `contentId`. A future stable identity
  migration for local content must update all three together rather than piecemeal.
- `ContentIdentityService` has two paths and they are not interchangeable.
  Reconciliation owns `lastSeenAtMs` and retirement and may scan a playlist;
  browsing resolves by fingerprint in batches, creates what is missing, rebinds a
  stable identity when a partial catalog refresh gave its item a new channel row id,
  and never writes `lastSeenAtMs`. A read path that scans is a bug.

## Build facts

- Server target/runtime: JDK 25.
- Android/shared target: JVM 17 compatibility.
- Android: compile/target SDK 37, min SDK 26.
- Web/docs build target: Node.js 24.18 LTS or newer.
- Generated web output: `server/src/main/resources/web/` (ignored).
- Generated APKs, Gradle output, `node_modules`, `local.properties`, and server
  runtime data are ignored and must not be committed.
- Gradle normally builds the web client during server resource processing.
  `-PwebappPrebuilt` skips that step when web output was already built or is
  irrelevant to focused server tests.

Focused validation:

```bash
./gradlew :core:jvmTest
./gradlew -PwebappPrebuilt :server:test
cd server/webapp && npm run build
./gradlew testDebugUnitTest assembleDebug :app:lintDebug
```

Cross-layer validation without rebuilding web twice:

```bash
cd server/webapp
npm ci --no-audit --no-fund
npm run build
cd ../..
./gradlew -PwebappPrebuilt \
  :core:jvmTest :data:compileKotlinJvm :server:test :server:installDist
./gradlew testDebugUnitTest assembleDebug :app:lintDebug
```

## Useful executable references

- Route layering: `RouteLayeringTest`
- Write-only credentials: `PlaylistUpdateSecurityTest`
- Provider connection budget: `ProviderConnectionsTest`
- Playback session lifecycle: `PlaybackSessionRegistryTest`
- Playback capability boundary and HLS rewriting: `MediaCapabilityTest`
- Content identity reads vs reconciliation: `ContentIdentityServiceTest`
- Account-security step-up window: `AuthServiceTest`
- QR device linking: `DeviceLinkServiceTest`
- Security and caching headers, compression allowlist: `WebHeadersTest`
- API prefix terminates before the SPA fallback: `ApiNotFoundTest`
- Refresh gates reconciliation: `PlaylistRefreshTest`
- Typed administration failures: `UserAdministrationErrorTest`
- Browser transport contract: `webapp/src/api/http.test.ts`
- Browser failure copy and reporting: `webapp/src/errors.test.ts`
- Bearer, WebSocket-capability, and public-route boundary: `ApiSecurityTest`,
  `NativeAuthFlowTest`, `RouteLayeringTest`
- Android player orchestration: `PlayerViewModelTest`
- Android player policies: `PlayerPolicyTest`
- Watch-together generations, sequencing, retry bounds, and kick grace:
  `PlaybackSessionRegistryTest`, `WatchTogetherCoordinatorTest`,
  `webapp/src/player/sessionProtocol.test.ts`

## Invariants

- No Ktor/Android/Room/server DTO types in `:core`.
- No provider credentials or raw provider URLs in browser response contracts.
  Playlist create/update requests carry write-only provider inputs by necessity.
- No unmanaged long-lived scopes, processes, or threads.
- Compression is an allowlist of text content types. Media is streamed, and Ktor
  compresses a streaming body by buffering all of it; a new streaming route must not
  have to remember to opt out.
- Playback status codes: a lease that is gone is 410 and nothing else. 404 means many
  ordinary things on these routes (no extra tracks to expose, an unknown segment), so
  no client may infer "stop playing" from it.
- Android and server Room databases currently use destructive fallback; this must become
  real migrations before persisted deployment data matters.
- No hand-maintained OpenAPI file.
- Security headers and the CSP are configured once at composition in
  `Application.kt`; caching headers are bound to the static-content route so they
  can never reach a media route. `index.html` is `no-cache`, hashed assets are
  `immutable`.
- `/api/v1` terminates in a JSON 404. A tailcard SPA route must never be able to
  answer an API path with `index.html`, which the browser client cannot parse.
- Browser transport failures are `ApiError(status 0, code network/timeout/aborted)`.
  A transport failure is not an authorization outcome: it must not clear a
  session, and 401/403 listeners must not fire for it.
- Human-delivered secrets in a URL (activation, password reset, device link) travel
  in the fragment, never the query string: the query reaches the server's and every
  proxy's access log. Query credentials are purpose-limited media, image, download-file,
  and 30-second WebSocket capabilities; a long-lived session bearer never travels there.
- Failed async state is rendered by `asyncFallback`/`LoadFailed`, never as an
  endless spinner. `useAsync` exposes `error` and `reload` for exactly this.
- `src/errors.ts` turns a failure into words and puts it on screen; screens do not
  format `error.message` themselves. `errorMessage` maps server and transport codes
  to localized copy, per-screen overrides sharpen a code, and `GENERIC` replaces the
  last-resort line. `reportError` raises the error toast, `reportSuccess` the
  confirmation. A form explains itself inline through `ErrorNotice` (one `role=alert`
  per failure, never both surfaces); everything else toasts. A cancelled prompt is
  not a failure and is reported to nobody. `errors.test.ts`.
- `toast(message, { tone })` is the only transient surface: `error` and `success`
  carry a coloured left rail, an icon and their own live-region politeness, so the
  tone never rests on colour alone. There is no `snackbar` any more.
- A first-run server does not commandeer routing. The sign-in screen offers
  "Create the first administrator" while `bootstrapRequired` holds, so single
  sign-on stays reachable; `/setup` says why it is closed instead of redirecting.
- `Dialog` and `Sheet` are modal: role, `aria-modal`, labelling, Escape, focus
  trap and focus restore come from `useModalFocus`. Anything the user must
  acknowledge (recovery codes) uses `dismissible={false}`.
- Every content action is a real control: this UI is driven by keyboards and TV
  remotes as well as touch. The modal scrim is the only pointer-only clickable `div`;
  dialogs also provide Escape and explicit controls.
- `IconName` is a closed union; a name that is not a glyph is a compile error
  rather than an invisible button.
- Do not recreate the deleted server `Routes.kt` or Android player god class.
- `PlayerProvider.tsx` composes the player; engine, remux and chrome policy stay
  in their own modules. `RemuxService` owns session lifetime and HTTP only.
