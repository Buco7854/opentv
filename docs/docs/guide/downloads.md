# Downloads

Save movies and episodes to your device for offline viewing.

## Starting a download

Open a movie or episode detail page and choose to download it. Progress appears in a notification and in the downloads manager inside the app.

## Pause, resume and delete

Downloads can be paused and resumed. Resuming continues from where it stopped using byte ranges, so a large movie does not start over and you do not re-request gigabytes from your provider. Tap a running download to pause it, and long press to delete it.

## Choosing where files go

By default, downloads are kept in the app's private storage. You can pick any folder instead, which makes the files visible to other apps and keeps them even if you uninstall OpenTV. A custom folder applies to new downloads.

If you change the folder later, you can move existing completed downloads into the new location from [Settings](/guide/settings#downloads).

## Connection-aware transfers

Downloads respect your provider's limits. In automatic mode, OpenTV uses your plan's maximum connections minus one, keeping a slot free for watching, and pauses a download if you start streaming from the same provider. You can also set a fixed number of simultaneous transfers in Settings.

## Downloads from an OpenTV server

Server-source downloads are still stored on this Android device. The server
starts fetching from the provider, and the device starts pulling as soon as the
growing server file has usable bytes; both transfers continue as one pipelined
operation and one progress entry.

The server copy is kept by default because another user may share it. Each
connected server has a **Remove from server after download** option; turn it on
only if you want the app to delete your server association after the local byte
counts and server `DONE` state agree.
