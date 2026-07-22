// Watch-together: two viewers on the same content share a room where the host drives
// playback and the guest mirrors it. Handshake and sync ride the session command channel
// (useSessionReporter), so there is no second socket. Trying to watch something else while
// the provider is full surfaces a connection-limit message instead.

import { ReactNode, RefObject, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { api, SessionCommand } from '../api';
import { snackbar } from '../components/Primitives';
import { deviceLabel } from '../lib/format';
import { t } from '../i18n';

const SYNC_MS = 2000;
// Guests only re-seek past this drift, so small buffering differences don't fight playback.
const DRIFT_SEC = 2.5;

// A room can hold any number of viewers; only the host drives, everyone else mirrors.
type RoomState = { role: 'host' | 'guest' };

export interface WatchTogether {
  room: RoomState | null;
  /** The one-time "someone's already watching this" prompt. */
  offer: boolean;
  /** Others are on this content and we're not in a room yet: a re-ask stays available. */
  canAsk: boolean;
  incoming: { peerId: string; peerName: string } | null;
  limited: boolean;
  acceptOffer: () => void;
  dismissOffer: () => void;
  ask: () => void;
  answer: (accept: boolean) => void;
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
  const [incoming, setIncoming] = useState<{ peerId: string; peerName: string } | null>(null);
  const [limited, setLimited] = useState(false);

  const deviceName = useMemo(() => deviceLabel(navigator.userAgent) || t('watch.someone'), []);
  const contentRef = useRef(contentKey); contentRef.current = contentKey;
  const liveRef = useRef(live); liveRef.current = live;
  const roomRef = useRef(room); roomRef.current = room;
  const hostsRef = useRef(hosts); hostsRef.current = hosts;
  const incomingRef = useRef(incoming); incomingRef.current = incoming;
  // The content the room was formed on, so navigating elsewhere drops us out of it.
  const roomContent = useRef<string | null>(null);

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
      setRoom({ role: 'host' });
    }
    setIncoming(null);
  }, [selfId, deviceName]);

  const applySync = useCallback((sync: NonNullable<SessionCommand['sync']>) => {
    const v = video.current;
    if (!v) return;
    if (sync.paused && !v.paused) v.pause();
    else if (!sync.paused && v.paused) v.play().catch(() => {});
    if (sync.rate > 0 && v.playbackRate !== sync.rate) v.playbackRate = sync.rate;
    if (!liveRef.current) {
      const target = sync.positionMs / 1000;
      if (isFinite(target) && Math.abs(v.currentTime - target) > DRIFT_SEC) v.currentTime = target;
    }
  }, [video]);

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
    } else if (command.type === 'join-response') {
      if (command.accepted) {
        roomContent.current = contentRef.current;
        setRoom({ role: 'guest' });
        snackbar(t('watch.joined'));
      } else {
        snackbar(t('watch.declined'));
      }
    } else if (command.type === 'sync' && command.sync) {
      applySync(command.sync);
    } else if (command.type === 'host') {
      // Promoted because the previous host left: start driving.
      setRoom((current) => (current ? { role: 'host' } : current));
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

  // New content: clear any stale prompt, and drop a room that belonged to the old content.
  useEffect(() => {
    setOffer(false);
    setHosts([]);
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

  // Host drives: push its state on a tick and on every transport change.
  useEffect(() => {
    if (room?.role !== 'host') return;
    const v = video.current;
    if (!v) return;
    const broadcast = () => api.sessionSync(selfId, {
      positionMs: Math.floor((v.currentTime || 0) * 1000),
      paused: v.paused,
      rate: v.playbackRate || 1,
    });
    broadcast();
    const timer = setInterval(broadcast, SYNC_MS);
    v.addEventListener('play', broadcast);
    v.addEventListener('pause', broadcast);
    v.addEventListener('seeked', broadcast);
    return () => {
      clearInterval(timer);
      v.removeEventListener('play', broadcast);
      v.removeEventListener('pause', broadcast);
      v.removeEventListener('seeked', broadcast);
    };
  }, [room, selfId, video]);

  return {
    room, offer, incoming, limited, canAsk: hosts.length > 0 && !room,
    acceptOffer, dismissOffer, ask, answer, leave, onCommand,
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
    </>
  );
}
