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
32-byte encryption key once in either base64 or hexadecimal form and store it
in your secret manager:

```bash
# Choose either format:
openssl rand -base64 32
openssl rand -hex 32
```

| Variable | Default | Meaning |
|---|---|---|
| `OPENTV_PUBLIC_URL` | derived per request | Browser origin and OIDC callback base. Set it for any deployment with a fixed address; when unset, both follow the address each request arrived on. Use HTTPS outside localhost. |
| `OPENTV_AUTH_ENCRYPTION_KEY` | none | Required 32-byte secret encoded as base64 or 64-character hex when password auth is enabled. Encrypts TOTP secrets; it is not the media-token key. |
| `OPENTV_PASSWORD_AUTH_ENABLED` | `true` | Enables local login, bootstrap, activation, and credential reset. |
| `OPENTV_MFA_REQUIRED_ROLES` | `USER,ADMIN` | Comma-separated local-password roles that must complete TOTP or WebAuthn. Accepts `USER`, `ADMIN`, or both; an empty value is ignored and the default applies on every login. |
| `OPENTV_INITIAL_ADMIN_USERNAME` | none | Optional one-time initial administrator username. Must be supplied with its password. |
| `OPENTV_INITIAL_ADMIN_PASSWORD` | none | Optional one-time initial administrator password. Ignored after any administrator exists. |
| `OPENTV_SESSION_IDLE_HOURS` | `24` | Browser session idle lifetime. |
| `OPENTV_SESSION_ABSOLUTE_DAYS` | `30` | Browser session maximum lifetime. |
| `OPENTV_WEBAUTHN_RP_ID` | public URL host | Exact WebAuthn relying-party ID. |
| `OPENTV_WEBAUTHN_ORIGIN` | public URL origin | Exact WebAuthn browser origin. HTTPS is required except on localhost. |

Without initial-admin variables, the server creates
`$OPENTV_DATA/bootstrap.token` with owner-only permissions and logs only its
path. Submit it once to the bootstrap flow; the file is deleted after use. The
sign-in screen offers "Create the first administrator" while that file exists and
no administrator does, so a server configured for OIDC can also be entered
through single sign-on by a member of `OPENTV_OIDC_ADMIN_GROUPS`.

Back up the auth encryption key separately from the database. Losing it makes
encrypted TOTP credentials unrecoverable; rotate affected accounts through an
administrator credential reset.

Back up `server-users.db`, the auth encryption key, and the server download
directory together. A database restore without the matching key cannot decrypt
TOTP secrets; a database/file snapshot taken at different times may leave
download associations that require reconciliation.

## Addresses

Four things need to know where browsers reach this server: the OIDC callback, the
device-linking QR, the WebAuthn relying party, and whether the session cookie may
be marked `Secure`.

`OPENTV_PUBLIC_URL` answers all four when it is set, and a deployment with a fixed
address should set it: the OIDC callback has to be registered at the identity
provider, and a passkey belongs to exactly one relying party, so both want one
predictable value. It is also required whenever a reverse proxy rewrites `Host` to
its upstream (nginx's `proxy_set_header Host $proxy_host` default does), because the
address the browser used never reaches the server then.

When it is unset, each of the four follows the request instead of a loopback default
nobody browses - so a LAN address, a container name or a published port works with no
configuration. `X-Forwarded-Proto` and `X-Forwarded-Host` are honoured only when the
peer is listed in `OPENTV_TRUSTED_PROXIES`, the same rule the client address follows.

Two consequences worth knowing:

- WebAuthn needs a secure context and a host name. Reached over plain HTTP off
  localhost, or by IP address, the server stops offering passkeys and answers a
  ceremony with `409 webauthn_unavailable` rather than letting the browser fail.
  A passkey enrolled while the address was derived belongs to that host name.
- `OPENTV_WEBAUTHN_RP_ID` and `OPENTV_WEBAUTHN_ORIGIN` still pin the relying party on
  their own, even with `OPENTV_PUBLIC_URL` unset.

Independently of all this, every unsafe request must carry an `Origin` naming either
the host it was addressed to or `OPENTV_PUBLIC_URL`; a browser sets both headers
itself, so that stays a same-origin check. A rejected origin answers
`403 origin_rejected` and logs the received origin, the requested host and the
configured URL side by side.

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

OIDC needs one address the provider knows: set `OPENTV_PUBLIC_URL` and register
`${OPENTV_PUBLIC_URL}/api/v1/auth/oidc/callback` exactly. Without it the callback
follows whichever address the sign-in was started from, so every alias a user might
type would have to be registered - and a proxy that rewrites `Host` would produce one
the provider never saw. Non-loopback HTTP is rejected unless
`OPENTV_ALLOW_INSECURE_HTTP=true`, which is for isolated development only and provides
no transport security.

## Passkeys

Passkeys can be used either as the WebAuthn step after a password or as the
primary sign-in method. Primary sign-in is username-less: the browser requests
discoverable-credential options, the authenticator selects the account, and the
server resolves the credential ID to its owner. This avoids exposing a public
username lookup. Primary assertions require user verification; the existing
password-plus-WebAuthn flow retains its less restrictive second-factor policy.

New registrations request a discoverable credential when the authenticator
supports one. Existing security-key registrations remain usable as a second
factor and may also sign in when their authenticator can return the credential
without an allow-list. Assertion challenges are short-lived and single-use.
Credential counters and ownership are checked before the challenge, updated
credential, login timestamp, and new session are committed atomically.

The account-security screen lists registered passkeys and allows a recently
authenticated user to remove one. The server refuses removal when it would
eliminate the account's last enabled sign-in method or, for an MFA-required
role, its last MFA method. A successful removal revokes every existing session
and replaces the acting browser's session, so other devices are signed out
without ejecting the user from account security. Activation/password reset
that clears MFA and administrator credential reset revoke all sessions.

A signed-in user may also add a TOTP authenticator from account security after
a recent authentication, including when a passkey is already enrolled.
Completing the ceremony rotates the session without replacing existing
recovery codes. An account may have only one confirmed TOTP authenticator.
Removing it applies the same sign-in and MFA-factor safeguards and session
rotation as passkey removal. Any unfinished TOTP enrollment is invalidated.

## Linking a device

A browser can start a five-minute device-link request. It receives an opaque
polling token held only in memory and a QR link whose secret is in the URL
fragment. The new device polls no faster than the interval returned by the
server; faster polling returns `429` and `Retry-After`.

Scanning the QR claims the request for the signed-in phone user and moves it
from `PENDING` to `SCANNED`. The phone shows the requesting device name, user
agent, and IP address. The requesting browser shows the claiming account's
display name and username, but no account identity is returned before the scan.
A second account cannot take over a claimed request, while rescanning from the
same account is idempotent.

Approval requires an active session that has satisfied MFA and is accepted
only after that same account claimed the request. Claim, approval, and denial
are conditional database updates. Polling an approved request atomically
consumes it while inserting the linked session, so decision races and poll
replays cannot mint multiple sessions. Denial and expiry never issue a cookie.

The linked session inherits the approving session's authentication method and
is marked `LINKED_DEVICE`. This preserves password-disable revocation policy.
The server rechecks request expiry and the approving account's active status at
claim time. Device names are stripped of control characters and truncated
before being stored or rendered.

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

Browser cookie delivery and browser-to-browser device linking are implemented
now. Native access/refresh-token and mobile-OIDC handoff remain deferred behind
the existing auth-flow, session-issuer, and actor seams.

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
