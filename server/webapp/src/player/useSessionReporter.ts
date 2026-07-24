// Reports the active player's state to the server so the activity dashboard can
// show who is watching what, and applies remote pause/play/message commands the
// admin queues. Web sessions only; kept isolated from the playback engine.

import { MutableRefObject, RefObject, useEffect, useRef } from 'react';
import { api, SessionCommand, SessionCommandInput, SessionHeartbeat } from '../api';
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
  contentKey: string;
  name: string;
}

const HEARTBEAT_MS = 3000;

/** Stable per browser tab, so navigating between channels stays one session. */
export function tabSessionId(): string {
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

/**
 * Reports playback and delivers admin pause/play/message commands. [onCommand] also gets
 * every command (including watch-together ones), so the room layer can react without
 * opening a second socket.
 */
export function useSessionReporter(
  snapshot: PlaybackSnapshot,
  video: RefObject<HTMLVideoElement>,
  onCommand?: (command: SessionCommand) => void,
  /** Filled with a sender that pushes a frame over the live socket (false if it's down),
   *  so the room layer can send sync in real time instead of POSTing. */
  wsSend?: MutableRefObject<((command: SessionCommandInput) => boolean) | null>,
) {
  const snapRef = useRef(snapshot);
  snapRef.current = snapshot;
  const cmdRef = useRef(onCommand);
  cmdRef.current = onCommand;

  useEffect(() => {
    const id = tabSessionId();
    let stopped = false;

    let ws: WebSocket | null = null;

    const handle = (command: SessionCommand, el: HTMLVideoElement) => {
      applyCommand(command, el);
      cmdRef.current?.(command);
      // A pause/play changes what the dashboard should show: report it now instead of
      // waiting for the next tick, so the admin sees the real state within a round-trip.
      if (command.type === 'pause' || command.type === 'play') beat();
    };

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
        contentKey: s.contentKey,
        name: s.name,
      };
      // Over the socket while it's up (commands come back via onmessage); POST otherwise.
      if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({ type: 'heartbeat', heartbeat: body }));
        return;
      }
      const { commands } = await api.sessionHeartbeat(body);
      if (stopped) return;
      const el = video.current;
      if (el) commands.forEach((c) => handle(c, el));
    };

    // Push channel: commands arrive instantly; the client also sends heartbeat/sync over it.
    let wsRetry: ReturnType<typeof setTimeout> | undefined;
    const connect = () => {
      if (stopped) return;
      ws = new WebSocket(api.sessionSocketUrl(id));
      ws.onmessage = (ev) => {
        const el = video.current;
        if (!el) return;
        try { handle(JSON.parse(ev.data as string) as SessionCommand, el); } catch { /* ignore */ }
      };
      ws.onclose = () => { if (!stopped) wsRetry = setTimeout(connect, HEARTBEAT_MS); };
      ws.onerror = () => ws?.close();
    };
    connect();

    beat();
    const timer = setInterval(beat, HEARTBEAT_MS);

    if (wsSend) {
      wsSend.current = (command) => {
        if (ws && ws.readyState === WebSocket.OPEN) { ws.send(JSON.stringify(command)); return true; }
        return false;
      };
    }

    // A refresh/tab-close unloads the page: keep the session (and its watch-together room) so the
    // reloaded page rejoins it. Only a real navigate-away (page stays) ends the session.
    let unloading = false;
    const onPageHide = () => { unloading = true; };
    window.addEventListener('pagehide', onPageHide);

    return () => {
      stopped = true;
      clearInterval(timer);
      clearTimeout(wsRetry);
      window.removeEventListener('pagehide', onPageHide);
      if (wsSend) wsSend.current = null;
      if (ws) { ws.onclose = null; ws.close(); }
      if (!unloading) api.sessionEnd(id);
    };
  }, [video, wsSend]);
}
