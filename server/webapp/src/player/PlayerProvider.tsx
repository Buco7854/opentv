// Fullscreen playback overlay; navigating away stops playback.
//
// This file owns the lease and wires the player's parts together: the remux session
// (useRemuxSession), the engine driving the media element (usePlaybackEngine), what the
// element reports back (useMediaElement), and the chrome (PlayerChrome). Engine choice per
// source and the remux policy live in those modules, not here.

import {
  ReactNode, useCallback, useEffect, useMemo, useRef, useState,
} from 'react';
import {
  api, ApiError, Channel, MediaGrant, PlaybackLease, SessionCommandInput,
} from '../api';
import { browserApiHttp } from '../api/http';
import { GuideSheet } from '../components/GuideSheet';
import { IconBtn, toast } from '../components/Primitives';
import { errorMessage } from '../errors';
import { useSessionReporter } from './useSessionReporter';
import { useWatchTogether, WatchTogetherSheet } from './WatchTogether';
import { t } from '../i18n';
import { prefs } from '../preferences';
import { MenuSheet, SubtitleStyle, SubtitleStyleSheet } from './PlaybackSheets';
import { playbackCapabilities, reportedEngine } from './playbackPolicy';
import { playbackSource, sourceKind } from './mediaSource';
import { isTerminalPlaybackStatus, mediaSourceIdentity, replaceMediaGrant } from './mediaGrant';
import { usePlaybackStatus } from './playbackStatus';
import { useRemuxSession } from './useRemuxSession';
import { usePlaybackEngine } from './usePlaybackEngine';
import { useMediaElement } from './useMediaElement';
import { usePlayerShortcuts } from './usePlayerShortcuts';
import {
  PlayerControls, PlayerMenu, PlayerOverlays, useChromeVisibility,
} from './PlayerChrome';

const SPEEDS = [0.5, 0.75, 1, 1.25, 1.5, 2];
const SCALE_MODES = ['fit', 'zoom', 'stretch'] as const;
const GRANT_ROTATION_ATTEMPTS = 5;
const GRANT_RETRY_MS = 2000;
const PENDING_SEEK_TIMEOUT_MS = 8000;
const CLIENT_CAPABILITIES = playbackCapabilities(
  typeof MediaSource === 'undefined' ? undefined : MediaSource,
);

const withMediaGrant = (lease: PlaybackLease, issued: MediaGrant): PlaybackLease => ({
  ...lease,
  mediaGrant: issued.token,
  mediaGrantExpiresAtMs: issued.expiresAtMs,
  streamUrl: replaceMediaGrant(lease.streamUrl, issued.token),
  sharedHlsUrl: replaceMediaGrant(lease.sharedHlsUrl, issued.token),
  relayUrl: replaceMediaGrant(lease.relayUrl, issued.token),
  transcodeUrl: replaceMediaGrant(lease.transcodeUrl, issued.token),
  remuxStartUrl: replaceMediaGrant(lease.remuxStartUrl, issued.token)!,
});

export interface PlayRequest {
  contentId: string;
  title: string;
  channelId?: number;
  live?: boolean;
  tvgId?: string | null;
  hasGuide?: boolean;
  mode?: 'play' | 'catchup' | 'download';
  catchupStartMs?: number;
  catchupDurationMs?: number;
  downloadId?: string;
  kind?: 'live' | 'movie' | 'series' | 'catchup' | 'download';
  logo?: string | null;
}

/** Isolates a known mpegts.js teardown race while the player feature is mounted. */
export function PlaybackErrorBoundary({ children }: { children: ReactNode }) {
  // Swallow the benign mpegts.js teardown race: destroying a player mid-stream
  // lets queued demux callbacks emit on a now-null emitter (TypeError naming null+emit).
  useEffect(() => {
    const swallow = (e: PromiseRejectionEvent) => {
      const message = (e.reason as Error | undefined)?.message ?? '';
      if (e.reason instanceof TypeError && /emit/.test(message) && /null/i.test(message)) {
        e.preventDefault();
      }
    };
    window.addEventListener('unhandledrejection', swallow);
    return () => window.removeEventListener('unhandledrejection', swallow);
  }, []);
  return <>{children}</>;
}

export function PlayerSurface(props: {
  request: PlayRequest;
  onClose: () => void;
  onPlayCatchup: (channelId: number, startMs: number, endMs: number) => void;
}) {
  const { request, onClose } = props;
  const [lease, setLease] = useState<PlaybackLease | null>(null);
  const [leaseError, setLeaseError] = useState<string | null>(null);
  const leaseRef = useRef<PlaybackLease | null>(lease);
  leaseRef.current = lease;
  const grantRefresh = useRef<{ leaseId: string; request: Promise<MediaGrant> } | null>(null);

  const requestMediaGrant = useCallback((leaseId: string): Promise<MediaGrant> => {
    const existing = grantRefresh.current;
    if (existing?.leaseId === leaseId) return existing.request;
    const request = api.refreshMediaGrant(leaseId).then((issued) => {
      const current = leaseRef.current;
      if (current?.id === leaseId) leaseRef.current = withMediaGrant(current, issued);
      setLease((value) => value?.id === leaseId ? withMediaGrant(value, issued) : value);
      return issued;
    });
    const tracked = request.finally(() => {
      if (grantRefresh.current?.request === tracked) grantRefresh.current = null;
    });
    grantRefresh.current = { leaseId, request: tracked };
    return tracked;
  }, []);

  const refreshCurrentMediaGrant = useCallback(() => {
    const current = leaseRef.current;
    return current
      ? requestMediaGrant(current.id)
      : Promise.reject(new Error('Playback lease is no longer active'));
  }, [requestMediaGrant]);

  useEffect(() => {
    let cancelled = false;
    let created: PlaybackLease | null = null;
    setLease(null);
    setLeaseError(null);
    api.createPlayback({
      contentId: request.contentId,
      mode: request.mode ?? 'play',
      catchupStartMs: request.catchupStartMs,
      catchupDurationMs: request.catchupDurationMs,
      downloadId: request.downloadId,
      capabilities: CLIENT_CAPABILITIES,
    }).then((next) => {
      created = next;
      if (cancelled) api.playbackEnd(next.id);
      else setLease(next);
    }).catch((cause: unknown) => {
      if (cancelled) return;
      const error = cause as ApiError;
      if (isTerminalPlaybackStatus(error.status)) onClose();
      else setLeaseError(errorMessage(cause));
    });
    let unloading = false;
    const onPageHide = () => { unloading = true; };
    window.addEventListener('pagehide', onPageHide);
    return () => {
      cancelled = true;
      window.removeEventListener('pagehide', onPageHide);
      if (created && !unloading) api.playbackEnd(created.id);
    };
  }, [
    request.contentId, request.mode, request.catchupStartMs,
    request.catchupDurationMs, request.downloadId, onClose,
  ]);

  useEffect(() => {
    if (!lease) return;
    let timer = 0;
    let attempt = 0;
    let active = true;
    const rotate = () => {
      requestMediaGrant(lease.id).catch((cause: unknown) => {
        if (!active) return;
        const error = cause as ApiError;
        if (isTerminalPlaybackStatus(error.status)) {
          onClose();
          return;
        }
        attempt += 1;
        const expired = Date.now() >= lease.mediaGrantExpiresAtMs;
        if (expired || attempt > GRANT_ROTATION_ATTEMPTS) setLeaseError(errorMessage(cause));
        else timer = window.setTimeout(rotate, GRANT_RETRY_MS * 2 ** (attempt - 1));
      });
    };
    timer = window.setTimeout(
      rotate,
      Math.max(0, lease.mediaGrantExpiresAtMs - Date.now() - 60_000),
    );
    return () => {
      active = false;
      window.clearTimeout(timer);
    };
  }, [lease, onClose, requestMediaGrant]);

  if (leaseError) {
    return (
      <div className="player-frame">
        <div className="player-error">
          <h3>{t('player.errorTitle')}</h3>
          <p>{leaseError}</p>
          <button className="btn tonal w-auto" onClick={onClose}>{t('common.close')}</button>
        </div>
      </div>
    );
  }
  if (!lease) {
    return (
      <div className="player-frame" role="status" aria-label={t('common.working')}>
        <div className="player-spinner" aria-hidden />
      </div>
    );
  }
  return (
    <LeasedPlayerSurface
      {...props}
      lease={lease}
      refreshMediaGrant={refreshCurrentMediaGrant}
    />
  );
}

function LeasedPlayerSurface({
  request, lease, onClose, onPlayCatchup, refreshMediaGrant,
}: {
  request: PlayRequest;
  lease: PlaybackLease;
  onClose: () => void;
  onPlayCatchup: (channelId: number, startMs: number, endMs: number) => void;
  refreshMediaGrant: () => Promise<MediaGrant>;
}) {
  const { title, channelId, live = false, tvgId, hasGuide } = request;
  const catchup = request.mode === 'catchup';
  const direct = request.mode === 'download';
  const downloadId = request.downloadId ?? null;
  const sourceKey = mediaSourceIdentity(lease.streamUrl ?? lease.remuxStartUrl);
  const leaseRef = useRef(lease);
  leaseRef.current = lease;

  const rootRef = useRef<HTMLDivElement>(null);
  const videoRef = useRef<HTMLVideoElement>(null);

  const { status, actions } = usePlaybackStatus();
  const { setError, setTracks, setCueText } = actions;

  const [menu, setMenu] = useState<PlayerMenu | null>(null);
  const [wtMenu, setWtMenu] = useState(false);
  const [guideChannel, setGuideChannel] = useState<Channel | null>(null);
  const [scale, setScale] = useState(prefs.resizeMode);
  const [subStyle, setSubStyle] = useState<SubtitleStyle>({
    scale: prefs.subScale, style: prefs.subStyle, bold: prefs.subBold,
  });
  const { uiVisible, setUiVisible, poke } = useChromeVisibility(videoRef);

  /* Full-file target of an in-flight seek; the bar shows this until playback reaches it. */
  const [pendingSeek, setPendingSeek] = useState<number | null>(null);
  // Held-seek target for the seek closures, so a relative nudge starts from where
  // the bar is heading, not stale media time.
  const pendingSeekRef = useRef(pendingSeek);
  pendingSeekRef.current = pendingSeek;

  // Explicit track picks, kept across engine restarts (seek re-anchors rebuild hls.js).
  // audio -1 = untouched; subs null = untouched, -1 = explicitly off.
  const chosenTracks = useRef<{ audio: number; subs: number | null }>({ audio: -1, subs: null });
  useEffect(() => { chosenTracks.current = { audio: -1, subs: null }; }, [sourceKey]);

  const finishPlayback = useCallback((httpStatus: number) => {
    toast(httpStatus === 403 ? t('player.forbidden') : t('player.revoked'), { tone: 'error' });
    if (httpStatus === 401) browserApiHttp.notifyUnauthorized();
    onClose();
  }, [onClose]);
  const grantRecovery = useRef<Promise<boolean> | null>(null);
  const recoverMediaGrant = useCallback((): Promise<boolean> => {
    if (grantRecovery.current) return grantRecovery.current;
    const attempt = refreshMediaGrant()
      .then((issued) => {
        leaseRef.current = withMediaGrant(leaseRef.current, issued);
        return true;
      })
      .catch((cause: unknown) => {
        finishPlayback((cause as ApiError).status ?? 410);
        return false;
      })
      .finally(() => {
        if (grantRecovery.current === attempt) grantRecovery.current = null;
      });
    grantRecovery.current = attempt;
    return attempt;
  }, [finishPlayback, leaseRef, refreshMediaGrant]);
  const terminatePlayback = useCallback((httpStatus: number) => {
    if (httpStatus === 410) {
      void recoverMediaGrant();
      return;
    }
    finishPlayback(httpStatus);
  }, [finishPlayback, recoverMediaGrant]);

  // Non-live files (VOD, downloads, raw-TS VOD) and catch-up go through the remux; live `.ts`
  // is excluded. Watch-together needs the same fact: a same-variant viewer shares its read.
  const remuxEligible = !live;
  // Whether this stream draws on the provider at all (downloads are local).
  const providerBacked = !direct;
  const liveKind = sourceKind(lease.streamUrl);
  // A room may retry a provider-full result when it has a shared transport or may negotiate a
  // shared remux. Fully capable direct-play VOD is the deliberate exception: after negotiation
  // says no remux is needed, each member retains a seat and the newcomer may receive 429.
  const sharesRoomRead = remuxEligible || (
    liveKind === 'hls' ? lease.sharedHlsUrl != null : lease.relayUrl != null
  );

  // Filled by useSessionReporter with a sender over its live socket, so watch-together sync
  // rides that socket in real time (with a POST fallback) instead of a request per event.
  const wsSend = useRef<((command: SessionCommandInput) => boolean) | null>(null);
  // startRemux is defined below; the room-audio command only fires later.
  const startRemuxRef = useRef<(audio: number, startAt: number) => void>(() => {});
  const wt = useWatchTogether({
    selfId: lease.id,
    video: videoRef,
    active: !status.error,
    live,
    sharesRoomRead,
    contentId: request.contentId,
    send: wsSend,
    // A controller changed the room's shared track: re-request the remux with it.
    onRoomAudio: (index) => {
      if (!remuxEligible) return;
      chosenTracks.current.audio = index;
      startRemuxRef.current(index, videoRef.current?.currentTime ?? 0);
    },
  });
  // Room rights read from inside pickAudio without rebuilding it each roster change.
  const wtRef = useRef({ inRoom: false, canControl: false });
  wtRef.current = { inRoom: wt.inRoom, canControl: wt.canControl };
  // Live watched together uses the source-appropriate shared transport: untouched HLS resources
  // for an HLS channel, or the byte tee relay for raw TS.
  const roomLive = live && wt.inRoom;

  const remux = useRemuxSession({
    lease,
    leaseRef,
    contentId: request.contentId,
    sourceKey,
    catchup,
    eligible: remuxEligible,
    checking: wt.checking,
    blocked: wt.blocked,
    inRoom: wt.inRoom,
    trackMenuOpen: menu === 'audio' || menu === 'subs',
    videoRef,
    chosenTracks,
    actions,
    recoverMediaGrant,
    onTerminate: terminatePlayback,
  });
  startRemuxRef.current = remux.start;

  const activeSource = playbackSource({
    lease,
    remuxPlaylistUrl: remux.session?.playlistUrl ?? null,
    downloadId,
  });
  const activeUrl = activeSource?.url ?? '';
  const activeSourceKey = mediaSourceIdentity(activeUrl);
  const activeDirect = remux.session ? true : direct;
  const activeUrlRef = useRef(activeUrl);
  activeUrlRef.current = activeUrl;

  // Hold the engine while the provider check is in flight, and keep it off (with a clear
  // message) when the provider is full - so a blocked stream never plays in the background
  // or steals the connection from whoever is already watching.
  // The alone/together choice (and the check that precedes it) holds any content, downloads
  // included - there's no seat there, but the viewer still chooses whether to sync. A full
  // provider only blocks provider-backed streams.
  const holdForChoice = wt.checking || wt.choosing;
  const holdForConnection = holdForChoice || (providerBacked && wt.blocked);
  const holdEngine = wt.refusal != null || wt.transitioning || holdForConnection || (remuxEligible && !remux.session &&
    (remux.available == null ||
      (remux.available && (remux.state === 'idle' || remux.state === 'loading'))));
  useEffect(() => {
    // Don't surface the limit error while the viewer is still choosing alone vs together - only
    // once they've picked alone and the provider really is full.
    if (wt.blocked && !wt.choosing) setError((old) => old ?? t('player.connectionLimit'));
    else if (!wt.blocked) setError((old) => (old === t('player.connectionLimit') ? null : old));
  }, [wt.blocked, wt.choosing, setError]);

  // Native <video> failures give no reason; probe the URL to surface an upstream
  // HTTP error instead of a misleading "cannot decode".
  const diagnoseNativeError = useCallback(async () => {
    const url = activeUrlRef.current;
    try {
      const response = await fetch(url, { headers: { Range: 'bytes=0-0' } });
      if (!response.ok) {
        if (response.status === 409) {
          setError(t('error.sameContentAlreadyPlaying'));
          return;
        }
        if (response.status === 429) {
          setError(t('player.connectionLimit'));
          return;
        }
        if (isTerminalPlaybackStatus(response.status)) {
          terminatePlayback(response.status);
          return;
        }
        let message = `HTTP ${response.status}`;
        try { message = ((await response.json()) as { message?: string }).message || message; } catch { /* not json */ }
        setError(t('player.upstreamFailed', { message }));
        return;
      }
      try { await response.body?.cancel(); } catch { /* stream already closed */ }
    } catch { /* network failed; keep the generic message */ }
    setError((old) => old ?? t('player.decodeFailed'));
  }, [setError, terminatePlayback]);

  const { hlsRef, mpegtsRef } = usePlaybackEngine({
    lease,
    leaseRef,
    live,
    catchup,
    roomLive,
    hold: holdEngine,
    contentId: request.contentId,
    downloadId,
    activeUrl,
    activeSourceKey,
    sourceKey,
    videoRef,
    remux,
    chosenTracks,
    actions,
    recoverMediaGrant,
    onTerminate: terminatePlayback,
  });

  const nativeTracks = useMediaElement({
    videoRef,
    hlsRef,
    mpegtsRef,
    remuxRef: remux.ref,
    activeUrl,
    actions,
    onEnded: onClose,
    onPlaying: remux.markPlaying,
    onRemuxDied: remux.markDied,
    onNativeError: diagnoseNativeError,
  });

  // ---- viewer actions ----

  const pickAudio = useCallback((index: number) => {
    // In a room the audio is shared: only a controller can change it, and the switch comes back
    // as a room-audio command that re-requests the remux for everyone, so don't touch local state.
    if (remux.ref.current && wtRef.current.inRoom) {
      if (wtRef.current.canControl) api.roomAudio(lease.id, index).catch(() => {});
      return;
    }
    chosenTracks.current.audio = index;
    setTracks((old) => ({ ...old, audio: { ...old.audio, current: index } }));
    if (remux.ref.current) {
      // Switching audio re-requests the playlist muxed with that track, reopened at
      // the current position.
      remux.start(index, videoRef.current!.currentTime);
      return;
    }
    if (hlsRef.current) { hlsRef.current.audioTrack = index; return; }
    nativeTracks.current.audio.forEach((track, i) => { track.enabled = i === index; });
  }, [lease.id, remux.ref, remux.start, hlsRef, nativeTracks, setTracks]);

  const pickSubtitle = useCallback((index: number) => {
    chosenTracks.current.subs = index;
    setTracks((old) => ({ ...old, subs: { ...old.subs, current: index } }));
    if (index < 0) setCueText('');
    // hls.js aligns the cues to the video's timeline. Switching straight to another track
    // leaves the already-buffered stretch on the old track, so the new one shows nothing
    // until playback passes it; deselect first so it reloads from the current position.
    if (hlsRef.current) {
      const hls = hlsRef.current;
      hls.subtitleTrack = -1;
      if (index >= 0) requestAnimationFrame(() => { if (hlsRef.current === hls) hls.subtitleTrack = index; });
      return;
    }
    // 'hidden' not 'showing': cues fire for our overlay without browser rendering.
    nativeTracks.current.text.forEach((track, i) => { track.mode = i === index ? 'hidden' : 'disabled'; });
  }, [hlsRef, nativeTracks, setCueText, setTracks]);

  const togglePlay = useCallback(() => {
    const video = videoRef.current!;
    if (video.paused) video.play().catch(() => {});
    else video.pause();
  }, []);

  // hls.js (remux) and native players both seek in place; the whole file is addressable.
  const seekTo = useCallback((target: number) => {
    const clamped = Math.max(0, target);
    setPendingSeek(clamped);
    videoRef.current!.currentTime = clamped;
  }, []);

  const seekBy = useCallback((delta: number) => {
    // Start from the held target if any, else media time: a seek still buffering
    // would otherwise nudge from a stale position.
    const base = pendingSeekRef.current ?? videoRef.current!.currentTime;
    seekTo(base + delta);
  }, [seekTo]);

  const toggleMute = useCallback(() => {
    const video = videoRef.current!;
    video.muted = !video.muted;
  }, []);

  const changeVolume = useCallback((level: number) => {
    const video = videoRef.current!;
    video.volume = Math.min(1, Math.max(0, level));
    if (video.volume > 0 && video.muted) video.muted = false;
  }, []);

  const toggleFullscreen = useCallback(() => {
    if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
    else rootRef.current?.requestFullscreen().catch(() => {});
  }, []);

  // Rotation lock only meaningful on touch devices.
  const canRotate = useMemo(
    () => typeof screen !== 'undefined' && !!screen.orientation
      && 'lock' in screen.orientation && window.matchMedia('(pointer: coarse)').matches,
    [],
  );
  const rotateScreen = useCallback(async () => {
    try {
      if (!document.fullscreenElement) await rootRef.current?.requestFullscreen();
      const landscape = screen.orientation.type.startsWith('landscape');
      await (screen.orientation as unknown as { lock: (o: string) => Promise<void> })
        .lock(landscape ? 'portrait' : 'landscape');
    } catch { /* not supported here */ }
  }, []);

  usePlayerShortcuts({
    title, live, menu, guideOpen: !!guideChannel, watchTogetherOpen: wtMenu || wt.choosing, videoRef,
    onClose, poke, togglePlay, toggleMute, seekBy, changeVolume, toggleFullscreen,
  });

  // ---- derived playback view ----

  const fullDuration = remux.session?.duration ?? status.time.duration;
  useEffect(() => { setPendingSeek(null); }, [sourceKey, activeSourceKey, status.error]);
  // Release the held seek target once media reaches it (within ~1.5s).
  useEffect(() => {
    if (pendingSeek == null) return;
    const position = status.time.position;
    if (isFinite(position) && Math.abs(position - pendingSeek) < 1.5) {
      setPendingSeek(null);
      return;
    }
    const timer = window.setTimeout(() => setPendingSeek(null), PENDING_SEEK_TIMEOUT_MS);
    return () => window.clearTimeout(timer);
  }, [status.time.position, pendingSeek]);

  // Report playback to the activity dashboard. Engine mirrors the wiring module's choice;
  // remux takes precedence since it re-serves the source as HLS.
  const reportEngine = reportedEngine(liveKind, roomLive, remux.session != null);
  useSessionReporter(lease.id, {
    title,
    kind: request.kind ?? (catchup ? 'catchup' : live ? 'live' : 'movie'),
    logo: request.logo ?? null,
    live,
    durationSec: fullDuration,
    engine: reportEngine,
    direct: activeDirect,
    audioTranscoded: status.audioTranscoded,
    // Engine is held while ffmpeg probes the file; report "preparing" so the
    // dashboard shows that, not the transient pre-remux (proxied) mode.
    preparing: holdEngine,
    remuxId: remux.session?.id ?? null,
  }, videoRef, wt.onCommand, wsSend, () => {
    toast(t('player.revoked'), { tone: 'error' });
    onClose();
  });

  // Buffering means we're waiting on data (initial load, a seek, a stall) - show the spinner even
  // while paused, so a loading player never shows a resting play icon. A deliberate pause clears
  // buffering (the media has data), so it correctly shows play then.
  const busy = holdEngine || remux.state === 'loading' || status.buffering || wt.loading;
  const visibleError = status.error ?? wt.refusal;
  const tracksEmptyText =
    remux.state === 'loading' || holdEngine ? t('player.remuxPreparing')
      : remux.session || remux.state === 'none' ? t('player.noExtraTracks')
        : t('player.noTracks');

  const chromeTarget = (e: { target: EventTarget }) => {
    const target = e.target as HTMLElement;
    // The frame, the video, or the chrome overlay's empty area.
    return target === rootRef.current || target === videoRef.current
      || target.classList.contains('player-ui');
  };

  return (
    <div ref={rootRef} className={`player-frame${uiVisible ? '' : ' chrome-hidden'}`}
         onPointerMove={poke}
         onClick={(e) => {
           if (chromeTarget(e)) {
             if (uiVisible) setUiVisible(false); else poke();
           }
         }}
         onDoubleClick={(e) => {
           if (!chromeTarget(e)) return;
           const zone = e.clientX / window.innerWidth;
           if (!live && zone < 1 / 3) { seekBy(-prefs.seekSeconds); poke(); }
           else if (!live && zone > 2 / 3) { seekBy(prefs.seekSeconds); poke(); }
           else toggleFullscreen();
         }}>
      <video ref={videoRef} autoPlay playsInline
             className={scale === 'zoom' ? 'zoom' : scale === 'stretch' ? 'stretch' : undefined} />

      <PlayerOverlays
        error={visibleError}
        busy={busy}
        uiVisible={uiVisible}
        cueText={status.cueText}
        subStyle={subStyle}
        wt={wt}
        onClose={onClose}
        onOpenWatchTogether={() => setWtMenu(true)}
      />

      {!visibleError && !wt.choosing && (
        <PlayerControls
          title={title}
          live={live}
          catchup={catchup}
          channelId={channelId}
          guideAvailable={hasGuide ?? !!tvgId}
          wt={wt}
          uiVisible={uiVisible}
          paused={status.paused}
          busy={busy}
          duration={fullDuration}
          // Bar position: the held seek target, else live media.
          position={pendingSeek ?? status.time.position}
          bufferedEnd={status.bufferedEnd}
          canRotate={canRotate}
          onTogglePlay={togglePlay}
          onSeekBy={seekBy}
          onSeekTo={seekTo}
          onOpenMenu={setMenu}
          onOpenWatchTogether={() => setWtMenu(true)}
          onGuideChannel={setGuideChannel}
          onRotate={rotateScreen}
          onPip={() => videoRef.current?.requestPictureInPicture().catch(() => {})}
          onToggleFullscreen={toggleFullscreen}
          onClose={onClose}
          poke={poke}
        />
      )}

      {menu === 'speed' && (
        <MenuSheet title={t('player.speed')} options={SPEEDS.map((s) => `${s}×`)}
                   selected={SPEEDS.indexOf(videoRef.current?.playbackRate ?? 1)}
                   onPick={(i) => { videoRef.current!.playbackRate = SPEEDS[i] ?? 1; }}
                   onDismiss={() => setMenu(null)} container={rootRef.current} />
      )}
      {menu === 'scale' && (
        <MenuSheet title={t('player.scaling')}
                   options={[t('settings.scaleFit'), t('settings.scaleZoom'), t('settings.scaleStretch')]}
                   selected={SCALE_MODES.indexOf(scale as typeof SCALE_MODES[number])}
                   onPick={(i) => {
                     const mode = SCALE_MODES[i] ?? 'fit';
                     prefs.resizeMode = mode;
                     setScale(mode);
                   }}
                   onDismiss={() => setMenu(null)} container={rootRef.current} />
      )}
      {menu === 'audio' && (
        <MenuSheet title={t('player.audio')} options={status.tracks.audio.names}
                   selected={status.tracks.audio.current}
                   onPick={pickAudio} emptyText={tracksEmptyText}
                   onDismiss={() => setMenu(null)} container={rootRef.current} />
      )}
      {menu === 'subs' && (
        <MenuSheet title={t('player.subtitles')}
                   options={status.tracks.subs.names.length ? [t('player.off'), ...status.tracks.subs.names] : []}
                   selected={status.tracks.subs.current + 1}
                   onPick={(i) => pickSubtitle(i - 1)} emptyText={tracksEmptyText}
                   headerAction={
                     <IconBtn name="settings" label={t('player.subtitleStyle')} className="self-center"
                              onClick={() => setMenu('subStyle')} />
                   }
                   onDismiss={() => setMenu(null)} container={rootRef.current} />
      )}
      {menu === 'subStyle' && (
        <SubtitleStyleSheet
          value={subStyle}
          onChange={(next) => {
            setSubStyle(next);
            prefs.subScale = next.scale; prefs.subStyle = next.style; prefs.subBold = next.bold;
          }}
          onDismiss={() => setMenu('subs')} container={rootRef.current} />
      )}
      {guideChannel && (
        <GuideSheet
          channel={guideChannel}
          container={rootRef.current}
          onDismiss={() => setGuideChannel(null)}
          onPlayCatchup={(cid, startMs, endMs) => { setGuideChannel(null); onPlayCatchup(cid, startMs, endMs); }}
        />
      )}
      {wtMenu && (
        <WatchTogetherSheet wt={wt} container={rootRef.current} onDismiss={() => setWtMenu(false)} />
      )}
    </div>
  );
}
