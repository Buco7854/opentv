// Reports the active player's state to the server so the activity dashboard can
// show who is watching what, and applies remote pause/play/message commands the
// admin queues. Web sessions only; kept isolated from the playback engine.

import { MutableRefObject, RefObject, useEffect, useRef } from 'react';
import {
  api, ApiError, SessionCommand, SessionCommandInput, SessionHeartbeat,
} from '../api';
import { toast } from '../components/Primitives';
import { isTerminalPlaybackStatus } from './mediaGrant';

/** Live playback facts, read fresh on each heartbeat via a ref. */
export interface PlaybackSnapshot {
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

function applyCommand(command: SessionCommand, video: HTMLVideoElement) {
  if (command.type === 'pause') video.pause();
  else if (command.type === 'play') video.play().catch(() => {});
  else if (command.type === 'message' && command.text) toast(command.text);
}

/**
 * Reports playback and delivers admin pause/play/message commands. [onCommand] also gets
 * every command (including watch-together ones), so the room layer can react without
 * opening a second socket.
 */
export function useSessionReporter(
  leaseId: string,
  snapshot: PlaybackSnapshot,
  video: RefObject<HTMLVideoElement | null>,
  onCommand?: (command: SessionCommand) => void,
  /** Filled with a sender that pushes a frame over the live socket (false if it's down),
   *  so the room layer can send sync in real time instead of POSTing. */
  wsSend?: MutableRefObject<((command: SessionCommandInput) => boolean) | null>,
  onRevoked?: () => void,
) {
  const snapRef = useRef(snapshot);
  snapRef.current = snapshot;
  const cmdRef = useRef(onCommand);
  cmdRef.current = onCommand;
  const revokedRef = useRef(onRevoked);
  revokedRef.current = onRevoked;

  useEffect(() => {
    const id = leaseId;
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
      // Over the socket while it's up (commands come back via onmessage); POST otherwise.
      if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({ type: 'heartbeat', heartbeat: body }));
        return;
      }
      try {
        const { commands } = await api.playbackHeartbeat(id, body);
        if (stopped) return;
        const el = video.current;
        if (el) commands.forEach((c) => handle(c, el));
      } catch (cause) {
        if (cause instanceof ApiError && isTerminalPlaybackStatus(cause.status)) {
          stopped = true;
          revokedRef.current?.();
        }
      }
    };

    // Push channel: commands arrive instantly; the client also sends heartbeat/sync over it.
    let wsRetry: ReturnType<typeof setTimeout> | undefined;
    const connect = () => {
      if (stopped) return;
      ws = new WebSocket(api.playbackSocketUrl(id));
      ws.onmessage = (ev) => {
        const el = video.current;
        if (!el) return;
        try { handle(JSON.parse(ev.data as string) as SessionCommand, el); } catch { /* ignore */ }
      };
      ws.onclose = (event) => {
        if (stopped) return;
        if (event.code === 1000 && /lease ended|revoked/i.test(event.reason)) {
          stopped = true;
          revokedRef.current?.();
          return;
        }
        wsRetry = setTimeout(connect, HEARTBEAT_MS);
      };
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

    return () => {
      stopped = true;
      clearInterval(timer);
      clearTimeout(wsRetry);
      if (wsSend) wsSend.current = null;
      if (ws) { ws.onclose = null; ws.close(); }
    };
  }, [leaseId, video, wsSend]);
}
