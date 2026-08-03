---
title: Android app with your server
---

# Using the Android app with your server

The Android app works on its own with M3U playlists and Xtream logins, and it
can also connect to your OpenTV server. Connecting is optional and additive: a
server becomes another source alongside whatever you already have, and your
local playlists keep working exactly as before.

Connecting gets you the playlists your account is granted on the server, your
watch progress and favorites synced across every device signed in to it, watch
together with other viewers, and downloads saved to the device.

## Connecting

Open the sources panel and choose **OpenTV server**, or pick it when adding a
source, then enter the server address (for example `https://tv.example.com`).
The app checks the address before asking for anything else, so a typo tells you
straight away rather than failing at sign-in.

The app then offers only the sign-in methods your server actually enables:

- **Username and password**, including two-factor codes and recovery codes. If
  your server requires two-factor authentication and you have not set it up yet,
  the app walks you through enrolling, showing a QR code for your authenticator
  app.
- **Link this device**: approve from a device that is already signed in. The
  app shows a QR code; scan it with the signed-in device and approve. On a phone
  you can also tap **Open on this device** to approve in your own browser.
- **Sign in with a browser**, for single sign-on and passkeys, which complete in
  a browser. On a phone the app opens its own browser directly. A QR code is the
  fallback on a TV, on a device without a browser, or when the browser cannot be
  opened. Once you finish in the browser the app signs itself in.

After approval, an Android browser may return through the fixed
`opentv://sign-in` link. That link is only a wake-up signal: it carries no token,
account identity or other secret. The app's authenticated polling request remains
authoritative, so intercepting or losing the return link can change only how soon
the next poll happens, not who signs in.

On Android TV, device linking is the easy path: the TV displays the code and you
scan it with your phone. Nothing needs typing on a remote, and the sign-in,
source, browsing, player, and settings controls all support D-pad focus.

The resulting server session is stored in an encrypted vault backed by Android
Keystore, not in the catalog database. OpenTV excludes that vault, the catalog,
and player preferences from Android backup and Android 12+ device-to-device
transfer; after moving to another device, connect and sign in again.

## Your account and administration

Playlist administration that belongs next to a source is available directly in
the app: when the server grants the operation, an administrator can refresh,
edit or delete that server playlist, and correct an M3U category's type. The app
uses the form fields and deletion warning returned by the server rather than
reconstructing those rules locally. For source-panel actions, a capability can
instead direct the operation to the server's browser UI, which lets a future
server move it without an Android release.

Account settings and the broader server administration UI still open the
server's own pages in a browser, so those security and deployment controls stay
in one place. The app opens only pages on that server's own origin. On Android
11 and newer it checks browser availability before offering the handoff; the app
declares HTTP and HTTPS browser intents for Android's package-visibility rules.

From the server's entry in the sources panel you get **Account and security**
(password, two-factor, sessions), and, if your account is an administrator,
**Administration** and **Now watching**. On a TV, where a browser is awkward or
absent, the app shows a QR code to open the page on your phone.

## Watching

Playback works the same as any other source, with one difference in your favour.
Browsers can only play a narrow set of formats and cannot select every muxed
track directly. Android tells the server which codecs this device actually
decodes and that ExoPlayer selects tracks in band. When the video and all audio
tracks are decodable, the app direct-plays the original even when it has several
audio tracks or subtitles: no remux, less work for your server, and no quality
loss.

Where a file does need converting, because of a codec your device lacks or
because it is catch-up, the server prepares it and the app plays that instead.
Audio track and subtitle selection work either way.

## Watch together

If someone else is watching the same thing, the player offers to watch together.
The host approves who joins and can hand out control, so anyone with control can
play, pause and seek for everyone.

Watching together also works between your own devices, and for the same thing it
is the only thing that works. One account cannot play the same thing twice
independently: start what another of your devices is already playing and the app
offers to join that session instead. Joining your own device needs no approval,
since you are both ends of it. Declining ends the attempt on the new device
rather than starting a second stream, and you can start it again once the other
device stops.

"The same thing" means the same source, not merely the same title. Watching a
channel live and replaying a programme from that channel are different sources,
so they neither collide nor share: they are two ordinary streams on two
connections. Two devices on the same live channel, or on the same catch-up
programme, do collide and are offered the join.

Being removed from a room by its host is not a ban. You can come back, but you
have to ask: the host approves you as they would anyone else. That applies to
the device that was removed, not to your account: removing one of your devices
leaves your others exactly as they were, and the removed one has to ask like
anybody would. Signing out and back in on it starts a new session and clears
that, which is the point of a kick rather than a ban.

The reason is your provider's connection limit. Two devices watching one live
channel together share a single connection to your provider, so joining costs
nothing extra, where two independent plays would have cost two. A movie is
different: viewers of a movie are rarely at the same position, so a room of
fully capable devices still plays each one directly and still costs a connection
each. On a provider that allows only one connection, joining a movie therefore
reports that capacity is full, which is the same answer by a different route.

Everyone stays in sync automatically. If one person's device cannot play the
original stream, the whole room switches to a converted one so nobody is left
behind; if everyone's device can, the room plays the original and no conversion
happens at all.

For fully capable Android viewers, movies and episodes direct-play and the room
creates no shared remux. Raw-TS live channels use one shared server relay and
one upstream read. Playlist-only `.m3u8` rooms use the server's bounded
shared-HLS cache: each manifest or media resource is fetched once for the
room's share group and rewritten separately with each viewer's lease
capability. Both paths avoid one provider read per viewer.

Administrators can pause a stream or send a message to a viewer, and those
arrive in the app as they do in a browser.

## Favorites and progress

Favorites and watch progress for a server live **on that server**, so they follow
you to every device signed in to it. Local playlists keep their favorites and
progress on the device, as they always have.

The favorites screen shows everything together in one list, grouped by type
rather than by where it came from, with chips to narrow to a single source when
you want that. If a server is unreachable it says so and offers a retry, while
your other favorites still appear.

## Downloads

Downloads from a server are saved **on your device**, so they play offline like
any other download.

Because your provider's details never leave the server, a download happens in
two pipelined transfers. The server starts fetching from the provider; as soon
as the growing server file has usable bytes, your device begins pulling fixed
snapshots while the server continues. The downloads screen presents the whole
operation as one continuous entry, not separate preparation and device phases.

By default your download association stays on the server afterwards. You can
turn on **Remove from server after download** per server to delete that
association after the local copy is verified; the shared server file is deleted
only when no other user refers to it.

## If something goes wrong

- **"Couldn't reach the server"**: the app could not contact it. Check the
  address and that your device is on a network that can reach it. Server sources
  are not cached, so nothing from that server appears until it is reachable.
  You see this on the home screen too when a connected server will not list its
  playlists. It is deliberately not the same as the welcome screen: an empty
  home means you have nothing connected, never that we failed to ask.
- **"Signed out"**: your session ended or was revoked. Sign in again from the
  server's entry in the sources panel. A stored session the device can no longer
  decrypt reads the same way, so the server stays where it is and offers you a
  sign-in rather than quietly disappearing.
- **A series that opens with no episodes**: the series page says so and offers a
  retry, rather than showing a poster above an empty space. The server has no
  episodes for it at that moment, which is what a favorite looks like while the
  playlist it came from is being refreshed; retrying once that finishes fills it
  in. A series still loading its first page is not this, and does not say it.
  For a series from an Xtream panel the retry does reach the panel again: an
  answer with no episodes is never recorded as the answer, so it is asked afresh
  each time you open the page. The server log notes each empty reply, which is
  what tells a series the panel has nothing for apart from one it described in a
  way we could not read.
- **"Playback capacity"**: your provider's connection limit is reached; try
  again shortly.
- **Playback stopped with "Playback ended"**: an administrator ended the
  stream, or your session was revoked.
