# EPG and catch-up

## Electronic program guide

OpenTV reads an XMLTV guide, either from your Xtream account automatically or from an EPG URL you provide for an M3U playlist. Gzip compressed guides are supported.

- Live rows show the current program with a progress indicator and what is on next.
- Each channel has a full guide you can open while browsing or from inside the player.

The guide is stored locally and refreshed on a schedule, so opening it does not cost a network request every time.

## Catch-up and timeshift

On channels that keep an archive, you can replay past programs. OpenTV supports Xtream timeshift and the M3U `catchup-source` attribute.

To use it:

1. Open the guide for a channel that has an archive, from browsing or from the player.
2. Pick a past program. If it can be replayed, it starts playing in the same player.

A calendar button marks channels and programs where catch-up is available. The per-channel guide that powers catch-up is fetched only when you open it, and then cached, to keep requests low.
