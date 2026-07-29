// The server-side remux: the file re-served as a VOD HLS playlist so every track is exposed,
// undecodable audio is normalized, and catch-up gets a seekable timeline.
//
// Non-live files (VOD, downloads, raw-TS VOD) and catch-up all go through it. A session is
// keyed by the audio track and the share group, so switching either re-requests one and
// releases the old read - this viewer never holds two of the provider's connections at once.

import { MutableRefObject, RefObject, useCallback, useEffect, useRef, useState } from 'react';
import { api, ApiError, PlaybackLease, ResumePoint } from '../api';
import { reportError } from '../errors';
import { t } from '../i18n';
import { isTerminalPlaybackStatus } from './mediaGrant';
import { PlaybackStatusActions } from './playbackStatus';

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
  recoverMediaGrant: () => Promise<boolean>;
  onTerminate: (status: number) => void;
}): RemuxController {
  const {
    lease, leaseRef, contentId, sourceKey, catchup, eligible, checking, blocked, inRoom,
    trackMenuOpen, videoRef, chosenTracks, actions, recoverMediaGrant, onTerminate,
  } = opts;
  const { setError, setTracks } = actions;

  const [session, setSession] = useState<RemuxSession | null>(null);
  const [state, setState] = useState<RemuxState>('idle');
  const [available, setAvailable] = useState<boolean | null>(null);
  const ref = useRef(session);
  useEffect(() => { ref.current = session; }, [session]);
  const requestSequence = useRef(0);
  const availableRef = useRef(available);
  useEffect(() => { availableRef.current = available; }, [available]);
  const lastStartAt = useRef(0);
  const reopenAt = useCallback(
    () => videoRef.current?.currentTime || lastStartAt.current,
    [videoRef],
  );

  const release = useCallback((id: string) => {
    const current = leaseRef.current;
    api.remuxStop(id, current.id, current.mediaGrant);
  }, [leaseRef]);

  useEffect(() => {
    // Switching files: release the old session (frees its provider connection).
    requestSequence.current++;
    const previous = ref.current;
    if (previous) release(previous.id);
    ref.current = null;
    setSession(null);
    setState('idle');
    lastStartAt.current = 0;
    setError(null);
  }, [sourceKey, lease.id, release, setError]);

  useEffect(() => {
    let active = true;
    api.remuxAvailable()
      .then((r) => { if (active) setAvailable(r.available); })
      .catch(() => { if (active) setAvailable(false); });
    return () => { active = false; };
  }, []);

  // Closing releases the session so nothing keeps reading the provider.
  useEffect(() => () => {
    requestSequence.current++;
    const current = ref.current;
    ref.current = null;
    if (current) release(current.id);
  }, [release]);

  const startRemux = useCallback(async (audio: number, startAt: number) => {
    const request = ++requestSequence.current;
    const audioIndex = Math.max(0, audio);
    const previousId = ref.current?.id;
    const requestLease = leaseRef.current;
    lastStartAt.current = startAt;
    setState('loading');
    try {
      // The tab id lets the server group this read: alone it's ours, in a room it's shared, and
      // there the room's audio track overrides what we asked - result.audio is what it used.
      const result = await api.remuxStart(requestLease.remuxStartUrl, audioIndex, catchup);
      const currentLease = leaseRef.current;
      if (request !== requestSequence.current || currentLease.id !== requestLease.id) {
        if (ref.current?.id !== result.id) {
          api.remuxStop(
            result.id,
            requestLease.id,
            currentLease.id === requestLease.id
              ? currentLease.mediaGrant
              : requestLease.mediaGrant,
          );
        }
        return;
      }
      // Switching audio or share group makes a new session; release the old one so this viewer
      // never holds two of the provider's connections at once.
      if (previousId && previousId !== result.id) {
        api.remuxStop(previousId, currentLease.id, currentLease.mediaGrant);
      }
      chosenTracks.current.audio = result.audio;
      const next = {
        id: result.id,
        playlistUrl: result.playlistUrl,
        duration: result.duration ?? ref.current?.duration ?? null,
        startAt,
        nativeCopy: result.nativeVideoCopy,
        audio: result.audio,
      };
      ref.current = next;
      setSession(next);
      setTracks({
        audio: { names: result.audioTracks, current: result.audio },
        subs: { names: result.subtitleTracks ?? [], current: chosenTracks.current.subs ?? -1 },
      });
    } catch (cause) {
      if (request !== requestSequence.current) return;
      if (cause instanceof ApiError && cause.status === 410) {
        const recovered = await recoverMediaGrant();
        if (recovered && request === requestSequence.current) {
          void startRemux(audio, startAt);
        }
        return;
      }
      if (cause instanceof ApiError && isTerminalPlaybackStatus(cause.status)) {
        onTerminate(cause.status);
        return;
      }
      // Provider connection limit reached: surface it as a player error like the
      // decode failure, not a passing toast.
      if ((cause as ApiError).status === 429) {
        setState('failed');
        setSession(null);
        setError(t('player.connectionLimit'));
        return;
      }
      // "No additional tracks" is normal (source plays directly); anything else surfaces.
      const noTracks = cause instanceof ApiError && cause.code === 'no_extra_tracks';
      setState(noTracks ? 'none' : 'failed');
      setSession(null);
      if (!noTracks) reportError(cause);
    }
  }, [
    catchup, chosenTracks, leaseRef, onTerminate, recoverMediaGrant, setError, setTracks,
  ]);

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
      const saved = point && point.positionMs >= 10_000 ? Math.floor(point.positionMs / 1000) : 0;
      void startRemux(Math.max(0, chosenTracks.current.audio), reopenAt() || saved);
    })();
    return () => { cancelled = true; };
  }, [
    eligible, available, session, state, sourceKey, catchup,
    startRemux, reopenAt, checking, blocked, contentId, chosenTracks,
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
      void startRemux(Math.max(0, chosenTracks.current.audio), reopenAt());
    } else {
      // Left or kicked: drop the room's shared read now (so we don't keep playing a session we're
      // no longer part of); the re-check then blocks us if the provider is full, or the prepare
      // effect starts our own solo read.
      lastStartAt.current = reopenAt();
      release(ref.current.id);
      ref.current = null;
      setSession(null);
      setState('idle');
    }
  }, [inRoom, eligible, available, startRemux, reopenAt, release, chosenTracks]);

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
      () => void startRemux(Math.max(0, chosenTracks.current.audio), reopenAt()),
      1500 * retries.current,
    );
    return () => clearTimeout(timer);
  }, [state, eligible, available, startRemux, reopenAt, chosenTracks]);

  // One extra retry per track-menu opening, after the automatic ones are used up.
  const menuRetried = useRef(false);
  useEffect(() => {
    if (!trackMenuOpen) { menuRetried.current = false; return; }
    if (menuRetried.current || state !== 'failed' || !eligible || available !== true) return;
    menuRetried.current = true;
    void startRemux(Math.max(0, chosenTracks.current.audio), reopenAt());
  }, [trackMenuOpen, state, eligible, available, startRemux, reopenAt, chosenTracks]);

  const markPlaying = useCallback(() => {
    setState((current) => (current === 'loading' ? 'idle' : current));
  }, []);

  const markDied = useCallback(() => {
    requestSequence.current++;
    ref.current = null;
    setSession(null);
    setState('failed');
  }, []);

  return {
    session, state, available, ref, availableRef, start, markPlaying, markDied,
  };
}
