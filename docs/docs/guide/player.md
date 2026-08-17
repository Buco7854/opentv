# The player

OpenTV uses Media3 and ExoPlayer with hardware decoding, and supports HLS, TS, MP4 and MKV.

## Playback controls

- **Play and pause**, with a progress bar for video on demand.
- **Double-tap** the left or right side of the screen to skip back or forward by your configured step.
- **Single tap** shows or hides the controls. The title bar and the action buttons appear and hide together with the progress bar.

## Tracks and subtitles

Open the subtitle or audio sheet to pick any embedded track, or turn subtitles off. Subtitle size, style and bold are configurable with a live preview, and your preferred audio and subtitle languages can be set in [Settings](/guide/settings).

## Playback speed and scaling

Change playback speed for video on demand, and cycle the video scaling between Fit, Zoom and Stretch. Speed is hidden for live streams, where changing it would cause drift.

## HDR on a screen that cannot show it

An HDR film describes brightness on a curve meant for a much brighter panel than an
ordinary phone screen. Sent to that screen unconverted it plays very dark, with
everything below the highlights crushed towards black.

From Android 13 the app asks the decoder to convert those frames as it decodes them,
which the same hardware does at no extra cost. It asks only when the screen cannot
show the kind of HDR in hand: a phone with an HDR display still gets the film at its
full range, and ordinary video is never touched.

Two limits are worth knowing. Below Android 13 there is nothing to ask, so HDR still
plays dark. And a decoder may decline the request, which looks the same from the
outside; the error log records each time the app asked, so a picture that is still
dark can be told apart from one where the app never tried.

## Picture-in-Picture

Tap the Picture-in-Picture button, or simply press Home while watching, and playback continues in a floating window. Tapping the window reveals a play and pause control whose icon stays in sync with the actual state.

## Resume

For movies and episodes, your position is saved continuously, so playback picks up where you left off, even after the app is closed or the process is killed. Live streams are not resumed, since there is no fixed position to return to.

## Battery and data friendly

When you leave the app, playback pauses so an invisible stream does not waste one of your account's connection slots or pull unnecessary data.
