// Reports the active player's state to the server so the activity dashboard can
// show who is watching what, and applies remote pause/play/message commands the
// admin queues. Web sessions only; kept isolated from the playback engine.

import { RefObject, useEffect, useRef } from 'react';
import { api, SessionCommand, SessionHeartbeat } from '../api';
import { snackbar } from '../components/Primitives';

/** Live playback facts, read fresh on each heartbeat via a ref. */
export interface PlaybackSnapshot {
  playlistId: number | null;
  title: string;
  kind: SessionHeartbeat['kind'];
  logo: string | null;
  live: boolean;
  durationSec: number;
  engine: SessionHeartbeat['engine'];
  direct: boolean;
  audioTranscoded: boolean;
  preparing: boolean;
  remuxId: string | null;
}

const HEARTBEAT_MS = 3000;

/** Stable per browser tab, so navigating between channels stays one session. */
function tabSessionId(): string {
  let id = sessionStorage.getItem('opentvSessionId');
  if (!id) {
    id = (crypto.randomUUID?.() ?? `s-${Date.now()}-${Math.random().toString(36).slice(2)}`);
    sessionStorage.setItem('opentvSessionId', id);
  }
  return id;
}

function applyCommand(command: SessionCommand, video: HTMLVideoElement) {
  if (command.type === 'pause') video.pause();
  else if (command.type === 'play') video.play().catch(() => {});
  else if (command.type === 'message' && command.text) snackbar(command.text, undefined);
}

export function useSessionReporter(snapshot: PlaybackSnapshot, video: RefObject<HTMLVideoElement>) {
  const snapRef = useRef(snapshot);
  snapRef.current = snapshot;

  useEffect(() => {
    const id = tabSessionId();
    let stopped = false;

    const beat = async () => {
      const v = video.current;
      if (!v || stopped) return;
      const s = snapRef.current;
      const duration = s.durationSec;
      const body: SessionHeartbeat = {
        id,
        playlistId: s.playlistId,
        title: s.title,
        kind: s.kind,
        logo: s.logo,
        positionMs: Math.floor((v.currentTime || 0) * 1000),
        durationMs: isFinite(duration) && duration > 0 ? Math.floor(duration * 1000) : 0,
        paused: v.paused,
        live: s.live,
        engine: s.engine,
        direct: s.direct,
        audioTranscoded: s.audioTranscoded,
        preparing: s.preparing,
        remuxId: s.remuxId,
      };
      const { commands } = await api.sessionHeartbeat(body);
      if (stopped) return;
      const el = video.current;
      if (el) commands.forEach((c) => applyCommand(c, el));
    };

    beat();
    const timer = setInterval(beat, HEARTBEAT_MS);

    // Push channel: pause/play/message arrive instantly instead of on the next beat.
    let ws: WebSocket | null = null;
    let wsRetry: ReturnType<typeof setTimeout> | undefined;
    const connect = () => {
      if (stopped) return;
      ws = new WebSocket(api.sessionSocketUrl(id));
      ws.onmessage = (ev) => {
        const el = video.current;
        if (!el) return;
        try { applyCommand(JSON.parse(ev.data as string) as SessionCommand, el); } catch { /* ignore */ }
      };
      ws.onclose = () => { if (!stopped) wsRetry = setTimeout(connect, HEARTBEAT_MS); };
      ws.onerror = () => ws?.close();
    };
    connect();

    return () => {
      stopped = true;
      clearInterval(timer);
      clearTimeout(wsRetry);
      if (ws) { ws.onclose = null; ws.close(); }
      api.sessionEnd(id);
    };
  }, [video]);
}
