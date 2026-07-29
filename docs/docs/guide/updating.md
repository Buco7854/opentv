# Updating the app

OpenTV does not auto-update, since it is not on the Play Store. You update it by installing a newer APK over the current one.

## How updates work

Published builds use the same signing key, so Android can install a newer APK
over the existing app instead of requiring an uninstall.

:::caution Catalog schema changes

OpenTV deliberately recreates its catalog database when an update changes the
Room schema version; it does not run hand-written migrations. That recreation
removes local playlists, connected-server entries, favorites, resume points,
download records, and other catalog-backed state. Downloaded files may remain
in storage, but OpenTV no longer has their records. Connected-server sessions
are excluded from backup and transfer, so connect and sign in again.
Preferences outside the catalog database remain.

Back up or record source details before installing an update that announces a
catalog schema change.

:::

To update:

1. Download a newer APK from the [release](https://github.com/Buco7854/opentv/releases/latest/download/app-release.apk) or [dev](https://github.com/Buco7854/opentv/releases/download/dev/app-release.apk) channel.
2. Open it and confirm. Android will show an update prompt instead of a fresh install.

Android installs the package in place; whether catalog data survives depends on
whether that release changed the catalog schema as described above.

## Switching channels

You can move between the release and dev channels freely, because both use the same signing key. Install whichever APK you want over the other.

## First switch may need one uninstall

If you previously sideloaded a build that was signed with a different key, the first install of a same-key build will fail with a signature mismatch. In that one case, uninstall the old app, then install the new APK. From then on, every update installs in place.

## Checking your version

Open [Settings](/guide/settings) inside the app to see the installed version. Compare it with the newest entry on the [releases page](https://github.com/Buco7854/opentv/releases).
