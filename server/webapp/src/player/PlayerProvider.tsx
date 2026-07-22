// Fullscreen playback overlay; navigating away stops playback.
// Engine per source: .m3u8 -> hls.js (native HLS on Safari); Xtream live .ts ->
// panel HLS variant then mpegts.js; other .ts -> mpegts.js; mp4/webm/mkv -> native <video>.

import Hls from 'hls.js';
import mpegts from 'mpegts.js';
import {
  createContext, ReactNode, useCallback, useContext, useEffect, useMemo, useRef, useState,
} from 'react';
import { useNavigate } from 'react-router-dom';
import { api, ApiError, Channel, GuideEntry, prefs, ResumePoint, streamUrl, transcodeUrl } from '../api';
import { GuideSheet } from '../components/GuideSheet';
import { Icon } from '../components/Icons';
import { IconBtn, Segmented, Sheet, snackbar } from '../components/Primitives';
import { tabSessionId, useSessionReporter } from './useSessionReporter';
import { useWatchTogether, WatchTogetherModals } from './WatchTogether';
import { t } from '../i18n';

export interface PlayRequest {
  url: string;
  title: string;
  channelId?: number;
  live?: boolean;
  tvgId?: string | null;
  hasGuide?: boolean;
  catchup?: boolean;
  /** Served by this server (downloaded file): skip the stream proxy. */
  direct?: boolean;
  /** Reported to the activity dashboard (who is watching what). */
  playlistId?: number | null;
  kind?: 'live' | 'movie' | 'series' | 'catchup' | 'download';
  logo?: string | null;
}

/** Player is a route (`/watch/...`); helpers navigate by id to keep tokens/urls out of the URL bar. */
export interface PlayerNav {
  playChannel: (channelId: number) => void;
  playCatchup: (channelId: number, startMs: number, endMs: number) => void;
  playDownload: (downloadId: number) => void;
}

const PlayerContext = createContext<PlayerNav>({
  playChannel: () => {}, playCatchup: () => {}, playDownload: () => {},
});
export const usePlayer = () => useContext(PlayerContext);

export function PlayerProvider({ children }: { children: ReactNode }) {
  const navigate = useNavigate();
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
  const nav = useMemo<PlayerNav>(() => ({
    playChannel: (id) => navigate(`/watch/${id}`),
    playCatchup: (id, startMs, endMs) => navigate(`/watch/catchup/${id}/${startMs}/${endMs}`),
    playDownload: (id) => navigate(`/watch/download/${id}`),
  }), [navigate]);
  return <PlayerContext.Provider value={nav}>{children}</PlayerContext.Provider>;
}

/** Engine hint. Provider tokens carry a leading format tag (h/l/t/d); the server's own
 *  URLs (remux output, downloads) aren't tokens and fall back to extension sniffing. */
function streamKind(u: string): 'hls' | 'livets' | 'ts' | 'direct' {
  const tag = /^([hltd])\./.exec(u)?.[1];
  if (tag) return ({ h: 'hls', l: 'livets', t: 'ts', d: 'direct' } as const)[tag as 'h' | 'l' | 't' | 'd'];
  const path = u.split('?')[0].toLowerCase();
  if (path.endsWith('.m3u8') || path.endsWith('.m3u')) return 'hls';
  if (path.endsWith('.ts')) return /\/live\//.test(path) ? 'livets' : 'ts';
  return 'direct';
}

// Browser can decode HEVC in fMP4 via MediaSource -> server copies instead of
// transcoding to H.264. Probed once.
const hevcCapable = typeof MediaSource !== 'undefined'
  && ['hvc1.1.6.L120.90', 'hvc1.1.6.L93.90', 'hev1.1.6.L93.90']
    .some((c) => MediaSource.isTypeSupported(`video/mp4; codecs="${c}"`));

const fmtClock = (s: number) => {
  if (!isFinite(s)) return '–:––';
  s = Math.max(0, Math.floor(s));
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60;
  return h ? `${h}:${String(m).padStart(2, '0')}:${String(sec).padStart(2, '0')}` : `${m}:${String(sec).padStart(2, '0')}`;
};

function MenuSheet({ title, options, selected, onPick, onDismiss, container, emptyText, headerAction }: {
  title: string;
  options: string[];
  selected: number;
  onPick: (index: number) => void;
  onDismiss: () => void;
  container?: Element | null;
  emptyText?: string;
  headerAction?: ReactNode;
}) {
  return (
    <Sheet onDismiss={onDismiss} container={container}
           header={<><h3 className="sheet-title">{title}</h3>{headerAction}</>}>
      {options.length === 0 && (
        <p className="py-3 type-body-medium text-on-surface-variant">
          {emptyText ?? t('player.noTracks')}
        </p>
      )}
      {options.map((label, i) => (
        <button key={label + i} className={`menu-option${i === selected ? ' selected' : ''}`}
                onClick={() => { onDismiss(); onPick(i); }}>
          <span className="min-w-0 flex-1 truncate">{label}</span>
          {i === selected && <Icon name="check" className="ml-2 size-5 shrink-0" />}
        </button>
      ))}
    </Sheet>
  );
}

type SubStyle = { scale: number; style: string; bold: boolean };

/** Subtitle appearance controls: preview, size, outline/background, bold. */
function SubtitleStyleSheet({ value, onChange, onDismiss, container }: {
  value: SubStyle;
  onChange: (next: SubStyle) => void;
  onDismiss: () => void;
  container?: Element | null;
}) {
  return (
    <Sheet onDismiss={onDismiss} container={container}
           header={<h3 className="sheet-title">{t('player.subtitleStyle')}</h3>}>
      <div className="sub-preview">
        <span className={`cue cue-${value.style}${value.bold ? ' bold' : ''}`}
              style={{ fontSize: `${value.scale * 22}px` }}>
          <span className="cue-line">{t('player.subtitlePreview')}</span>
        </span>
      </div>

      <div className="sub-row-label">{t('player.subtitleSize', { pct: Math.round(value.scale * 100) })}</div>
      <input className="seek sub-slider" type="range" min={50} max={200} step={10}
             value={Math.round(value.scale * 100)}
             onChange={(e) => onChange({ ...value, scale: Number(e.target.value) / 100 })} />

      <div className="sub-row-label">{t('player.subtitleStyle')}</div>
      <Segmented
        options={[['outline', t('player.subtitleOutline')], ['background', t('player.subtitleBackground')]]}
        selected={value.style}
        onSelect={(v) => onChange({ ...value, style: String(v) })} />

      <div className="sub-row-label">{t('player.subtitleBold')}</div>
      <Segmented
        options={[['off', t('player.off')], ['on', t('player.on')]]}
        selected={value.bold ? 'on' : 'off'}
        onSelect={(v) => onChange({ ...value, bold: v === 'on' })} />
    </Sheet>
  );
}

type TrackKind = { audio: { names: string[]; current: number }; subs: { names: string[]; current: number } };

export function PlayerSurface({ request, onClose, onPlayCatchup }: {
  request: PlayRequest;
  onClose: () => void;
  onPlayCatchup: (channelId: number, startMs: number, endMs: number) => void;
}) {
  const { url, title, channelId, live, tvgId, hasGuide, catchup, direct } = request;
  // Server remux override: the file re-served as a VOD HLS playlist (all tracks exposed,
  // seeking handled by hls.js). `startAt` is the resume/switch position; `audio` is the
  // muxed-in track (switching re-requests).
  const [remux, setRemux] = useState<{ id: string; playlistUrl: string; duration: number | null; startAt: number; nativeCopy: boolean; audio: number } | null>(null);
  const [remuxState, setRemuxState] = useState<'idle' | 'loading' | 'none' | 'failed'>('idle');
  const [remuxAvailable, setRemuxAvailable] = useState<boolean | null>(null);
  const remuxRef = useRef(remux);
  useEffect(() => { remuxRef.current = remux; }, [remux]);
  // ffmpeg availability, readable from inside the engine closures.
  const remuxAvailableRef = useRef(remuxAvailable);
  useEffect(() => { remuxAvailableRef.current = remuxAvailable; }, [remuxAvailable]);
  // Set when a copied-HEVC remux fails: browser claimed support but couldn't play it;
  // re-request with a transcode.
  const forceTranscode = useRef(false);
  useEffect(() => {
    // Switching files: release the old session (frees its provider connection).
    const prev = remuxRef.current;
    if (prev) api.remuxStop(prev.id);
    setRemux(null); setRemuxState('idle'); forceTranscode.current = false;
  }, [url]);
  useEffect(() => {
    api.remuxAvailable().then((r) => setRemuxAvailable(r.available)).catch(() => setRemuxAvailable(false));
  }, []);
  const activeUrl = remux?.playlistUrl ?? url;
  const activeDirect = remux ? true : !!direct;
  const rootRef = useRef<HTMLDivElement>(null);
  const videoRef = useRef<HTMLVideoElement>(null);
  const hlsRef = useRef<Hls | null>(null);
  const mpegtsRef = useRef<mpegts.Player | null>(null);

  const [error, setError] = useState<string | null>(null);
  const [paused, setPaused] = useState(false);
  const [buffering, setBuffering] = useState(true);
  const [bufferedEnd, setBufferedEnd] = useState(0);
  // Live audio the browser couldn't decode, rescued via the server's AAC transcode
  // (mpegts path only); surfaced to the activity dashboard.
  const [audioTranscoded, setAudioTranscoded] = useState(false);
  const [epgNow, setEpgNow] = useState<GuideEntry | null>(null);
  const [uiVisible, setUiVisible] = useState(true);
  const [time, setTime] = useState({ position: 0, duration: NaN });
  /* Slider position (0..1000) while the user is dragging; null otherwise. */
  const [scrub, setScrub] = useState<number | null>(null);
  /* Full-file target of an in-flight seek; the bar shows this until playback reaches it. */
  const [pendingSeek, setPendingSeek] = useState<number | null>(null);
  useEffect(() => { setPendingSeek(null); }, [url]);
  // Held-seek target for the seek closures, so a relative nudge starts from where
  // the bar is heading, not stale media time.
  const pendingSeekRef = useRef(pendingSeek);
  useEffect(() => { pendingSeekRef.current = pendingSeek; }, [pendingSeek]);
  const [tracks, setTracks] = useState<TrackKind>({ audio: { names: [], current: -1 }, subs: { names: [], current: -1 } });
  const [menu, setMenu] = useState<null | 'speed' | 'scale' | 'audio' | 'subs' | 'subStyle'>(null);
  const [guideChannel, setGuideChannel] = useState<Channel | null>(null);
  const [scale, setScale] = useState(prefs.resizeMode);
  // Active cue text (rendered by our own overlay) and the user's appearance settings.
  const [cueText, setCueText] = useState('');
  const [subStyle, setSubStyle] = useState({ scale: prefs.subScale, style: prefs.subStyle, bold: prefs.subBold });
  const hideTimer = useRef<ReturnType<typeof setTimeout>>();
  const nativeTracks = useRef<{ text: TextTrack[]; audio: { enabled: boolean }[] }>({ text: [], audio: [] });
  // Explicit track picks, kept across engine restarts (seek re-anchors rebuild hls.js).
  // audio -1 = untouched; subs null = untouched, -1 = explicitly off.
  const chosenTracks = useRef<{ audio: number; subs: number | null }>({ audio: -1, subs: null });
  useEffect(() => { chosenTracks.current = { audio: -1, subs: null }; }, [url]);

  const src = useCallback(
    (u: string, hls = false) => (activeDirect ? u : streamUrl(u, hls)),
    [activeDirect],
  );

  // Non-live files (VOD, downloads, raw-TS VOD) and catch-up go through the remux:
  // it exposes tracks, normalizes audio the browser can't decode (E-AC3/AC3), and
  // gives catch-up a seekable timeline. Engine stays off while pending so
  // single-connection providers never see two concurrent opens. Live `.ts` is excluded.
  const remuxEligible = !live &&
    (streamKind(url) === 'direct' || streamKind(url) === 'ts' || !!catchup);
  const holdEngine = remuxEligible && !remux &&
    (remuxAvailable == null ||
      (remuxAvailable && (remuxState === 'idle' || remuxState === 'loading')));

  const poke = useCallback(() => {
    setUiVisible(true);
    clearTimeout(hideTimer.current);
    hideTimer.current = setTimeout(() => {
      if (!videoRef.current?.paused) setUiVisible(false);
    }, 3000);
  }, []);

  // Native <video> failures give no reason; probe the URL to surface an upstream
  // HTTP error instead of a misleading "cannot decode".
  const diagnoseNativeError = useCallback(async () => {
    try {
      const r = await fetch(src(url), { headers: { Range: 'bytes=0-0' } });
      if (!r.ok) {
        let message = `HTTP ${r.status}`;
        try { message = ((await r.json()) as { message?: string }).message || message; } catch { /* not json */ }
        setError(t('player.upstreamFailed', { message }));
        return;
      }
      try { await r.body?.cancel(); } catch { /* stream already closed */ }
    } catch { /* network failed; keep the generic message */ }
    setError((old) => old ?? t('player.decodeFailed'));
  }, [src, url]);

  // ---- engine wiring ----
  const lastUrl = useRef<string>();
  useEffect(() => {
    if (holdEngine) return;
    const video = videoRef.current!;
    let triedTsFallback = false;
    let retryTimer: ReturnType<typeof setTimeout> | undefined;
    setError(null);
    setBuffering(true);
    setBufferedEnd(0);
    setAudioTranscoded(false);
    if (lastUrl.current !== url) {
      lastUrl.current = url;
      video.playbackRate = 1;
    }

    const kind = streamKind(activeUrl);

    const stopEngines = () => {
      hlsRef.current?.destroy(); hlsRef.current = null;
      mpegtsRef.current?.destroy(); mpegtsRef.current = null;
    };

    const readHlsTracks = (hls: Hls) => {
      // A remux playlist muxes in one audio track and serves subtitles as sidecars,
      // so its menus come from /remux/start, not from what hls.js sees here.
      if (remuxRef.current) return;
      setTracks({
        audio: { names: hls.audioTracks.map((track, i) => track.name || track.lang || t('player.audioN', { n: i + 1 })), current: hls.audioTrack },
        subs: { names: hls.subtitleTracks.map((track, i) => track.name || track.lang || t('player.subtitlesN', { n: i + 1 })), current: hls.subtitleTrack },
      });
    };

    const playMpegts = (target: string, transcoded = false) => {
      if (!mpegts.getFeatureList().mseLivePlayback) {
        setError(t('player.mpegtsUnsupported'));
        return;
      }
      setAudioTranscoded(transcoded);
      const player = mpegts.createPlayer({
        type: 'mpegts', isLive: true,
        url: transcoded ? transcodeUrl(target) : src(target),
      });
      mpegtsRef.current = player;
      player.attachMediaElement(video);
      player.load();
      player.play()?.catch(() => {});
      // Audio the browser can't decode: server re-muxes to AAC (video copied) and
      // retry once through the same engine, so it gets sound not a silent picture.
      const rescueAudio = () => {
        if (transcoded || remuxAvailableRef.current !== true) return false;
        player.destroy();
        if (mpegtsRef.current === player) mpegtsRef.current = null;
        playMpegts(target, true);
        return true;
      };
      // mpegts.js fires ERROR for both network hiccups and undecodable codecs.
      // A live TS whose video decodes but whose audio doesn't plays silently yet
      // still errors: never cover a decoding picture; note the audio issue once and
      // only surface a hard failure when no picture arrives. Network errors get a bounded reload.
      const hasPicture = () => video.videoWidth > 0 && video.readyState >= 2;
      const reload = () => {
        try { player.unload(); player.load(); player.play()?.catch(() => {}); } catch { /* destroyed */ }
      };
      let retries = 0;
      let lastErr = 0;
      let noticed = false;
      const noteAudio = () => { if (!noticed) { noticed = true; snackbar(t('player.audioUnsupported')); } };
      player.on(mpegts.Events.ERROR, (type: string) => {
        if (type === mpegts.ErrorTypes.NETWORK_ERROR) {
          if (hasPicture()) return; // a frozen frame recovers via the watchdog
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
        if (video.canPlayType('application/vnd.apple.mpegurl')) video.src = src(target, hlsVariant);
        else setError(t('player.hlsUnsupported'));
        return;
      }
      // The remux playlist is the whole file; open at the resume/switch position.
      const startAt = remux && remux.startAt > 0 ? remux.startAt : -1;
      // 30s forward buffer; unbounded back buffer for VOD so seeking back replays from memory.
      const hls = new Hls({
        startPosition: startAt,
        lowLatencyMode: false,
        maxBufferLength: 30,
        maxMaxBufferLength: 30,
        backBufferLength: remux ? Infinity : 90,
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
      hls.loadSource(src(target, hlsVariant));
      hls.attachMedia(video);
      hls.on(Hls.Events.ERROR, (_e, data) => {
        if (!data.fatal) return;
        // A copied HEVC the browser claimed to support but can't actually decode: force a
        // transcode and re-prepare the session at the current spot.
        if (remuxRef.current?.nativeCopy && data.type === Hls.ErrorTypes.MEDIA_ERROR && mediaRecoveries < 1) {
          forceTranscode.current = true;
          startRemuxRef.current(remuxRef.current.audio, video.currentTime);
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
          playMpegts(url);
        } else {
          setError(t('player.failedDetail', { detail: data.details || data.type }));
        }
      });
      hls.on(Hls.Events.MANIFEST_PARSED, () => {
        readHlsTracks(hls);
        setRemuxState((s) => (s === 'loading' ? 'idle' : s));
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

    if (kind === 'hls') playHls(activeUrl);
    else if (kind === 'livets') playHls(activeUrl, true); // ask the proxy for the HLS variant
    else if (kind === 'ts') playMpegts(activeUrl);
    else video.src = src(activeUrl);

    if (!live && !catchup && !remux) {
      // Resume VOD position (remux already starts there); catch-up never resumes.
      api.resumeAll().then((points) => {
        const p = points.find((x) => x.url === url);
        if (p && p.positionMs >= 10_000) {
          const apply = () => { video.currentTime = p.positionMs / 1000; };
          if (video.readyState >= 1) apply();
          else video.addEventListener('loadedmetadata', apply, { once: true });
        }
      }).catch(() => {});
    }

    const saveResume = () => {
      if (live || catchup) return;
      const duration = remux?.duration ?? video.duration;
      if (!duration || !isFinite(duration)) return;
      api.saveResume(url, Math.floor(video.currentTime * 1000), Math.floor(duration * 1000)).catch(() => {});
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
          const player = mpegtsRef.current;
          player.unload();
          player.load();
          player.play()?.catch(() => {});
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
      video.pause();
      video.removeAttribute('src');
      video.load();
    };
  }, [url, activeUrl, live, remux, src, holdEngine]);

  // PiP/fullscreen end when the player closes, not on engine swaps. Closing also
  // releases the remux session so nothing keeps reading the provider.
  useEffect(() => () => {
    if (document.pictureInPictureElement) document.exitPictureInPicture().catch(() => {});
    if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
    if (remuxRef.current) api.remuxStop(remuxRef.current.id);
  }, []);

  // ---- video element state -> React ----
  useEffect(() => {
    const video = videoRef.current!;
    video.volume = prefs.volume;
    video.muted = prefs.muted;
    const onTime = () => setTime({ position: video.currentTime, duration: video.duration });
    const onPlay = () => setPaused(false);
    const onPause = () => setPaused(true);
    const onWaiting = () => setBuffering(true);
    const onReady = () => {
      setBuffering(false);
      // Remux is playing: never leave the menus stuck on "preparing".
      if (remuxRef.current) setRemuxState((s) => (s === 'loading' ? 'idle' : s));
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
    const onEnded = () => onClose();
    const onError = () => {
      if (hlsRef.current || mpegtsRef.current) return;
      if (remuxRef.current) {
        // The remux stream died, or the browser couldn't decode a copied HEVC it
        // claimed to support: force a transcode on the retry and re-anchor.
        if (remuxRef.current.nativeCopy) forceTranscode.current = true;
        setRemux(null);
        setRemuxState('failed');
        return;
      }
      diagnoseNativeError();
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
    video.addEventListener('timeupdate', onTime);
    video.addEventListener('play', onPlay);
    video.addEventListener('pause', onPause);
    video.addEventListener('waiting', onWaiting);
    video.addEventListener('seeking', onWaiting);
    video.addEventListener('playing', onReady);
    video.addEventListener('canplay', onReady);
    video.addEventListener('seeked', onReady);
    video.addEventListener('volumechange', onVolume);
    video.addEventListener('progress', onProgress);
    video.addEventListener('ended', onEnded);
    video.addEventListener('error', onError);
    video.addEventListener('loadedmetadata', readNativeTracks);
    video.textTracks?.addEventListener?.('addtrack', readNativeTracks);
    video.textTracks?.addEventListener?.('change', readNativeTracks);
    // Safari fills audioTracks after loadedmetadata; listen for the adds.
    type TrackList = { addEventListener?: (t: string, l: () => void) => void; removeEventListener?: (t: string, l: () => void) => void };
    const audioTrackList = (video as HTMLVideoElement & { audioTracks?: TrackList }).audioTracks;
    audioTrackList?.addEventListener?.('addtrack', readNativeTracks);
    audioTrackList?.addEventListener?.('change', readNativeTracks);
    return () => {
      video.removeEventListener('timeupdate', onTime);
      video.removeEventListener('play', onPlay);
      video.removeEventListener('pause', onPause);
      video.removeEventListener('waiting', onWaiting);
      video.removeEventListener('seeking', onWaiting);
      video.removeEventListener('playing', onReady);
      video.removeEventListener('canplay', onReady);
      video.removeEventListener('seeked', onReady);
      video.removeEventListener('volumechange', onVolume);
      video.removeEventListener('progress', onProgress);
      video.removeEventListener('ended', onEnded);
      video.removeEventListener('error', onError);
      video.removeEventListener('loadedmetadata', readNativeTracks);
      video.textTracks?.removeEventListener?.('addtrack', readNativeTracks);
      video.textTracks?.removeEventListener?.('change', readNativeTracks);
      audioTrackList?.removeEventListener?.('addtrack', readNativeTracks);
      audioTrackList?.removeEventListener?.('change', readNativeTracks);
    };
  }, [onClose, diagnoseNativeError]);

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
        if (list[i].mode === 'disabled') continue;
        const cues = list[i].cues;
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
  }, [activeUrl]);

  const pickAudio = useCallback((index: number) => {
    chosenTracks.current.audio = index;
    setTracks((old) => ({ ...old, audio: { ...old.audio, current: index } }));
    if (remuxRef.current) {
      // Switching audio re-requests the playlist muxed with that track, reopened at
      // the current position.
      startRemuxRef.current(index, videoRef.current!.currentTime);
      return;
    }
    if (hlsRef.current) { hlsRef.current.audioTrack = index; return; }
    nativeTracks.current.audio.forEach((track, i) => { track.enabled = i === index; });
  }, []);

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
  }, []);

  const togglePlay = useCallback(() => {
    const video = videoRef.current!;
    if (video.paused) video.play().catch(() => {});
    else video.pause();
  }, []);

  // seekTo/startRemux are defined further down; refs let earlier callbacks reach them.
  const seekToRef = useRef<(target: number) => void>(() => {});
  const startRemuxRef = useRef<(audio: number, startAt: number) => void>(() => {});
  const seekBy = useCallback((delta: number) => {
    const video = videoRef.current!;
    // Start from the held target if any, else media time: a seek still buffering
    // would otherwise nudge from a stale position.
    const base = pendingSeekRef.current ?? video.currentTime;
    seekToRef.current(Math.max(0, base + delta));
  }, []);

  const toggleMute = useCallback(() => {
    const video = videoRef.current!;
    video.muted = !video.muted;
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

  const changeVolume = useCallback((v: number) => {
    const video = videoRef.current!;
    video.volume = Math.min(1, Math.max(0, v));
    if (video.volume > 0 && video.muted) video.muted = false;
  }, []);

  // Live: show what's airing now, refreshed when the programme ends.
  useEffect(() => {
    if (!live || channelId == null) { setEpgNow(null); return; }
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout>;
    const load = async () => {
      const entries = await api.guide(channelId).catch(() => [] as GuideEntry[]);
      if (cancelled) return;
      const now = Date.now();
      const current = entries.find((g) => g.startMs <= now && now < g.endMs) ?? null;
      setEpgNow(current);
      timer = setTimeout(load, current ? Math.min(current.endMs - now + 1000, 30 * 60_000) : 5 * 60_000);
    };
    load();
    return () => { cancelled = true; clearTimeout(timer); };
  }, [live, channelId]);

  // OS media keys / lock-screen controls.
  useEffect(() => {
    if (!('mediaSession' in navigator)) return;
    const session = navigator.mediaSession;
    session.metadata = new MediaMetadata({ title });
    session.setActionHandler('play', () => videoRef.current?.play().catch(() => {}));
    session.setActionHandler('pause', () => videoRef.current?.pause());
    session.setActionHandler('seekbackward', live ? null : () => seekBy(-prefs.seekSeconds));
    session.setActionHandler('seekforward', live ? null : () => seekBy(prefs.seekSeconds));
    return () => {
      session.metadata = null;
      session.setActionHandler('play', null);
      session.setActionHandler('pause', null);
      session.setActionHandler('seekbackward', null);
      session.setActionHandler('seekforward', null);
    };
  }, [title, live, seekBy]);

  // keyboard shortcuts
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      // A focused slider already consumes arrows/space.
      if ((e.target as HTMLElement).tagName === 'INPUT') return;
      // Player closes on Escape only when no sheet is layered over it.
      if (e.key === 'Escape') {
        if (!menu && !guideChannel && !document.fullscreenElement) onClose();
      }
      else if (e.key === ' ') { e.preventDefault(); togglePlay(); poke(); }
      else if (e.key === 'ArrowLeft' && !live) { seekBy(-prefs.seekSeconds); poke(); }
      else if (e.key === 'ArrowRight' && !live) { seekBy(prefs.seekSeconds); poke(); }
      else if (e.key === 'ArrowUp') { e.preventDefault(); changeVolume((videoRef.current?.volume ?? 1) + 0.05); poke(); }
      else if (e.key === 'ArrowDown') { e.preventDefault(); changeVolume((videoRef.current?.volume ?? 1) - 0.05); poke(); }
      else if (e.key === 'm') { toggleMute(); poke(); }
      else if (e.key === 'f') rootRef.current?.requestFullscreen().catch(() => {});
    };
    document.addEventListener('keydown', onKey);
    poke();
    return () => { document.removeEventListener('keydown', onKey); clearTimeout(hideTimer.current); };
  }, [live, menu, guideChannel, onClose, poke, seekBy, togglePlay, toggleMute, changeVolume]);

  const speeds = useMemo(() => [0.5, 0.75, 1, 1.25, 1.5, 2], []);
  const scaleModes = useMemo(() => ['fit', 'zoom', 'stretch'] as const, []);

  /** Prepares (or re-requests) the HLS remux session with audio track [audio]; hls.js
   *  then plays its VOD playlist, opening at [startAt] seconds. */
  const startRemux = useCallback(async (audio: number, startAt: number) => {
    const audioIdx = Math.max(0, audio);
    const prevId = remuxRef.current?.id;
    setRemuxState('loading');
    try {
      // Copy (not transcode) HEVC when the browser can decode it, unless a prior copy failed.
      const hevc = hevcCapable && !forceTranscode.current;
      const result = await api.remuxStart(url, audioIdx, !!catchup, hevc);
      // Switching audio makes a new session; release the old one so this viewer never
      // holds two of the provider's connections at once.
      if (prevId && prevId !== result.id) api.remuxStop(prevId);
      setRemux({
        id: result.id, playlistUrl: result.playlistUrl,
        duration: result.duration ?? remuxRef.current?.duration ?? null,
        startAt, nativeCopy: result.nativeVideoCopy, audio: audioIdx,
      });
      setTracks({
        audio: { names: result.audioTracks, current: audioIdx },
        subs: { names: result.subtitleTracks ?? [], current: chosenTracks.current.subs ?? -1 },
      });
    } catch (e) {
      // Provider connection limit reached: surface it as a player error like the
      // decode failure, not a passing snackbar.
      if ((e as ApiError).status === 429) {
        setRemuxState('failed');
        setRemux(null);
        setError(t('player.connectionLimit'));
        return;
      }
      // "No additional tracks" is normal (source plays directly); anything else surfaces.
      const noTracks = /no additional tracks/i.test((e as Error).message);
      setRemuxState(noTracks ? 'none' : 'failed');
      setRemux(null);
      if (!noTracks) snackbar((e as Error).message);
    }
  }, [url, catchup]);
  startRemuxRef.current = startRemux;

  // Prepare the remux when the file opens, opening at the saved resume position so
  // hls.js starts there and the track menus are populated early.
  useEffect(() => {
    if (!remuxEligible || remuxAvailable !== true || remux || remuxState !== 'idle') return;
    let cancelled = false;
    (async () => {
      const points = catchup ? [] : await api.resumeAll().catch(() => [] as ResumePoint[]);
      if (cancelled) return;
      const point = points.find((x) => x.url === url);
      const at = point && point.positionMs >= 10_000 ? Math.floor(point.positionMs / 1000) : 0;
      startRemux(Math.max(0, chosenTracks.current.audio), at);
    })();
    return () => { cancelled = true; };
  }, [remuxEligible, remuxAvailable, remux, remuxState, url, catchup, startRemux]);

  // Remux is the only path to sound for undecodable audio (AC3/E-AC3/DTS), so a failed
  // prepare can't be left as silent direct playback. Usual cause: a single-connection
  // provider where the first ffprobe still holds the connection; the probe is then cached,
  // so retries (with backoff) generally get in.
  const remuxRetries = useRef(0);
  useEffect(() => { remuxRetries.current = 0; }, [url]);
  useEffect(() => {
    if (remuxState !== 'failed' || !remuxEligible || remuxAvailable !== true) return;
    if (remuxRetries.current >= 3) return;
    remuxRetries.current += 1;
    const timer = setTimeout(
      () => startRemux(Math.max(0, chosenTracks.current.audio), 0),
      1500 * remuxRetries.current,
    );
    return () => clearTimeout(timer);
  }, [remuxState, remuxEligible, remuxAvailable, startRemux]);

  // One extra remux retry per track-menu opening, after the automatic ones are used up.
  const menuRetried = useRef(false);
  useEffect(() => {
    if (menu !== 'audio' && menu !== 'subs') { menuRetried.current = false; return; }
    if (menuRetried.current || remuxState !== 'failed' || !remuxEligible || remuxAvailable !== true) return;
    menuRetried.current = true;
    startRemux(Math.max(0, chosenTracks.current.audio), 0);
  }, [menu, remuxState, remuxEligible, remuxAvailable, startRemux]);

  const fullDuration = remux?.duration ?? time.duration;
  const fullPosition = time.position;
  // hls.js (remux) and native players both seek in place; the whole file is addressable.
  const seekTo = useCallback((target: number) => {
    const video = videoRef.current!;
    const clamped = Math.max(0, target);
    setPendingSeek(clamped);
    video.currentTime = clamped;
  }, []);
  seekToRef.current = seekTo;

  // Release the held seek target once media reaches it (within ~1.5s).
  useEffect(() => {
    if (pendingSeek == null) return;
    if (isFinite(fullPosition) && Math.abs(fullPosition - pendingSeek) < 1.5) setPendingSeek(null);
  }, [fullPosition, pendingSeek]);

  // Report playback to the activity dashboard. Engine mirrors the wiring effect's
  // choice; remux takes precedence since it re-serves the source as HLS.
  const reportEngine = remux ? 'remux'
    : streamKind(activeUrl) === 'ts' ? 'mpegts'
      : streamKind(activeUrl) === 'direct' ? 'native' : 'hls';
  // Stable identity of this content, so two viewers of the same thing can watch together.
  const contentKey = catchup ? `cu:${url}` : channelId != null ? `ch:${channelId}` : `u:${url}`;
  const wt = useWatchTogether({
    selfId: tabSessionId(),
    video: videoRef,
    active: !error,
    live: !!live,
    contentKey,
    source: url,
    playlistId: request.playlistId ?? null,
  });
  // Watching something else while the provider is full: show the same style of message
  // as a decode failure rather than silently stealing the connection.
  useEffect(() => {
    if (!wt.limited) return;
    setError((old) => old ?? t('player.connectionLimit'));
    videoRef.current?.pause();
  }, [wt.limited]);
  useSessionReporter({
    playlistId: request.playlistId ?? null,
    title,
    kind: request.kind ?? (catchup ? 'catchup' : live ? 'live' : 'movie'),
    logo: request.logo ?? null,
    live: !!live,
    durationSec: fullDuration,
    engine: reportEngine,
    direct: activeDirect,
    audioTranscoded,
    // Engine is held while ffmpeg probes the file; report "preparing" so the
    // dashboard shows that, not the transient pre-remux (proxied) mode.
    preparing: holdEngine,
    remuxId: remux?.id ?? null,
    contentKey,
  }, videoRef, wt.onCommand);

  const busy = holdEngine || remuxState === 'loading' || (buffering && !paused);
  // VOD/downloads and catch-up all get a scrubber.
  const showSeek = !live;
  const tracksEmptyText =
    remuxState === 'loading' || holdEngine ? t('player.remuxPreparing')
      : remux || remuxState === 'none' ? t('player.noExtraTracks')
        : t('player.noTracks');
  // Bar position: the drag value, else the held seek target, else live media.
  const barPosition = pendingSeek ?? fullPosition;
  const seekFrac = scrub != null ? scrub / 1000
    : fullDuration && isFinite(fullDuration) ? barPosition / fullDuration : 0;
  const bufferedFrac = fullDuration && isFinite(fullDuration)
    ? Math.max(seekFrac, Math.min(1, bufferedEnd / fullDuration)) : 0;
  const seekStyle = {
    background: `linear-gradient(to right, #fff ${(seekFrac * 100).toFixed(2)}%, `
      + `rgba(255,255,255,0.45) ${(seekFrac * 100).toFixed(2)}%, `
      + `rgba(255,255,255,0.45) ${(bufferedFrac * 100).toFixed(2)}%, `
      + `rgba(255,255,255,0.18) ${(bufferedFrac * 100).toFixed(2)}%)`,
  };
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
           else if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
           else rootRef.current?.requestFullscreen().catch(() => {});
         }}>
      <video ref={videoRef} autoPlay playsInline
             className={scale === 'zoom' ? 'zoom' : scale === 'stretch' ? 'stretch' : undefined} />

      {wt.room && (
        <div className="watch-pill">
          <span className="dot" aria-hidden />
          {t('watch.together')}
          {wt.room.role === 'guest' && !wt.room.canControl && (
            <button className="leave" onClick={wt.requestControl}>{t('watch.requestControl')}</button>
          )}
          <button className="leave" onClick={wt.leave}>{t('watch.leave')}</button>
        </div>
      )}
      {!wt.room && wt.canAsk && !wt.offer && (
        <button className="watch-pill ask" onClick={wt.ask}>
          <span className="dot" aria-hidden />
          {t('watch.ask')}
        </button>
      )}
      <WatchTogetherModals wt={wt} container={rootRef.current} />

      {cueText && (
        <div className={`player-subs${uiVisible ? ' chrome' : ''}`} aria-live="off">
          <span className={`cue cue-${subStyle.style}${subStyle.bold ? ' bold' : ''}`}
                style={{ fontSize: `${subStyle.scale * 3.2}vh` }}>
            {cueText.split('\n').map((line, i) => <span key={i} className="cue-line">{line}</span>)}
          </span>
        </div>
      )}

      {!error && busy && !uiVisible && <div className="player-spinner" aria-hidden />}

      {error && (
        <div className="player-error">
          <h3>{t('player.errorTitle')}</h3>
          <p>{error} {t('player.errorHint')}</p>
          <button className="btn tonal" style={{ width: 'auto' }} onClick={onClose}>{t('common.close')}</button>
        </div>
      )}

      {!error && (
        <div className={`player-ui${uiVisible ? '' : ' hidden'}`}>
          <div className="top">
            <IconBtn name="back" label={t('common.back')} onClick={onClose} />
            <div className="title-block">
              <div className="t">{title}</div>
              <div className="s">
                {live
                  ? epgNow
                    ? t('player.nowUntil', {
                        title: epgNow.title,
                        end: new Date(epgNow.endMs).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
                      })
                    : t('player.live')
                  : catchup ? t('player.catchup') : ''}
              </div>
            </div>
            {live && channelId != null && (hasGuide ?? !!tvgId) && (
              <IconBtn name="calendar" label={t('guide.title')} onClick={async () => {
                const channel = await api.channel(channelId).catch(() => null);
                if (channel) setGuideChannel(channel);
              }} />
            )}
            <IconBtn name="close" label={t('player.stop')} onClick={onClose} />
          </div>
          <div className="middle">
            {!live && (
              <button className="icon-btn big-btn" aria-label={t('player.rewind')} onClick={() => seekBy(-prefs.seekSeconds)}>
                <Icon name="replay" />
              </button>
            )}
            <button className="icon-btn big-btn" aria-label={paused ? t('common.play') : t('common.pause')} onClick={togglePlay}>
              {busy ? <span className="btn-spinner" aria-hidden /> : <Icon name={paused ? 'play' : 'pause'} />}
            </button>
            {!live && (
              <button className="icon-btn big-btn" aria-label={t('player.forward')} onClick={() => seekBy(prefs.seekSeconds)}>
                <Icon name="forward" />
              </button>
            )}
          </div>
          <div className="bottom">
            {showSeek && (
              <div className="seek-row">
                <span className="time-label">
                  {fmtClock(scrub != null && isFinite(fullDuration)
                    ? (scrub / 1000) * fullDuration
                    : barPosition)}
                </span>
                <input
                  className="seek" type="range" min={0} max={1000} style={seekStyle}
                  value={scrub ?? (fullDuration ? Math.floor((barPosition / fullDuration) * 1000) : 0)}
                  onChange={(e) => { setScrub(Number(e.target.value)); poke(); }}
                  onPointerUp={(e) => {
                    const value = Number((e.target as HTMLInputElement).value);
                    if (fullDuration && isFinite(fullDuration)) seekTo((value / 1000) * fullDuration);
                    setScrub(null);
                  }}
                  onKeyUp={(e) => {
                    const value = Number((e.target as HTMLInputElement).value);
                    if (fullDuration && isFinite(fullDuration)) seekTo((value / 1000) * fullDuration);
                    setScrub(null);
                  }}
                />
                <span className="time-label">{fmtClock(fullDuration)}</span>
              </div>
            )}
            <div className="controls">
              {live && <span className="live-chip">{t('player.live').toUpperCase()}</span>}
              <IconBtn name="audio" label={t('player.audio')} onClick={() => setMenu('audio')} />
              <IconBtn name="subtitles" label={t('player.subtitles')} onClick={() => setMenu('subs')} />
              {!live && <IconBtn name="speed" label={t('player.speed')} onClick={() => setMenu('speed')} />}
              <IconBtn name="aspect" label={t('player.scaling')} onClick={() => setMenu('scale')} />
              {document.pictureInPictureEnabled &&
                <IconBtn name="pip" label={t('player.pip')}
                         onClick={() => videoRef.current?.requestPictureInPicture().catch(() => {})} />}
              {canRotate &&
                <IconBtn name="rotate" label={t('player.rotate')} onClick={rotateScreen} />}
              <IconBtn name="fullscreen" label={t('player.fullscreen')} onClick={() => {
                if (document.fullscreenElement) document.exitFullscreen();
                else rootRef.current?.requestFullscreen().catch(() => {});
              }} />
            </div>
          </div>
        </div>
      )}

      {menu === 'speed' && (
        <MenuSheet title={t('player.speed')} options={speeds.map((s) => `${s}×`)}
                   selected={speeds.indexOf(videoRef.current?.playbackRate ?? 1)}
                   onPick={(i) => { videoRef.current!.playbackRate = speeds[i]; }}
                   onDismiss={() => setMenu(null)} container={rootRef.current} />
      )}
      {menu === 'scale' && (
        <MenuSheet title={t('player.scaling')}
                   options={[t('settings.scaleFit'), t('settings.scaleZoom'), t('settings.scaleStretch')]}
                   selected={scaleModes.indexOf(scale as typeof scaleModes[number])}
                   onPick={(i) => { prefs.resizeMode = scaleModes[i]; setScale(scaleModes[i]); }}
                   onDismiss={() => setMenu(null)} container={rootRef.current} />
      )}
      {menu === 'audio' && (
        <MenuSheet title={t('player.audio')} options={tracks.audio.names} selected={tracks.audio.current}
                   onPick={pickAudio} emptyText={tracksEmptyText}
                   onDismiss={() => setMenu(null)} container={rootRef.current} />
      )}
      {menu === 'subs' && (
        <MenuSheet title={t('player.subtitles')}
                   options={tracks.subs.names.length ? [t('player.off'), ...tracks.subs.names] : []}
                   selected={tracks.subs.current + 1}
                   onPick={(i) => pickSubtitle(i - 1)} emptyText={tracksEmptyText}
                   headerAction={
                     <IconBtn name="settings" label={t('player.subtitleStyle')} className="sub-style-btn"
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
    </div>
  );
}
