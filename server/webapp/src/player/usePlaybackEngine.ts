// Picks and drives the playback engine for the mounted source, and keeps it attached across
// authorization changes.
//
// Engine per source: .m3u8 -> hls.js (native HLS on Safari); Xtream live .ts -> panel HLS
// variant then mpegts.js; other .ts -> mpegts.js; mp4/webm/mkv -> native <video>. A remux
// session overrides all of it: the file is re-served as a VOD HLS playlist.

import Hls from 'hls.js';
import mpegts from 'mpegts.js';
import { MutableRefObject, RefObject, useCallback, useEffect, useRef } from 'react';
import { api, PlaybackLease } from '../api';
import { snackbar } from '../components/Primitives';
import { t } from '../i18n';
import {
  captureMediaPosition, isTerminalPlaybackStatus, replaceMediaGrant, restoreMediaPosition,
} from './mediaGrant';
import {
  hlsVariantOf, resolveSource, sourceKind, Transport, TransportContext,
} from './mediaSource';
import { streamKind } from './playbackPolicy';
import { PlaybackStatusActions } from './playbackStatus';
import { RemuxController } from './useRemuxSession';

/** The panel serves an HLS variant of a live .ts under an extra query flag. */
const engineUrl = (url: string, hlsVariant: boolean) => (hlsVariant ? hlsVariantOf(url) : url);

export interface PlaybackEngine {
  hlsRef: MutableRefObject<Hls | null>;
  mpegtsRef: MutableRefObject<mpegts.Player | null>;
}

export function usePlaybackEngine(opts: {
  lease: PlaybackLease;
  leaseRef: MutableRefObject<PlaybackLease>;
  live: boolean;
  catchup: boolean;
  /** Watch-together live rides the room's shared relay instead of this viewer's own read. */
  roomLive: boolean;
  /** Hold the engine off entirely (provider check, room transition, remux preparing). */
  hold: boolean;
  contentId: string;
  downloadId: string | null;
  /** The source actually being played, and its grant-independent identity. */
  activeUrl: string;
  activeSourceKey: string;
  sourceKey: string;
  videoRef: RefObject<HTMLVideoElement | null>;
  remux: RemuxController;
  chosenTracks: MutableRefObject<{ audio: number; subs: number | null }>;
  actions: PlaybackStatusActions;
  onTerminate: (status: number) => void;
}): PlaybackEngine {
  const {
    lease, leaseRef, live, catchup, roomLive, hold, contentId, downloadId,
    activeUrl, activeSourceKey, sourceKey, videoRef, remux, chosenTracks, actions, onTerminate,
  } = opts;
  const { setError, setBuffering, setBufferedEnd, setAudioTranscoded, setTracks } = actions;

  const hlsRef = useRef<Hls | null>(null);
  const mpegtsRef = useRef<mpegts.Player | null>(null);
  const mpegtsReload = useRef<(() => void) | null>(null);
  const resumeSeek = useRef<(() => void) | null>(null);

  const remuxRef = remux.ref;
  const remuxAvailableRef = remux.availableRef;
  const { start: startRemux, markPlaying, forceTranscode } = remux;

  // Read fresh inside the engine's closures, so a reconnect picks up rotated URLs.
  const transportContextRef = useRef<() => TransportContext>(() => ({ lease }));
  transportContextRef.current = () => ({
    lease: leaseRef.current,
    remuxPlaylistUrl: remuxRef.current?.playlistUrl ?? null,
    downloadId,
  });
  const transportContext = useCallback(() => transportContextRef.current(), []);

  // Grant rotation changes authorization only. Keep the current engine and remux
  // attachment, reload the HLS manifest in place, and restore the exact VOD
  // position. Continuous MPEG-TS responses keep using their open transport; any
  // later engine reconstruction reads the fresh URLs from leaseRef.
  const previousGrant = useRef(lease.mediaGrant);
  useEffect(() => {
    if (previousGrant.current === lease.mediaGrant) return;
    previousGrant.current = lease.mediaGrant;
    const video = videoRef.current;
    if (!video) return;
    const currentRemux = remuxRef.current;
    const target = currentRemux
      ? replaceMediaGrant(currentRemux.playlistUrl, lease.mediaGrant)
      : lease.streamUrl;
    if (!target) return;
    const snapshot = captureMediaPosition(video);
    const hls = hlsRef.current;
    if (hls) {
      const restore = () => {
        restoreMediaPosition(video, snapshot, live);
        hls.off(Hls.Events.MANIFEST_PARSED, restore);
      };
      hls.on(Hls.Events.MANIFEST_PARSED, restore);
      const hlsVariant = !currentRemux && sourceKind(lease.streamUrl) === 'livets';
      hls.loadSource(engineUrl(target, hlsVariant));
      hls.startLoad(live ? -1 : snapshot.position);
      return () => hls.off(Hls.Events.MANIFEST_PARSED, restore);
    }
    if (!mpegtsRef.current && video.currentSrc) {
      const restore = () => restoreMediaPosition(video, snapshot, live);
      video.addEventListener('loadedmetadata', restore, { once: true });
      video.src = target;
      return () => video.removeEventListener('loadedmetadata', restore);
    }
  }, [lease.mediaGrant, lease.streamUrl, live, remuxRef, videoRef]);

  // ---- engine wiring ----
  const lastSource = useRef<string | undefined>(undefined);
  useEffect(() => {
    if (hold) return;
    const video = videoRef.current!;
    let triedTsFallback = false;
    let retryTimer: ReturnType<typeof setTimeout> | undefined;
    setError(null);
    setBuffering(true);
    setBufferedEnd(0);
    setAudioTranscoded(false);
    // A new file starts at normal speed; re-attaching to the same one keeps the viewer's choice.
    if (lastSource.current !== sourceKey) {
      lastSource.current = sourceKey;
      video.playbackRate = 1;
    }

    const kind = remuxRef.current ? streamKind(activeUrl) : sourceKind(leaseRef.current.streamUrl);

    const stopEngines = () => {
      hlsRef.current?.destroy(); hlsRef.current = null;
      mpegtsRef.current?.destroy(); mpegtsRef.current = null;
    };

    const readHlsTracks = (hls: Hls) => {
      // A remux playlist muxes in one audio track and serves subtitles as sidecars,
      // so its menus come from /remux/start, not from what hls.js sees here.
      if (remuxRef.current) return;
      setTracks({
        audio: {
          names: hls.audioTracks.map((track, i) => track.name || track.lang || t('player.audioN', { n: i + 1 })),
          current: hls.audioTrack,
        },
        subs: {
          names: hls.subtitleTracks.map((track, i) => track.name || track.lang || t('player.subtitlesN', { n: i + 1 })),
          current: hls.subtitleTrack,
        },
      });
    };

    // [relay] serves the room's shared upstream (watch-together live) instead of this viewer's
    // own; the AAC rescue is skipped there, since it would open a second, unshared connection.
    const playMpegts = (transport: Transport) => {
      if (!mpegts.getFeatureList().mseLivePlayback) {
        setError(t('player.mpegtsUnsupported'));
        return;
      }
      const source = resolveSource(transportContext(), transport);
      if (!source) {
        setError(t('player.decodeFailed'));
        return;
      }
      const transcoded = transport === 'transcode';
      const relay = transport === 'relay';
      let openedUrl = source.url;
      setAudioTranscoded(transcoded);
      const player = mpegts.createPlayer({ type: 'mpegts', isLive: true, url: openedUrl });
      mpegtsRef.current = player;
      player.attachMediaElement(video);
      player.load();
      player.play()?.catch(() => {});
      // Audio the browser can't decode: server re-muxes to AAC (video copied) and
      // retry once through the same engine, so it gets sound not a silent picture.
      const rescueAudio = () => {
        if (transcoded || relay || remuxAvailableRef.current !== true) return false;
        player.destroy();
        if (mpegtsRef.current === player) mpegtsRef.current = null;
        playMpegts('transcode');
        return true;
      };
      // mpegts.js fires ERROR for both network hiccups and undecodable codecs.
      // A live TS whose video decodes but whose audio doesn't plays silently yet
      // still errors: never cover a decoding picture; note the audio issue once and
      // only surface a hard failure when no picture arrives. Network errors get a bounded reload.
      const hasPicture = () => video.videoWidth > 0 && video.readyState >= 2;
      const reload = () => {
        const current = resolveSource(transportContext(), transport);
        if (current && current.url !== openedUrl) {
          openedUrl = current.url;
          player.destroy();
          if (mpegtsRef.current === player) mpegtsRef.current = null;
          playMpegts(transport);
          return;
        }
        try { player.unload(); player.load(); player.play()?.catch(() => {}); } catch { /* destroyed */ }
      };
      mpegtsReload.current = reload;
      let retries = 0;
      let lastErr = 0;
      let noticed = false;
      const noteAudio = () => { if (!noticed) { noticed = true; snackbar(t('player.audioUnsupported')); } };
      player.on(mpegts.Events.ERROR, (type: string, _detail: string, info: unknown) => {
        const response = info as { code?: number; status?: number; statusCode?: number } | null;
        const status = Number(response?.code ?? response?.status ?? response?.statusCode);
        if (type === mpegts.ErrorTypes.NETWORK_ERROR) {
          if (hasPicture()) return; // a frozen frame recovers via the watchdog
          if (isTerminalPlaybackStatus(status)) {
            onTerminate(status);
            return;
          }
          const now = performance.now();
          if (now - lastErr > 30_000) retries = 0;
          lastErr = now;
          if (retries < 3) { retries++; reload(); return; }
          setError(t('player.codecFailed'));
          return;
        }
        // Codec/format error: reload won't help. Try the audio rescue; else keep any
        // decoding picture, else give a late demux a moment before covering the screen.
        if (rescueAudio()) return;
        if (hasPicture()) { noteAudio(); return; }
        setTimeout(() => (hasPicture() ? noteAudio() : setError(t('player.codecFailed'))), 2000);
      });
    };

    const playHls = (target: string, hlsVariant = false) => {
      // hls.js first even on Safari: only it reports tracks/manifest state to the UI.
      // Native HLS is the no-MSE fallback (iOS).
      if (!Hls.isSupported()) {
        if (video.canPlayType('application/vnd.apple.mpegurl')) video.src = engineUrl(target, hlsVariant);
        else setError(t('player.hlsUnsupported'));
        return;
      }
      const session = remuxRef.current;
      // The remux playlist is the whole file; open at the resume/switch position.
      const startPosition = session && session.startAt > 0 ? session.startAt : -1;
      // 30s forward buffer; unbounded back buffer for VOD so seeking back replays from memory.
      const hls = new Hls({
        startPosition,
        lowLatencyMode: false,
        maxBufferLength: 30,
        maxMaxBufferLength: 30,
        backBufferLength: session ? Infinity : 90,
        manifestLoadingTimeOut: 20_000,
      });
      hlsRef.current = hls;
      // Keep the track 'hidden' (cues fire, browser draws nothing) so our overlay renders them.
      hls.subtitleDisplay = false;
      let mediaRecoveries = 0;
      let netRetries = 0;
      let lastMediaError = 0;
      // A media error every now and then (e.g. resuming after the tab was backgrounded and the
      // decoder was suspended) shouldn't burn the recovery budget forever: once fragments flow
      // again for a few seconds, restore it so each incident gets a fresh attempt.
      hls.on(Hls.Events.FRAG_BUFFERED, () => {
        if (performance.now() - lastMediaError > 5000) { mediaRecoveries = 0; netRetries = 0; }
      });
      hls.loadSource(engineUrl(target, hlsVariant));
      hls.attachMedia(video);
      hls.on(Hls.Events.ERROR, (_e, data) => {
        if (!data.fatal) return;
        const status = data.response?.code;
        if (isTerminalPlaybackStatus(status)) {
          onTerminate(status);
          return;
        }
        // A copied HEVC the browser claimed to support but can't actually decode: force a
        // transcode and re-prepare the session at the current spot.
        if (remuxRef.current?.nativeCopy && data.type === Hls.ErrorTypes.MEDIA_ERROR && mediaRecoveries < 1) {
          forceTranscode.current = true;
          startRemux(remuxRef.current.audio, video.currentTime);
          return;
        }
        // recoverMediaError, then (provider HLS only) swapAudioCodec and recover, then give up.
        // Never swap on the remux: its audio is AAC-LC we encoded, so a swap to HE-AAC decodes
        // as distorted audio that sticks until the buffer is rebuilt.
        if (data.type === Hls.ErrorTypes.MEDIA_ERROR && mediaRecoveries < 2) {
          lastMediaError = performance.now();
          if (mediaRecoveries++ > 0 && !remuxRef.current) hls.swapAudioCodec();
          hls.recoverMediaError();
          return;
        }
        if (data.type === Hls.ErrorTypes.NETWORK_ERROR && netRetries < 4) {
          netRetries++;
          retryTimer = setTimeout(() => hls.startLoad(), 1000 * netRetries);
          return;
        }
        if (kind === 'livets' && !triedTsFallback) {
          // The panel may not serve an HLS variant - fall back to raw TS.
          triedTsFallback = true;
          stopEngines();
          playMpegts('proxy');
        } else {
          setError(t('player.failedDetail', { detail: data.details || data.type }));
        }
      });
      hls.on(Hls.Events.MANIFEST_PARSED, () => {
        readHlsTracks(hls);
        markPlaying();
      });
      // Track lists exist only once these fire; setting the pick here pre-empts
      // hls.js's default selection, so picks survive seek re-anchors.
      hls.on(Hls.Events.AUDIO_TRACKS_UPDATED, () => {
        const chosen = chosenTracks.current;
        if (chosen.audio >= 0 && chosen.audio < hls.audioTracks.length) hls.audioTrack = chosen.audio;
        readHlsTracks(hls);
      });
      hls.on(Hls.Events.SUBTITLE_TRACKS_UPDATED, () => {
        const chosen = chosenTracks.current;
        if (chosen.subs != null && chosen.subs < hls.subtitleTracks.length) hls.subtitleTrack = chosen.subs;
        readHlsTracks(hls);
      });
      hls.on(Hls.Events.AUDIO_TRACK_SWITCHED, () => readHlsTracks(hls));
      hls.on(Hls.Events.SUBTITLE_TRACK_SWITCH, () => readHlsTracks(hls));
    };

    // Watch-together live rides the room's shared upstream (one provider seat) via the relay,
    // played as a transport stream; solo live keeps its own connection through the proxy. The
    // relay tees raw TS, so a playlist-only (m3u8) channel can't use it and stays on HLS.
    if (roomLive && kind !== 'hls') playMpegts('relay');
    else if (kind === 'hls') playHls(activeUrl);
    else if (kind === 'livets') playHls(activeUrl, true);
    else if (kind === 'ts') playMpegts('proxy');
    else video.src = activeUrl;

    if (!live && !catchup && !remuxRef.current) {
      // Resume VOD position (remux already starts there); catch-up never resumes.
      api.resumeAll().then((points) => {
        const point = points.find((x) => x.contentId === contentId);
        if (point && point.positionMs >= 10_000) {
          const apply = () => { video.currentTime = point.positionMs / 1000; };
          if (video.readyState >= 1) apply();
          else {
            resumeSeek.current = apply;
            video.addEventListener('loadedmetadata', apply, { once: true });
          }
        }
      }).catch(() => {});
    }

    const saveResume = () => {
      if (live || catchup) return;
      const duration = remuxRef.current?.duration ?? video.duration;
      if (!duration || !isFinite(duration)) return;
      api.saveResume(
        contentId,
        Math.floor(video.currentTime * 1000),
        Math.floor(duration * 1000),
      ).catch(() => {});
    };
    const resumeTimer = live || catchup ? undefined : setInterval(saveResume, 5000);

    // Live streams can silently stall; if position freezes while playing, kick the engine.
    let watchdog: ReturnType<typeof setInterval> | undefined;
    if (live) {
      let lastPos = -1;
      let stalledFor = 0;
      watchdog = setInterval(() => {
        if (video.paused || video.readyState === 0) { stalledFor = 0; return; }
        if (video.currentTime !== lastPos) { lastPos = video.currentTime; stalledFor = 0; return; }
        stalledFor += 4;
        if (stalledFor < 12) return;
        stalledFor = 0;
        if (hlsRef.current) {
          hlsRef.current.stopLoad();
          hlsRef.current.startLoad();
        } else if (mpegtsRef.current) {
          mpegtsReload.current?.();
        } else if (video.currentSrc) {
          video.load();
          video.play().catch(() => {});
        }
      }, 4000);
    }

    return () => {
      clearInterval(resumeTimer);
      clearInterval(watchdog);
      clearTimeout(retryTimer);
      saveResume();
      stopEngines();
      if (resumeSeek.current) video.removeEventListener('loadedmetadata', resumeSeek.current);
      resumeSeek.current = null;
      video.pause();
      video.removeAttribute('src');
      video.load();
    };
    // Keyed on grant-independent source identity only: a rotated grant must not tear the
    // engine down. The rotation effect above re-authorizes the attachment in place, and any
    // later rebuild reads the fresh URLs through leaseRef/transportContext.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [
    sourceKey, activeSourceKey, live, catchup, hold, roomLive, contentId,
    remux.session?.id, remux.session?.startAt, remuxRef, remuxAvailableRef,
    leaseRef, videoRef, chosenTracks, transportContext, startRemux, markPlaying, forceTranscode,
    onTerminate, setError, setBuffering, setBufferedEnd, setAudioTranscoded, setTracks,
  ]);

  // PiP/fullscreen end when the player closes, not on engine swaps.
  useEffect(() => () => {
    if (document.pictureInPictureElement) document.exitPictureInPicture().catch(() => {});
    if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
  }, [lease.id]);

  return { hlsRef, mpegtsRef };
}
