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

### Web

- Composition/routes: `server/webapp/src/App.tsx`
- Typed API facade and TS contracts: `src/api.ts`
- HTTP/errors/auth transport: `src/api/http.ts`
- Shared playlist catalog and playlist-route guard: `src/library.tsx`
- Browser-only preferences: `src/preferences.ts`
- Shared async/download state: `src/hooks.ts`
- Lightweight player navigation: `src/player/PlayerNavigation.tsx`
- Playback runtime: `src/player/PlayerProvider.tsx`

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
- Authentication is installed once as a route-scoped plugin. Feature handlers
  contain no credential policy.
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

- Room schema version is 8 for `:data` (`opentv.db`), 2 for `:server-data`
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
- Do not recreate the deleted server `Routes.kt` or Android player god class.
