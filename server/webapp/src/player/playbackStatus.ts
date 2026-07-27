// Everything the player chrome shows about the current stream. The engine and the media
// element both report into it, so neither has to know what the other observed.

import { Dispatch, SetStateAction, useMemo, useState } from 'react';

export interface TrackMenu { names: string[]; current: number }
export interface TrackMenus { audio: TrackMenu; subs: TrackMenu }

export const NO_TRACKS: TrackMenus = {
  audio: { names: [], current: -1 },
  subs: { names: [], current: -1 },
};

export interface PlaybackStatus {
  error: string | null;
  paused: boolean;
  buffering: boolean;
  bufferedEnd: number;
  /** Live audio the browser couldn't decode, rescued via the server's AAC transcode. */
  audioTranscoded: boolean;
  time: { position: number; duration: number };
  tracks: TrackMenus;
  /** Active cue text, drawn by our own overlay rather than by the browser. */
  cueText: string;
}

/** Stable for the player's lifetime, so effects can depend on it without re-running. */
export interface PlaybackStatusActions {
  setError: Dispatch<SetStateAction<string | null>>;
  setPaused: Dispatch<SetStateAction<boolean>>;
  setBuffering: Dispatch<SetStateAction<boolean>>;
  setBufferedEnd: Dispatch<SetStateAction<number>>;
  setAudioTranscoded: Dispatch<SetStateAction<boolean>>;
  setTime: Dispatch<SetStateAction<{ position: number; duration: number }>>;
  setTracks: Dispatch<SetStateAction<TrackMenus>>;
  setCueText: Dispatch<SetStateAction<string>>;
}

export function usePlaybackStatus(): { status: PlaybackStatus; actions: PlaybackStatusActions } {
  const [error, setError] = useState<string | null>(null);
  const [paused, setPaused] = useState(false);
  const [buffering, setBuffering] = useState(true);
  const [bufferedEnd, setBufferedEnd] = useState(0);
  const [audioTranscoded, setAudioTranscoded] = useState(false);
  const [time, setTime] = useState({ position: 0, duration: NaN });
  const [tracks, setTracks] = useState<TrackMenus>(NO_TRACKS);
  const [cueText, setCueText] = useState('');

  const actions = useMemo<PlaybackStatusActions>(() => ({
    setError, setPaused, setBuffering, setBufferedEnd, setAudioTranscoded, setTime, setTracks, setCueText,
  }), []);

  return {
    status: { error, paused, buffering, bufferedEnd, audioTranscoded, time, tracks, cueText },
    actions,
  };
}
