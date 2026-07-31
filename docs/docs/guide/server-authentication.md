# Server authentication and user data

OpenTV's server keeps everything in one database at `$OPENTV_DATA/opentv.db`:
the catalog it fetches from your providers and the accounts, sessions, grants
and user state it owns. Sessions are opaque and revocable. Every client sends
the same session token as `Authorization: Bearer` on protected HTTP requests.

One file rather than two is what lets the server enforce, in the database
itself, that a grant or a content identity cannot outlive the playlist it
refers to: deleting a playlist cascades to its grants and identities in a
single transaction. It also means the file is *not* disposable — see
[account recovery](#account-recovery) before moving it aside.

The bundled web client stores its bearer in `localStorage`. There are no
session cookies, CSRF tokens, or auth-route Origin checks. Because JavaScript
can read the token, the server's Content Security Policy allows scripts only
from the OpenTV origin and forbids inline script attributes. Android stores
each server token AES-GCM encrypted under a non-exportable Android Keystore
key; it never puts the token in the catalog database.

Android and the server share platform-neutral catalog/domain modules (`:core`)
and Room catalog adapters (`:data`). The Android application remains a
standalone local IPTV reader and does not include `:server-data`. Its own
`opentv.db` holds the catalog tables only; the account, credential, session and
grant tables exist solely in the server's build and are absent from the released
APK. Connecting an OpenTV server adds an optional source alongside the app's
existing local M3U/Xtream sources.

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
| `OPENTV_SESSION_IDLE_HOURS` | `24` | Session idle lifetime. |
| `OPENTV_SESSION_ABSOLUTE_DAYS` | `30` | Session maximum lifetime. |
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

Back up `opentv.db`, the auth encryption key, and the server download
directory together. A database restore without the matching key cannot decrypt
TOTP secrets; a database/file snapshot taken at different times may leave
download associations that require reconciliation.

## Addresses

Four things need to know where browsers reach this server: the OIDC callback,
the device-linking QR, the WebAuthn relying party, and whether the short-lived
OIDC transaction cookie and HSTS may use HTTPS-only behavior.

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

`OPENTV_PUBLIC_URL` does not authorize requests. Protected routes authenticate
the bearer token; public authentication routes do not apply a separate Origin
guard.

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

The OIDC transaction uses one short-lived, callback-only `HttpOnly` cookie to
bind the callback to the authorization request. This is not a login session
cookie. After a successful exchange, the server redirects with the OpenTV
session in `/#session=...` and echoes a random `handoff` value that the initiating
tab stored in `sessionStorage`. The web client accepts only a recent exact
correlation match, saves the bearer, and removes both values from the address
before loading the account. An unsolicited or mismatched session fragment is
discarded.

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

A client can start a five-minute device-link request. It receives an opaque
polling token held only in memory and a QR link whose secret is in the URL
fragment. The new device polls no faster than the interval returned by the
server; faster polling returns `429` and `Retry-After`.

The same mechanism serves two situations. A **second device** scans the QR and
approves from an account that is already signed in. **One device on its own**
starts the request, opens the link in its own browser, and signs in there — this
is how Android signs in by default, and it is why the app no longer rebuilds each
authentication method natively. A request started this way is marked as browser
sign-in, and the web client completes the approval by itself only when it landed
with no existing session and the server-bound request agrees it is a browser
sign-in. An ordinary QR link, or a browser that is already signed in, still asks
for explicit approval, so a link someone was sent cannot authorize silently.

Scanning the QR claims the request for the signed-in phone user and moves it
from `PENDING` to `SCANNED`. The phone shows the requesting device name, user
agent, and IP address. The requesting client shows the claiming account's
display name and username, but no account identity is returned before the scan.
A second account cannot take over a claimed request, while rescanning from the
same account is idempotent.

Approval requires an active session that has satisfied MFA and is accepted
only after that same account claimed the request. Claim, approval, and denial
are conditional database updates. Polling an approved request atomically
consumes it while inserting the linked session, so decision races and poll
replays cannot mint multiple sessions. Denial and expiry never issue a session.

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
favorites/resume/download stores. Those Android-shaped `favorites`, `resume` and
`downloads` tables still exist in the file — they are part of the shared catalog
schema — but the server never reads or writes them, so an operator may leave
them alone. Removing their rows during an offline maintenance window is
optional; back up `opentv.db` first.

A grant or content identity cannot refer to a playlist that no longer exists:
both are declared with `ON DELETE CASCADE` against the playlist row, so
deleting a playlist removes its grants and identities in the same transaction.
A recreated playlist therefore cannot inherit access from a deleted one, even if
SQLite reuses the numeric row id.

Downloads are private user associations over a shared physical content blob.
Removing a playlist grant hides and suspends its user associations. Removing
the final association removes the physical transfer/file.

Deleting a download in the Android app removes your association on the server
too, so a file you have deleted does not keep occupying server storage. It is
your association only: another user who downloaded the same title keeps theirs,
and the shared file survives until its last reference goes. The intent is
recorded before the local file is removed, so an unreachable server delays the
cleanup rather than losing it. A per-server setting can also release your
association automatically as soon as a download finishes pulling; it is off by
default, because keeping it lets your other devices pull the same file without
the server fetching it again. A completed copy on your device stays usable even
if its server association is later removed.

Playback begins with `POST /api/v1/playback`. The response contains a
server-issued lease and lease-scoped media URLs. Media, image, download-file,
and WebSocket URLs use purpose-limited capabilities instead of
the bearer. In particular, the browser first authenticates
`POST /playback/{id}/ws-token`, then puts only that 30-second, session-and-lease
bound capability in the socket's `ws_token` query parameter.

Logout, session revocation, user disable/reset/delete, and playlist grant
removal terminate the applicable leases and close their proxy body, relay
attachment, transcoder, remux attachment, provider reservation, and WebSocket.
A room kick first removes the viewer from the room and shared read, delivers
`room-ended`, and revokes the lease after a server-owned 750 ms notice grace.
Stale leases return `410 playback_revoked`.

Watch-together commands have a positive per-lease monotonic `sequence`, so
clients discard missing, duplicate, or older delivery that races between the
WebSocket and HTTP heartbeat fallback. A room reload barrier has its own
positive `generation`; `room-audio`, `ready`, and `room-go` must refer to that
generation, so a late acknowledgement cannot release a newer barrier.

## Web client behavior

The bundled web client implements the server-owned authentication and user-data
contracts:

- It provides bootstrap/activation, password, TOTP, WebAuthn, recovery-code,
  OIDC callback, pending-approval, and account-security states.
- It keeps the bearer in `localStorage`, sends it as `Authorization: Bearer` on
  every protected HTTP request, sends no ambient credentials, and has no CSRF
  transport.
- It treats `401` as an authentication outcome, `403` as an authorization
  outcome, and `410 playback_revoked` as a playback-lease outcome. Network,
  timeout, and cancellation failures do not clear the session.
- Favorites, resume, downloads, and playback use `contentId`; ordinary user
  requests never submit a user ID.
- Playback begins with `POST /api/v1/playback`, uses only lease-scoped media
  URLs, and renews the media grant before expiry.
- Room requests contain only the lease target and requested operation. The
  server derives acting identity and display names.
- Playlist management, mutable server settings, provider diagnostics,
  now-watching, user management, pending OIDC identities, templates, and grants
  are visible only to administrators.

Android uses the same bearer-protected API and reports itself as a native client.
It signs in through your server's own web login by default, so whatever that
server accepts — password, TOTP, passkey, single sign-on — works without the app
implementing it, and a method you enable later needs no app update. No provider
refresh token is stored on Android. The in-app password form remains for a
password-only server, or a device with no browser, and QR device linking remains
for approving from a second device.

Playlists reached through a server offer the same operations as local ones, and
the app performs them itself: refresh, edit, delete and provider account are all
native. What differs is who may use them. Clearing your watch progress is yours,
because it is your own. Everything else edits what other people see and is
administrative — including correcting a category's type, since the override is
stored on the playlist and re-applied at every refresh, so it changes the catalog
for everyone who can see it. Native Xtream categories cannot be reclassified at
all; the provider owns them.

Editing a playlist never brings its provider credentials to the device. The form
prefills only the name; server, username, password and URLs stay blank, and the
server reports merely *that* a stored value exists. A field left blank keeps what
is stored, which is why an administrator can retype a provider password from a
phone that has never held the old one.

The app asks the server which operations apply to *you*, and never decides from a
cached role. That is a display concern only: the server enforces each operation
independently, rechecking current status and role at the moment it runs, so a
client that draws a button it should not have still cannot use it, and an
administrator demoted mid-request is refused.

A refresh is a server-owned job the app polls rather than one long request, so it
survives a mobile connection dropping. Deletion's confirmation text comes from
the server, so what you are warned about cannot drift from what the machine
performing it will actually remove.

## Account recovery

Use recovery options in this order:

1. Sign in with one of the account's one-time recovery codes.
2. Ask another administrator to reset the account's password and MFA. This
   revokes its sessions and playback leases and produces a one-time setup token.
3. Stop OpenTV and restore a consistent backup of `opentv.db`, the matching
   auth encryption key, and download data.
4. Only as a destructive last resort, stop OpenTV, move `opentv.db` aside, and
   start with a fresh database and bootstrap administrator.

   Because accounts and catalog share one file, this step discards **both**.
   You lose users, grants, activity, sessions and download associations, and
   also every playlist, channel, guide and metadata row. The catalog half is
   re-fetchable — re-add each playlist and let it refresh — but the account
   half is not, so prefer step 3 whenever a backup exists. Unreferenced
   physical download files may require manual cleanup.

There is deliberately no unauthenticated remote recovery endpoint or
environment switch that bypasses account authorization.
