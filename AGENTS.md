# OpenTV agent guide

OpenTV is a Kotlin multiplatform Android app plus a Kotlin/JVM Ktor server and
React web client. Keep it a modular monolith: `:core` owns platform-neutral
domain/application logic, `:data` owns Room adapters, `:app` owns Android
adapters/UI, and `:server` owns the HTTP/media runtime.

## Boundaries

- Ktor types belong at the server transport edge. Route handlers validate/map
  requests and delegate; they must not own provider, database, or process policy.
- Server feature routes depend on their `*ApplicationService`, never directly on
  `Storage` or Room-facing models. Application services remain Ktor-independent
  and return server-owned DTOs.
- Keep each API family in its own `*Routes.kt` adapter. Cross-cutting concerns
  such as authentication belong in route-scoped plugins, not repeated handlers.
- API authentication is injected through `ApiSecurity`. The open-access adapter
  preserves today's behavior; replace it at the composition root instead of
  adding credential checks throughout feature routes.
- Serialize server DTOs, not `:core` persistence/domain models directly.
- External effects need an owned lifecycle. Do not create an unmanaged
  `CoroutineScope`, shutdown hook, or infinite thread; attach work to
  `ServerRuntime` and make shutdown idempotent.
- Provider URLs and credentials never leave the server. Browser-visible URLs
  must remain opaque `StreamCipher` tokens.
- Live playback, VOD remuxing, and downloads share one provider-connection
  budget. Interactive streams may evict downloads, never another viewer.
- Keep Kotlin DTOs and TypeScript API types synchronized whenever a JSON or
  WebSocket contract changes. Do not add a hand-maintained OpenAPI document;
  introduce OpenAPI only together with build-time generation and validation
  from the executable route/DTO definitions.
- Room schema changes require an explicit migration and migration test. Never
  restore destructive migration fallback.
- Android composables render state and emit events; provider, Room, and
  filesystem work belongs in ViewModels or application repositories. Prefer
  lifecycle-aware Flow collection for screen state.
- Android background workers receive dependencies through the application
  `WorkerFactory`. Do not reach into `OpenTvApp.graph` from a worker or call
  WorkManager statics from application repositories.
- Android remains a standalone IPTV reader. Do not make local M3U/Xtream flows
  depend on the OpenTV server; a future server integration belongs behind an
  optional provider adapter at the existing application boundary.
- Shared `:core` dependencies stay implementation-scoped unless their types
  appear in a public signature. Do not apply compiler plugins without matching
  source annotations or generated code.

## Validation

Use JDK 25 for the server and JDK 17-compatible code for Android/shared modules.

```bash
./gradlew :core:jvmTest :data:compileKotlinJvm :server:test
./gradlew :server:installDist
cd server/webapp && npm ci && npm run build
./gradlew testDebugUnitTest assembleDebug
```

For focused server work, run `./gradlew :server:test` before the broader matrix.
Generated web output under `server/src/main/resources/web/` is ignored.
