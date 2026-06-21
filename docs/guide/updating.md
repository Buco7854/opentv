# Updating the app

OpenTV does not auto-update, since it is not on the Play Store. You update it by installing a newer APK over the current one.

## How updates work

Every build is signed with the same key. Android lets a new APK replace an existing app only when the signatures match, so installing a newer OpenTV over your current one keeps all of your data: playlists, favorites, downloads and settings.

To update:

1. Download a newer APK from the [release](https://github.com/Buco7854/opentv/releases/latest/download/app-debug.apk) or [dev](https://github.com/Buco7854/opentv/releases/download/dev/app-debug.apk) channel.
2. Open it and confirm. Android will show an update prompt instead of a fresh install.

Your playlists and settings are preserved.

## Switching channels

You can move between the release and dev channels freely, because both use the same signing key. Install whichever APK you want over the other.

## First switch may need one uninstall

If you previously sideloaded a build that was signed with a different key, the first install of a same-key build will fail with a signature mismatch. In that one case, uninstall the old app, then install the new APK. From then on, every update installs in place.

## Checking your version

Open [Settings](/guide/settings) inside the app to see the installed version. Compare it with the newest entry on the [releases page](https://github.com/Buco7854/opentv/releases).
