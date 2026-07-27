// Reflects the <video> element into React: playback state, the tracks the browser exposes
// itself, and the active subtitle cue. Everything here reads the element, never the engine.

import Hls from 'hls.js';
import mpegts from 'mpegts.js';
import { MutableRefObject, RefObject, useEffect, useRef } from 'react';
import { t } from '../i18n';
import { prefs } from '../preferences';
import { PlaybackStatusActions } from './playbackStatus';
import { RemuxSession } from './useRemuxSession';

/** Tracks the browser owns (native HLS, mp4/webm), so the pickers can drive them directly. */
export interface NativeTracks {
  text: TextTrack[];
  audio: { enabled: boolean }[];
}

export function useMediaElement(opts: {
  videoRef: RefObject<HTMLVideoElement | null>;
  hlsRef: MutableRefObject<Hls | null>;
  mpegtsRef: MutableRefObject<mpegts.Player | null>;
  remuxRef: MutableRefObject<RemuxSession | null>;
  /** Cue scanning restarts when the mounted source changes. */
  activeUrl: string;
  actions: PlaybackStatusActions;
  onEnded: () => void;
  /** The remux is playing: clear any "preparing" state. */
  onPlaying: () => void;
  /** The remux stream died and must be re-requested. */
  onRemuxDied: () => void;
  /** Native <video> failed with no engine attached; probe the URL for a real reason. */
  onNativeError: () => void;
}): MutableRefObject<NativeTracks> {
  const {
    videoRef, hlsRef, mpegtsRef, remuxRef, activeUrl, actions,
    onEnded, onPlaying, onRemuxDied, onNativeError,
  } = opts;
  const {
    setPaused, setBuffering, setBufferedEnd, setTime, setTracks, setCueText,
  } = actions;

  const nativeTracks = useRef<NativeTracks>({ text: [], audio: [] });

  useEffect(() => {
    const video = videoRef.current!;
    video.volume = prefs.volume;
    video.muted = prefs.muted;
    // Reflect the element's real state now, so a reload (or autoplay being blocked) can't
    // leave the play/pause button showing the wrong icon until the next event.
    setPaused(video.paused);
    const onTime = () => setTime({ position: video.currentTime, duration: video.duration });
    const onPlay = () => setPaused(false);
    const onPause = () => setPaused(true);
    const onWaiting = () => setBuffering(true);
    const onReady = () => {
      setBuffering(false);
      setPaused(video.paused);
      if (remuxRef.current) onPlaying();
    };
    const onVolume = () => {
      prefs.volume = video.volume;
      prefs.muted = video.muted;
    };
    const onProgress = () => {
      const ranges = video.buffered;
      let end = 0;
      for (let i = 0; i < ranges.length; i++) {
        if (ranges.start(i) <= video.currentTime + 0.5 && ranges.end(i) > end) end = ranges.end(i);
      }
      setBufferedEnd(end);
    };
    const onError = () => {
      if (hlsRef.current || mpegtsRef.current) return;
      if (remuxRef.current) { onRemuxDied(); return; }
      onNativeError();
    };
    // Browser-exposed tracks (native HLS, mp4/webm); hls.js reports its own instead.
    const readNativeTracks = () => {
      if (hlsRef.current || remuxRef.current) return;
      const text = Array.from(video.textTracks ?? []).filter(
        (track) => track.kind === 'subtitles' || track.kind === 'captions',
      );
      type NativeAudioTrack = { enabled: boolean; label?: string; language?: string };
      const audioList = (video as HTMLVideoElement & { audioTracks?: ArrayLike<NativeAudioTrack> }).audioTracks;
      const audio = audioList ? Array.from(audioList) : [];
      nativeTracks.current = { text, audio };
      setTracks({
        audio: {
          names: audio.map((track, i) => track.label || track.language || t('player.audioN', { n: i + 1 })),
          current: audio.findIndex((track) => track.enabled),
        },
        subs: {
          names: text.map((track, i) => track.label || track.language || t('player.subtitlesN', { n: i + 1 })),
          current: text.findIndex((track) => track.mode === 'showing'),
        },
      });
    };
    const listeners: [string, EventListener][] = [
      ['timeupdate', onTime],
      ['play', onPlay],
      ['pause', onPause],
      ['waiting', onWaiting],
      ['seeking', onWaiting],
      ['playing', onReady],
      ['canplay', onReady],
      ['seeked', onReady],
      ['volumechange', onVolume],
      ['progress', onProgress],
      ['ended', onEnded],
      ['error', onError],
      ['loadedmetadata', readNativeTracks],
    ];
    listeners.forEach(([event, listener]) => video.addEventListener(event, listener));
    video.textTracks?.addEventListener?.('addtrack', readNativeTracks);
    video.textTracks?.addEventListener?.('change', readNativeTracks);
    // Safari fills audioTracks after loadedmetadata; listen for the adds.
    type TrackList = {
      addEventListener?: (type: string, listener: () => void) => void;
      removeEventListener?: (type: string, listener: () => void) => void;
    };
    const audioTrackList = (video as HTMLVideoElement & { audioTracks?: TrackList }).audioTracks;
    audioTrackList?.addEventListener?.('addtrack', readNativeTracks);
    audioTrackList?.addEventListener?.('change', readNativeTracks);
    return () => {
      listeners.forEach(([event, listener]) => video.removeEventListener(event, listener));
      video.textTracks?.removeEventListener?.('addtrack', readNativeTracks);
      video.textTracks?.removeEventListener?.('change', readNativeTracks);
      audioTrackList?.removeEventListener?.('addtrack', readNativeTracks);
      audioTrackList?.removeEventListener?.('change', readNativeTracks);
    };
  }, [
    videoRef, hlsRef, mpegtsRef, remuxRef, onEnded, onPlaying, onRemuxDied, onNativeError,
    setPaused, setBuffering, setBufferedEnd, setTime, setTracks,
  ]);

  // Draw subtitles ourselves (track kept 'hidden': browser paints nothing) so size/style
  // follow the user's preference. Pick the active cue by scanning the track for one that
  // spans currentTime, rather than reading `activeCues`: on a track switch the browser
  // doesn't reliably re-activate the cue already spanning the playhead (its "time marches
  // on" step only re-evaluates as playback crosses a *new* cue boundary, and cues that
  // load a moment late never activate at all), which left the new track blank until a seek.
  // Scanning by time shows the right cue as soon as it's present, at the playhead.
  useEffect(() => {
    const video = videoRef.current!;
    let raf = 0;
    let last = '';
    const tick = () => {
      const list = video.textTracks;
      const now = video.currentTime;
      let text = '';
      for (let i = 0; i < list.length; i++) {
        const track = list[i];
        if (!track || track.mode === 'disabled') continue;
        const cues = track.cues;
        if (!cues || !cues.length) continue;
        const parts: string[] = [];
        for (let j = 0; j < cues.length; j++) {
          const cue = cues[j] as VTTCue;
          if (cue.startTime <= now && cue.endTime > now) parts.push(cue.text ?? '');
        }
        text = parts.join('\n');
        if (text) break;
      }
      if (text !== last) { last = text; setCueText(text); }
      raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [activeUrl, videoRef, setCueText]);

  return nativeTracks;
}
