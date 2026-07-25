// The server-side remux: the file re-served as a VOD HLS playlist so every track is exposed,
// undecodable audio is normalized, and catch-up gets a seekable timeline.
//
// Non-live files (VOD, downloads, raw-TS VOD) and catch-up all go through it. A session is
// keyed by the audio track and the share group, so switching either re-requests one and
// releases the old read - this viewer never holds two of the provider's connections at once.

import { MutableRefObject, RefObject, useCallback, useEffect, useRef, useState } from 'react';
import { api, ApiError, PlaybackLease, ResumePoint } from '../api';
import { snackbar } from '../components/Primitives';
import { t } from '../i18n';
import { isTerminalPlaybackStatus } from './mediaGrant';
import { supportsHevc } from './playbackPolicy';
import { PlaybackStatusActions } from './playbackStatus';

// Browser can decode HEVC in fMP4 via MediaSource -> server copies instead of
// transcoding to H.264. Probed once.
const hevcCapable = supportsHevc(typeof MediaSource === 'undefined' ? undefined : MediaSource);

/** Automatic retries after a failed prepare, then one more per track-menu opening. */
const MAX_AUTOMATIC_RETRIES = 3;

export interface RemuxSession {
  id: string;
  playlistUrl: string;
  duration: number | null;
  /** The resume/switch position hls.js opens the playlist at. */
  startAt: number;
  /** Video the browser claimed it can decode, so the server copied it instead of transcoding. */
  nativeCopy: boolean;
  /** The muxed-in audio track; switching re-requests the session. */
  audio: number;
}

export type RemuxState = 'idle' | 'loading' | 'none' | 'failed';

export interface RemuxController {
  session: RemuxSession | null;
  state: RemuxState;
  /** null until the ffmpeg availability probe answers. */
  available: boolean | null;
  /** Both readable from inside the engine's closures. */
  ref: MutableRefObject<RemuxSession | null>;
  availableRef: MutableRefObject<boolean | null>;
  /** Set when a copied-HEVC remux fails: the browser claimed support but couldn't play it. */
  forceTranscode: MutableRefObject<boolean>;
  /** Prepare (or re-request) the session with [audio], opening at [startAt] seconds. */
  start: (audio: number, startAt: number) => void;
  /** The remux is producing frames: never leave the menus stuck on "preparing". */
  markPlaying: () => void;
  /** The remux stream died; retry (transcoding if a copy was at fault). */
  markDied: () => void;
}

export function useRemuxSession(opts: {
  lease: PlaybackLease;
  leaseRef: MutableRefObject<PlaybackLease>;
  contentId: string;
  /** Identity of the mounted source; a change releases the session it belonged to. */
  sourceKey: string;
  catchup: boolean;
  /** Non-live content is served through the remux. */
  eligible: boolean;
  /** Provider check in flight: don't open a read that is about to be refused. */
  checking: boolean;
  /** The provider is full and this stream would need its own connection. */
  blocked: boolean;
  inRoom: boolean;
  /** An open track menu earns one extra retry after the automatic ones are used up. */
  trackMenuOpen: boolean;
  videoRef: RefObject<HTMLVideoElement | null>;
  chosenTracks: MutableRefObject<{ audio: number; subs: number | null }>;
  actions: PlaybackStatusActions;
  onTerminate: (status: number) => void;
}): RemuxController {
  const {
    lease, leaseRef, contentId, sourceKey, catchup, eligible, checking, blocked, inRoom,
    trackMenuOpen, videoRef, chosenTracks, actions, onTerminate,
  } = opts;
  const { setError, setTracks } = actions;

  const [session, setSession] = useState<RemuxSession | null>(null);
  const [state, setState] = useState<RemuxState>('idle');
  const [available, setAvailable] = useState<boolean | null>(null);
  const ref = useRef(session);
  useEffect(() => { ref.current = session; }, [session]);
  const availableRef = useRef(available);
  useEffect(() => { availableRef.current = available; }, [available]);
  const forceTranscode = useRef(false);

  const release = useCallback((id: string) => {
    const current = leaseRef.current;
    api.remuxStop(id, current.id, current.mediaGrant);
  }, [leaseRef]);

  useEffect(() => {
    // Switching files: release the old session (frees its provider connection).
    const previous = ref.current;
    if (previous) release(previous.id);
    setSession(null);
    setState('idle');
    forceTranscode.current = false;
    setError(null);
  }, [sourceKey, lease.id, release, setError]);

  useEffect(() => {
    api.remuxAvailable().then((r) => setAvailable(r.available)).catch(() => setAvailable(false));
  }, []);

  // Closing releases the session so nothing keeps reading the provider.
  useEffect(() => () => {
    if (ref.current) release(ref.current.id);
  }, [lease.id, release]);

  const startRemux = useCallback(async (audio: number, startAt: number) => {
    const audioIndex = Math.max(0, audio);
    const previousId = ref.current?.id;
    setState('loading');
    try {
      // Copy (not transcode) HEVC when the browser can decode it, unless a prior copy failed.
      const hevc = hevcCapable && !forceTranscode.current;
      // The tab id lets the server group this read: alone it's ours, in a room it's shared, and
      // there the room's audio track overrides what we asked - result.audio is what it used.
      const currentLease = leaseRef.current;
      const result = await api.remuxStart(currentLease.remuxStartUrl, audioIndex, catchup, hevc);
      // Switching audio or share group makes a new session; release the old one so this viewer
      // never holds two of the provider's connections at once.
      if (previousId && previousId !== result.id) {
        api.remuxStop(previousId, currentLease.id, currentLease.mediaGrant);
      }
      chosenTracks.current.audio = result.audio;
      setSession({
        id: result.id,
        playlistUrl: result.playlistUrl,
        duration: result.duration ?? ref.current?.duration ?? null,
        startAt,
        nativeCopy: result.nativeVideoCopy,
        audio: result.audio,
      });
      setTracks({
        audio: { names: result.audioTracks, current: result.audio },
        subs: { names: result.subtitleTracks ?? [], current: chosenTracks.current.subs ?? -1 },
      });
    } catch (cause) {
      if (cause instanceof ApiError && isTerminalPlaybackStatus(cause.status)) {
        onTerminate(cause.status);
        return;
      }
      // Provider connection limit reached: surface it as a player error like the
      // decode failure, not a passing snackbar.
      if ((cause as ApiError).status === 429) {
        setState('failed');
        setSession(null);
        setError(t('player.connectionLimit'));
        return;
      }
      // "No additional tracks" is normal (source plays directly); anything else surfaces.
      const noTracks = /no additional tracks/i.test((cause as Error).message);
      setState(noTracks ? 'none' : 'failed');
      setSession(null);
      if (!noTracks) snackbar((cause as Error).message);
    }
  }, [catchup, chosenTracks, leaseRef, onTerminate, setError, setTracks]);

  // Engine closures re-request the session without re-running on every prepare.
  const startRef = useRef(startRemux);
  startRef.current = startRemux;
  const start = useCallback((audio: number, startAt: number) => {
    void startRef.current(audio, startAt);
  }, []);

  // Prepare when the file opens, at the saved resume position so hls.js starts there and the
  // track menus are populated early. Held off until the connection check clears, so a full
  // provider isn't opened just to be refused.
  useEffect(() => {
    if (!eligible || available !== true || session || state !== 'idle') return;
    if (checking || blocked) return;
    let cancelled = false;
    (async () => {
      const points = catchup ? [] : await api.resumeAll().catch(() => [] as ResumePoint[]);
      if (cancelled) return;
      const point = points.find((x) => x.contentId === contentId);
      const at = point && point.positionMs >= 10_000 ? Math.floor(point.positionMs / 1000) : 0;
      void startRemux(Math.max(0, chosenTracks.current.audio), at);
    })();
    return () => { cancelled = true; };
  }, [
    eligible, available, session, state, sourceKey, catchup,
    startRemux, checking, blocked, contentId, chosenTracks,
  ]);

  // Joining or leaving a room changes the share group, so the server keys the remux to a
  // different session: joiners collapse onto the room's one shared read, a leaver splits back to
  // its own. Re-request at the current spot when membership flips. If none is running yet (the
  // viewer was blocked on a full provider, or is still preparing), the prepare effect above starts
  // it with the new group instead.
  const wasInRoom = useRef(false);
  useEffect(() => {
    if (inRoom === wasInRoom.current) return;
    const joined = inRoom && !wasInRoom.current;
    wasInRoom.current = inRoom;
    if (!eligible || available !== true || !ref.current) return;
    if (joined) {
      // Collapse onto the room's shared read at the current spot.
      void startRemux(Math.max(0, chosenTracks.current.audio), videoRef.current?.currentTime ?? 0);
    } else {
      // Left or kicked: drop the room's shared read now (so we don't keep playing a session we're
      // no longer part of); the re-check then blocks us if the provider is full, or the prepare
      // effect starts our own solo read.
      release(ref.current.id);
      setSession(null);
      setState('idle');
    }
  }, [inRoom, eligible, available, startRemux, release, chosenTracks, videoRef]);

  // The remux is the only path to sound for undecodable audio (AC3/E-AC3/DTS), so a failed
  // prepare can't be left as silent direct playback. Usual cause: a single-connection provider
  // where the first ffprobe still holds the connection; the probe is then cached, so retries
  // (with backoff) generally get in.
  const retries = useRef(0);
  useEffect(() => { retries.current = 0; }, [sourceKey]);
  useEffect(() => {
    if (state !== 'failed' || !eligible || available !== true) return;
    if (retries.current >= MAX_AUTOMATIC_RETRIES) return;
    retries.current += 1;
    const timer = setTimeout(
      () => void startRemux(Math.max(0, chosenTracks.current.audio), 0),
      1500 * retries.current,
    );
    return () => clearTimeout(timer);
  }, [state, eligible, available, startRemux, chosenTracks]);

  // One extra retry per track-menu opening, after the automatic ones are used up.
  const menuRetried = useRef(false);
  useEffect(() => {
    if (!trackMenuOpen) { menuRetried.current = false; return; }
    if (menuRetried.current || state !== 'failed' || !eligible || available !== true) return;
    menuRetried.current = true;
    void startRemux(Math.max(0, chosenTracks.current.audio), 0);
  }, [trackMenuOpen, state, eligible, available, startRemux, chosenTracks]);

  const markPlaying = useCallback(() => {
    setState((current) => (current === 'loading' ? 'idle' : current));
  }, []);

  const markDied = useCallback(() => {
    // The remux stream died, or the browser couldn't decode a copied HEVC it claimed to
    // support: force a transcode on the retry and re-anchor.
    if (ref.current?.nativeCopy) forceTranscode.current = true;
    setSession(null);
    setState('failed');
  }, []);

  return {
    session, state, available, ref, availableRef, forceTranscode, start, markPlaying, markDied,
  };
}
