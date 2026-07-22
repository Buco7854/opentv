// Watch-together: viewers on the same content share a room. The host owns it and anchors
// the timeline; it can grant control to guests, and everyone with control drives playback
// for the rest. Handshake and sync ride the session command channel (useSessionReporter),
// so there is no second socket. Trying to watch something else while the provider is full
// surfaces a connection-limit message instead.

import { ReactNode, RefObject, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { api, SessionCommand } from '../api';
import { snackbar } from '../components/Primitives';
import { deviceLabel } from '../lib/format';
import { t } from '../i18n';

const SYNC_MS = 2000;
// Mirrors only re-seek past this drift, so small buffering differences don't fight playback.
const DRIFT_SEC = 2.5;

// A room holds any number of viewers. `canControl` is the host plus whoever it allowed.
type RoomState = { role: 'host' | 'guest'; canControl: boolean };
type Peer = { peerId: string; peerName: string };

export interface WatchTogether {
  room: RoomState | null;
  /** The one-time "someone's already watching this" prompt. */
  offer: boolean;
  /** Others are on this content and we're not in a room yet: a re-ask stays available. */
  canAsk: boolean;
  /** A pending join request to answer (host of a would-be room). */
  incoming: Peer | null;
  /** A pending control request to answer (room host). */
  controlIncoming: Peer | null;
  limited: boolean;
  acceptOffer: () => void;
  dismissOffer: () => void;
  ask: () => void;
  answer: (accept: boolean) => void;
  requestControl: () => void;
  grantControl: (grant: boolean) => void;
  leave: () => void;
  /** Pass to useSessionReporter so room commands reach this hook. */
  onCommand: (command: SessionCommand) => void;
}

export function useWatchTogether(opts: {
  selfId: string;
  video: RefObject<HTMLVideoElement>;
  /** The player is up on real content (not an error), so an intent check is worthwhile. */
  active: boolean;
  live: boolean;
  contentKey: string;
  /** Encrypted source token, for reading the provider's connection limit. */
  source: string | null;
  playlistId: number | null;
}): WatchTogether {
  const { selfId, video, active, live, contentKey, source, playlistId } = opts;

  const [room, setRoom] = useState<RoomState | null>(null);
  const [offer, setOffer] = useState(false);
  // Session ids on this same content that we could ask to watch together.
  const [hosts, setHosts] = useState<string[]>([]);
  const [incoming, setIncoming] = useState<Peer | null>(null);
  const [controlIncoming, setControlIncoming] = useState<Peer | null>(null);
  const [limited, setLimited] = useState(false);

  const deviceName = useMemo(() => deviceLabel(navigator.userAgent) || t('watch.someone'), []);
  const contentRef = useRef(contentKey); contentRef.current = contentKey;
  const liveRef = useRef(live); liveRef.current = live;
  const roomRef = useRef(room); roomRef.current = room;
  const hostsRef = useRef(hosts); hostsRef.current = hosts;
  const incomingRef = useRef(incoming); incomingRef.current = incoming;
  const controlIncomingRef = useRef(controlIncoming); controlIncomingRef.current = controlIncoming;
  // The content the room was formed on, so navigating elsewhere drops us out of it.
  const roomContent = useRef<string | null>(null);
  // The last state we applied from a peer, so our own resulting events aren't echoed back.
  const lastApplied = useRef<{ positionMs: number; paused: boolean; rate: number } | null>(null);

  const leave = useCallback(() => {
    api.sessionLeave(selfId);
    roomContent.current = null;
    setRoom(null);
  }, [selfId]);

  const respond = useCallback((peerId: string, accept: boolean) => {
    api.joinAnswer(selfId, peerId, deviceName, contentRef.current, accept).catch(() => {});
    // Admitting someone while solo makes us the room's host; if already in a room we just
    // let them in and keep our current role (the server adds them to the same room).
    if (accept && !roomRef.current) {
      roomContent.current = contentRef.current;
      setRoom({ role: 'host', canControl: true });
    }
    setIncoming(null);
  }, [selfId, deviceName]);

  const applySync = useCallback((sync: NonNullable<SessionCommand['sync']>) => {
    const v = video.current;
    if (!v) return;
    lastApplied.current = { positionMs: sync.positionMs, paused: sync.paused, rate: sync.rate };
    if (sync.paused && !v.paused) v.pause();
    else if (!sync.paused && v.paused) v.play().catch(() => {});
    if (sync.rate > 0 && v.playbackRate !== sync.rate) v.playbackRate = sync.rate;
    if (!liveRef.current) {
      const target = sync.positionMs / 1000;
      if (isFinite(target) && Math.abs(v.currentTime - target) > DRIFT_SEC) v.currentTime = target;
    }
  }, [video]);

  // Send our playback state to the room. Controller events are guarded so applying a peer's
  // state doesn't bounce straight back; the host's periodic anchor always sends.
  const broadcast = useCallback((guarded: boolean) => {
    const v = video.current;
    if (!v) return;
    const state = { positionMs: Math.floor((v.currentTime || 0) * 1000), paused: v.paused, rate: v.playbackRate || 1 };
    const last = lastApplied.current;
    if (guarded && last && last.paused === state.paused && last.rate === state.rate
        && Math.abs(last.positionMs - state.positionMs) < 1500) return;
    api.sessionSync(selfId, state);
  }, [selfId, video]);

  const onCommand = useCallback((command: SessionCommand) => {
    if (command.type === 'join-request' && command.peerId) {
      const peerId = command.peerId;
      const name = command.peerName || t('watch.someone');
      if (command.quiet) {
        snackbar(t('watch.askedQuiet', { name }),
          { label: t('watch.accept'), onClick: () => respond(peerId, true) });
      } else {
        setIncoming({ peerId, peerName: name });
      }
    } else if (command.type === 'control-request' && command.peerId) {
      setControlIncoming({ peerId: command.peerId, peerName: command.peerName || t('watch.someone') });
    } else if (command.type === 'join-response') {
      if (command.accepted) {
        roomContent.current = contentRef.current;
        setRoom({ role: 'guest', canControl: false });
        snackbar(t('watch.joined'));
      } else {
        snackbar(t('watch.declined'));
      }
    } else if (command.type === 'control-response') {
      snackbar(command.accepted ? t('watch.controlGranted') : t('watch.controlDenied'));
      if (command.accepted) setRoom((current) => (current ? { ...current, canControl: true } : current));
    } else if (command.type === 'sync' && command.sync) {
      applySync(command.sync);
    } else if (command.type === 'host') {
      // The previous host left: we now own the room and can control it.
      setRoom((current) => (current ? { role: 'host', canControl: true } : current));
    } else if (command.type === 'room-ended') {
      roomContent.current = null;
      setRoom((current) => { if (current) snackbar(t('watch.ended')); return null; });
    }
    // "peer-left" needs no action: the room keeps going with the rest.
  }, [respond, applySync]);

  // Ask the first peer on this content; repeat asks after a decline reach them quietly
  // (the server remembers the refusal, so they get a subtle notice, not another modal).
  const ask = useCallback(() => {
    const target = hostsRef.current[0];
    if (!target) return;
    snackbar(t('watch.requesting'));
    api.joinRequest(target, selfId, deviceName, contentRef.current)
      .catch(() => snackbar(t('watch.requestFailed')));
  }, [selfId, deviceName]);

  const acceptOffer = useCallback(() => { setOffer(false); ask(); }, [ask]);
  const dismissOffer = useCallback(() => setOffer(false), []);

  const answer = useCallback((accept: boolean) => {
    const current = incomingRef.current;
    if (current) respond(current.peerId, accept);
  }, [respond]);

  // Guest asks the host for control.
  const requestControl = useCallback(() => {
    snackbar(t('watch.controlAsked'));
    api.requestControl(selfId, deviceName).catch(() => {});
  }, [selfId, deviceName]);

  // Host answers a pending control request.
  const grantControl = useCallback((grant: boolean) => {
    const current = controlIncomingRef.current;
    if (current) api.grantControl(selfId, current.peerId, grant).catch(() => {});
    setControlIncoming(null);
  }, [selfId]);

  // New content: clear any stale prompt, and drop a room that belonged to the old content.
  useEffect(() => {
    setOffer(false);
    setHosts([]);
    setControlIncoming(null);
    setLimited(false);
    if (roomContent.current && roomContent.current !== contentKey) leave();
  }, [contentKey, leave]);

  // Ask the server who else is here and whether the provider is full - once per content.
  const checked = useRef<string | null>(null);
  useEffect(() => {
    if (!active || !contentKey || room || checked.current === contentKey) return;
    checked.current = contentKey;
    let cancelled = false;
    api.watchIntent(selfId, contentKey, source, playlistId).then((intent) => {
      if (cancelled) return;
      if (intent.sameContent.length > 0) { setHosts(intent.sameContent); setOffer(true); }
      else if (intent.atLimit) setLimited(true);
    }).catch(() => {});
    return () => { cancelled = true; };
  }, [active, contentKey, room, selfId, source, playlistId]);

  // The host anchors the shared timeline on a tick, so drift is corrected and a fresh
  // joiner catches up to where everyone else is.
  useEffect(() => {
    if (room?.role !== 'host') return;
    const v = video.current;
    if (!v) return;
    broadcast(false);
    const timer = setInterval(() => broadcast(false), SYNC_MS);
    return () => clearInterval(timer);
  }, [room, broadcast, video]);

  // Anyone with control drives: their play/pause/seek reaches the rest of the room.
  useEffect(() => {
    if (!room?.canControl) return;
    const v = video.current;
    if (!v) return;
    const onEvt = () => broadcast(true);
    v.addEventListener('play', onEvt);
    v.addEventListener('pause', onEvt);
    v.addEventListener('seeked', onEvt);
    return () => {
      v.removeEventListener('play', onEvt);
      v.removeEventListener('pause', onEvt);
      v.removeEventListener('seeked', onEvt);
    };
  }, [room, broadcast, video]);

  return {
    room, offer, incoming, controlIncoming, limited, canAsk: hosts.length > 0 && !room,
    acceptOffer, dismissOffer, ask, answer, requestControl, grantControl, leave, onCommand,
  };
}

/** A dialog portalled into [container] (the player frame) so it also shows in fullscreen. */
function PlayerDialog({ title, body, buttons, onDismiss, container }: {
  title: string;
  body: string;
  buttons: ReactNode;
  onDismiss: () => void;
  container: Element | null;
}) {
  return createPortal(
    <div className="scrim" onClick={(e) => { if (e.target === e.currentTarget) onDismiss(); }}>
      <div className="dialog">
        <h2>{title}</h2>
        <p className="type-body-medium text-on-surface-variant">{body}</p>
        <div className="buttons">{buttons}</div>
      </div>
    </div>,
    container ?? document.body,
  );
}

/** The join prompts, shown over the player frame. The room pill lives in PlayerSurface. */
export function WatchTogetherModals({ wt, container }: { wt: WatchTogether; container: Element | null }) {
  return (
    <>
      {wt.offer && (
        <PlayerDialog
          title={t('watch.offerTitle')} body={t('watch.offerBody')} container={container}
          onDismiss={wt.dismissOffer}
          buttons={
            <>
              <button className="btn text" onClick={wt.dismissOffer}>{t('watch.solo')}</button>
              <button className="btn text" onClick={wt.acceptOffer}>{t('watch.together')}</button>
            </>
          }
        />
      )}
      {wt.incoming && (
        <PlayerDialog
          title={t('watch.incomingTitle')} body={t('watch.incomingBody', { name: wt.incoming.peerName })}
          container={container} onDismiss={() => wt.answer(false)}
          buttons={
            <>
              <button className="btn text" onClick={() => wt.answer(false)}>{t('watch.decline')}</button>
              <button className="btn text" onClick={() => wt.answer(true)}>{t('watch.accept')}</button>
            </>
          }
        />
      )}
      {wt.controlIncoming && (
        <PlayerDialog
          title={t('watch.controlTitle')} body={t('watch.controlBody', { name: wt.controlIncoming.peerName })}
          container={container} onDismiss={() => wt.grantControl(false)}
          buttons={
            <>
              <button className="btn text" onClick={() => wt.grantControl(false)}>{t('watch.decline')}</button>
              <button className="btn text" onClick={() => wt.grantControl(true)}>{t('watch.allow')}</button>
            </>
          }
        />
      )}
    </>
  );
}
