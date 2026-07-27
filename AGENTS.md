# OpenTV repository memory

This file records stable repository facts that are expensive to rediscover.
Source and build files remain authoritative when this summary becomes stale.

## Project shape

OpenTV is a modular monolith with two independent clients:

- `:app`: standalone Android/Android TV IPTV reader.
- `:server`: Kotlin/JVM Ktor server that embeds the React web client.
- `:core`: platform-neutral logic shared by Android and server.
- `:data`: Room implementation of `:core` storage ports for Android and JVM.
- `server/webapp`: React/TypeScript/Vite client for `:server`.

Android does not use or bundle the server. It includes `:core` and Android
`:data`; it does not include `:server` or JVM `:data`. R8 and resource shrinking
are currently disabled.

Future Android support for an OpenTV server is expected to be an optional source
adapter. Existing local M3U/Xtream behavior must remain independent.

## Where things are

### Shared

- Domain models: `core/.../model/Models.kt`
- Storage ports: `core/.../storage/Storage.kt`
- Repositories/use cases: `core/.../repo/`
- M3U/XMLTV/Xtream/catch-up logic: corresponding packages under `core/.../`
- Room database/DAOs: `data/.../db/OpenTvDatabase.kt`, `Daos.kt`
- Room adapter: `data/.../RoomStorage.kt`
- Exported Room schemas: `data/schemas/`

### Server

- Process entry point: `server/.../Main.kt`
- Environment configuration: `ServerConfig.kt`
- Composition and lifecycle: `Application.kt`, `ServerGraph.kt`
- API root: `ApiRoutes.kt` at `/api/v1`
- Feature adapters: `PlaylistRoutes.kt`, `LibraryRoutes.kt`,
  `DownloadRoutes.kt`, `SessionRoutes.kt`, `MediaRoutes.kt`
- Feature use cases: matching `*ApplicationService.kt`
- HTTP contracts: `ApiModels.kt`, `ResourceDtos.kt`, `PlaybackModels.kt`
- Authentication seam: `ApiSecurity.kt`
- Media runtime: `Remux.kt`, `LiveRelay.kt`, `StreamGate.kt`,
  `MediaProcessRunner.kt`
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
- There is no OpenAPI document. OpenAPI is only wanted if generated and
  validated from executable routes/DTOs.
- `ApiSecurity.openAccess()` is a test-only adapter; production composition uses
  `ApiSecurity.authenticated()`, so the API authenticates itself. Its CSRF/Origin
  guard is covered by `ApiSecurityTest`, its environment rules by `AuthConfigTest`.
- The same-origin decision is `RequestOrigin.isSameOrigin`: an `Origin` is accepted
  when it names the `Host` the request was addressed to, or `OPENTV_PUBLIC_URL`. A
  browser sets both headers, so this is still same-origin, and it is what lets a
  first-run visitor create the first administrator over a LAN address or the dev
  server's port. Comparing against the configured URL alone made that impossible.
  A rejection is `403 origin_rejected` (distinct from `csrf_rejected`, which means a
  stale token) and is logged with both sides. `RequestOriginTest`,
  `PublicAuthOriginTest`.
- Error responses are mapped in one place: `installOpenTvErrorResponses`.
- `OPENTV_PUBLIC_URL` is optional. `PublicOrigin` answers the four questions that need an
  absolute address - OIDC callback, device-link QR, WebAuthn relying party, cookie
  `Secure`/HSTS - from the configuration when it is pinned (`AuthConfig.publicUrlPinned`,
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
  carry no provider URLs or detail-only fields. This replaced returning whole groups: a
  5,000-item category went from 2.5 MB and ~85 ms to 17 kB and ~5 ms.
- Search is indexed, never a scan: a normalized `searchName` prefix B-tree, plus FTS5
  `unicode61` (word prefixes) and trigram (mid-word, three characters or more) sidecars
  maintained by triggers. Ranking is title-prefix, then word boundary, then mid-word, and
  every section is capped server-side. Room 2.x cannot export FTS5 entities, so that DDL
  lives in the migration and callback and is proven by `OpenTvDatabaseSchemaTest` rather
  than by the exported JSON. The catalog is rewritten wholesale on refresh, so every index
  here is paid on every refresh - the ones that were kept were measured on both sides.
- The image proxy caches: bounded memory tier, disk tier for large posters, LRU eviction,
  single-flight so concurrent grids fetch a poster once, and upstream validator
  revalidation. The disk tier is optional - a filesystem that refuses it degrades the tier,
  never the server's ability to start. Authorization is unchanged: every request still
  authenticates and rechecks playlist access, the cache only reuses bytes.
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
- `MediaProbe.inspect` is single-flighted: a remote probe costs a provider connection on the
  path to playback, so two viewers of one title share the result. It probes with bounded
  `-analyzeduration`/`-probesize` first and re-probes unbounded only when that looks short.
- Listings are paged by the server, not by the browser. Channel, series and episode
  endpoints take `offset`/`limit` (validated at the route, 1..MAX) and answer with a page
  object plus a total; `useServerPaged` drives them. Listing DTOs are projections - they
  carry no provider URLs or detail-only fields. This replaced returning whole groups: a
  5,000-item category went from 2.5 MB and ~85 ms to 17 kB and ~5 ms.
- Search is indexed, never a scan: a normalized `searchName` prefix B-tree, plus FTS5
  `unicode61` (word prefixes) and trigram (mid-word, three characters or more) sidecars
  maintained by triggers. Ranking is title-prefix, then word boundary, then mid-word, and
  every section is capped server-side. Room 2.x cannot export FTS5 entities, so that DDL
  lives in the migration and callback and is proven by `OpenTvDatabaseSchemaTest` rather
  than by the exported JSON. The catalog is rewritten wholesale on refresh, so every index
  here is paid on every refresh - the ones that were kept were measured on both sides.
- The image proxy caches: bounded memory tier, disk tier for large posters, LRU eviction,
  single-flight so concurrent grids fetch a poster once, and upstream validator
  revalidation. The disk tier is optional - a filesystem that refuses it degrades the tier,
  never the server's ability to start. Authorization is unchanged: every request still
  authenticates and rechecks playlist access, the cache only reuses bytes.
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
- `MediaProbe.inspect` is single-flighted: a remote probe costs a provider connection on the
  path to playback, so two viewers of one title share the result. It probes with bounded
  analyze/probe limits first and re-probes unbounded only when that looks short.
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
- Server DTOs are separate from `:core` models.
- Kotlin DTO and TypeScript contract changes must remain synchronized.
- Provider URLs and credentials never leave the server.
- Browser playlist credentials are write-only. There is no credential-read
  endpoint. Blank secret fields on update preserve existing values.
- Browser playback URLs are opaque `StreamCipher` capabilities. A stream token seals the
  playback lease it was minted for, so URLs the proxy derives while rewriting a lease's HLS
  manifest are usable by that lease alone. Media routes take the lease from the token, not
  from the request.
- The HLS rewriter mints capabilities only for children on the manifest's own origin;
  playlists are provider-controlled input and must not be able to aim the server's HTTP
  client at another host.
- `api/http.ts` already supports same-origin cookies and has one future bearer
  token provider seam.
- Browser preferences and server settings are intentionally separate.
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
- Android composables consume ViewModel state; direct graph access is confined
  to composition/ViewModel-factory boundaries.
- `PlayerSession` owns ExoPlayer, listeners, polling, progress persistence, and
  cleanup.

## Persistence and identity

- Room schema version is 10 for `:data` (`opentv.db`), 2 for `:server-data`
  (`server-users.db`). Both modules keep their migrations in `db/Migrations.kt`,
  register them in their database factory, and prove them: `ServerUserMigrationTest`
  and `OpenTvDatabaseSchemaTest` build a database from each exported schema and open
  it, so a version bump without a migration fails in CI rather than crash-looping a
  deployment. `:data:jvmTest` and `:server-data:jvmTest` both run in CI.
- There is no audit/security-event log. The `security_events` table was removed in
  server-user schema 2: nothing read it. Reintroducing one means designing its
  reader and its retention first.
- Destructive migration fallback is not used.
- Schema changes require explicit Android and JVM migrations, exported schema,
  and migration coverage.
- Favorites, resume points, and downloads currently use existing URL/key
  identities. A future stable-content identity migration must update all three
  together rather than piecemeal.
- `ContentIdentityService` has two paths and they are not interchangeable.
  Reconciliation owns `lastSeenAtMs` and retirement and may scan a playlist;
  browsing resolves by fingerprint in batches, creates only what is missing, and
  never writes `lastSeenAtMs`. A read path that scans or touches it is a bug.

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
- Authentication seam: `ApiSecurityTest`
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
- Same-origin decision: `RequestOriginTest`, `PublicAuthOriginTest`
- Android player orchestration: `PlayerViewModelTest`
- Android player policies: `PlayerPolicyTest`

## Invariants

- No Ktor/Android/Room/server DTO types in `:core`.
- No provider credentials or raw provider URLs in browser contracts.
- No unmanaged long-lived scopes, processes, or threads.
- Compression is an allowlist of text content types. Media is streamed, and Ktor
  compresses a streaming body by buffering all of it; a new streaming route must not
  have to remember to opt out.
- Playback status codes: a lease that is gone is 410 and nothing else. 404 means many
  ordinary things on these routes (no extra tracks to expose, an unknown segment), so
  no client may infer "stop playing" from it.
- No Room destructive fallback.
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
- Secrets that arrive in a URL (activation, password reset, device link) travel in
  the fragment, never the query string: the query reaches the server's and every
  proxy's access log.
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
- Every activation target is a real control. A clickable `div` is a bug: this UI
  is driven by keyboards and TV remotes as well as touch.
- `IconName` is a closed union; a name that is not a glyph is a compile error
  rather than an invisible button.
- Do not recreate the deleted server `Routes.kt` or Android player god class.
- `PlayerProvider.tsx` composes the player; engine, remux and chrome policy stay
  in their own modules. `RemuxService` owns session lifetime and HTTP only.
