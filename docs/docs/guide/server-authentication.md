# Server authentication and user data

OpenTV's server uses its own user database at
`$OPENTV_DATA/server-users.db`. Browser sessions are opaque, revocable
server-side sessions in an `HttpOnly` cookie. The browser keeps the CSRF token
returned by `/api/v1/auth/me` in memory and sends it as `X-CSRF-Token` on every
unsafe request.

Android and the server share platform-neutral catalog/domain modules (`:core`)
and Room catalog adapters (`:data`). The Android application remains a
standalone local IPTV reader and does not include `:server-data`, share
`server-users.db`, or use server user records. A future OpenTV-server provider
can use the same `contentId`, playback-lease, and actor-oriented API without
changing Android's existing local M3U/Xtream storage.

## Required configuration

Password authentication is enabled by default. Generate its independent
32-byte encryption key once and store it in your secret manager:

```bash
openssl rand -base64 32
```

| Variable | Default | Meaning |
|---|---|---|
| `OPENTV_PUBLIC_URL` | `http://localhost:8080` | Exact browser origin and OIDC callback base. Use HTTPS outside localhost. |
| `OPENTV_AUTH_ENCRYPTION_KEY` | none | Required 32-byte base64 secret when password auth is enabled. Encrypts TOTP secrets; it is not the media-token key. |
| `OPENTV_PASSWORD_AUTH_ENABLED` | `true` | Enables local login, bootstrap, activation, and credential reset. |
| `OPENTV_MFA_REQUIRED_ROLES` | `USER,ADMIN` | Comma-separated local-password roles that must complete TOTP or WebAuthn on every login. |
| `OPENTV_INITIAL_ADMIN_USERNAME` | none | Optional one-time initial administrator username. Must be supplied with its password. |
| `OPENTV_INITIAL_ADMIN_PASSWORD` | none | Optional one-time initial administrator password. Ignored after any administrator exists. |
| `OPENTV_SESSION_IDLE_HOURS` | `24` | Browser session idle lifetime. |
| `OPENTV_SESSION_ABSOLUTE_DAYS` | `30` | Browser session maximum lifetime. |
| `OPENTV_WEBAUTHN_RP_ID` | public URL host | Exact WebAuthn relying-party ID. |
| `OPENTV_WEBAUTHN_ORIGIN` | public URL origin | Exact WebAuthn browser origin. HTTPS is required except on localhost. |

Without initial-admin variables, the server creates
`$OPENTV_DATA/bootstrap.token` with owner-only permissions and logs only its
path. Submit it once to the bootstrap flow; the file is deleted after use.
Back up the auth encryption key separately from the database. Losing it makes
encrypted TOTP credentials unrecoverable; rotate affected accounts through an
administrator credential reset.

Back up `server-users.db`, the auth encryption key, and the server download
directory together. A database restore without the matching key cannot decrypt
TOTP secrets; a database/file snapshot taken at different times may leave
download associations that require reconciliation.

## OIDC

OIDC uses Authorization Code Flow with PKCE. OpenTV performs discovery, verifies
the ID-token signature and issuer/audience/time/nonce claims, then discards all
provider tokens. Configure all three required values or none:

| Variable | Default |
|---|---|
| `OPENTV_OIDC_ISSUER` | none; absolute HTTPS issuer |
| `OPENTV_OIDC_CLIENT_ID` | none |
| `OPENTV_OIDC_CLIENT_SECRET` | none |
| `OPENTV_OIDC_SCOPES` | `openid profile email` |
| `OPENTV_OIDC_USERNAME_CLAIM` | `preferred_username` |
| `OPENTV_OIDC_DISPLAY_NAME_CLAIM` | `name` |
| `OPENTV_OIDC_GROUPS_CLAIM` | `groups` |
| `OPENTV_OIDC_ADMIN_GROUPS` | none; comma-separated, exact case-sensitive matches |
| `OPENTV_OIDC_AUTO_PROVISION` | `false` |

Register the exact callback
`$OPENTV_PUBLIC_URL/api/v1/auth/oidc/callback`. Accounts are identified only by
issuer and subject; email and username never auto-link accounts. With automatic
provisioning off, administrators approve or explicitly attach pending
identities. An unknown identity in a configured admin group may bootstrap an
OIDC-only installation.

Disabling password authentication preserves local credentials but revokes all
password-origin sessions at startup. A complete OIDC configuration and an
admin-group bootstrap mapping are then mandatory unless a currently active
OIDC admin identity already exists. Local activation, reset, environment
seeding, and bootstrap are unavailable while password authentication is
disabled.

OIDC and cookies require one exact public origin. Register
`${OPENTV_PUBLIC_URL}/api/v1/auth/oidc/callback`; configure WebAuthn for the same
origin and RP ID; and do not alternate host aliases or ports. Non-loopback HTTP
is rejected unless `OPENTV_ALLOW_INSECURE_HTTP=true`, which is for isolated
development only and provides no transport security.

## Authorization and user-owned state

Administrators manage playlists, settings, user lifecycle and assignments, and
the now-watching dashboard. Ordinary users see only explicit playlist grants.
The default-playlist list is a creation template: activation, approval, or JIT
provisioning copies it into explicit grants, and later template changes do not
alter existing accounts.

Server favorites, resume points, downloads, and playback use immutable
server-issued `contentId` values. They no longer use the shared Android
favorites/resume/download stores. Existing global server rows in the older
catalog database are intentionally not imported; after confirming the new
deployment, an operator may remove obsolete favorite, resume, and download rows
from `opentv.db` during an offline maintenance window. Back up both databases
first. OpenTV never interprets those rows as web-user state, so leaving them in
place is safe.

Downloads are private user associations over a shared physical content blob.
Removing a playlist grant hides and suspends its user associations. Removing
the final association removes the physical transfer/file.

Playback begins with `POST /api/v1/playback`. The response contains a
server-issued lease and short-lived, lease-scoped media URLs. Room kicks,
logout, session revocation, user disable/reset/delete, and playlist grant
removal terminate the applicable leases and close their proxy body, relay
attachment, transcoder, remux attachment, provider reservation, and WebSocket.
Stale leases return `410 playback_revoked`.

## Web client behavior

The bundled web client implements the server-owned authentication and user-data
contracts:

- It provides bootstrap/activation, password, TOTP, WebAuthn, recovery-code,
  OIDC callback, pending-approval, and account-security states.
- It loads `/api/v1/auth/me`, retains its CSRF token only in memory, sends
  `X-CSRF-Token` on unsafe calls, and treats `401`, `403`, and `410` as terminal
  auth/authorization/playback states.
- Favorites, resume, downloads, and playback use `contentId`; ordinary user
  requests never submit a user ID.
- Playback begins with `POST /api/v1/playback`, uses only lease-scoped media
  URLs, and renews the media grant before expiry.
- Room requests contain only the lease target and requested operation. The
  server derives acting identity and display names.
- Playlist management, mutable server settings, provider diagnostics,
  now-watching, user management, pending OIDC identities, templates, and grants
  are visible only to administrators.

Browser cookie delivery is implemented now. Native access/refresh-token,
mobile-OIDC handoff, and Android-TV pairing endpoints are deliberately deferred
behind the existing auth-flow, session-issuer, and actor seams.

## Account recovery

Use recovery options in this order:

1. Sign in with one of the account's one-time recovery codes.
2. Ask another administrator to reset the account's password and MFA. This
   revokes its sessions and playback leases and produces a one-time setup token.
3. Stop OpenTV and restore a consistent backup of `server-users.db`, the
   matching auth encryption key, and download data.
4. Only as a destructive last resort, stop OpenTV, move
   `server-users.db` aside, and start with a fresh user database and bootstrap
   administrator. This loses users, grants, activity, sessions, and download
   associations; unreferenced physical download files may require manual
   cleanup.

There is deliberately no unauthenticated remote recovery endpoint or
environment switch that bypasses account authorization.
