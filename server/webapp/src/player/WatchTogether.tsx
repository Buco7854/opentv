// Watch-together: viewers on the same content share a room, reached from the group icon in
// the player's top bar (no banner over the video). The host owns the room and can grant
// control; every controller drives play/pause/seek and the rest mirror it in real time.
// Handshake, roster and sync all ride the session command channel (useSessionReporter), so
// there is no second socket. When the provider's connections are all in use and this stream
// would need its own, playback is blocked with a clear message instead of cutting someone off.

import { MutableRefObject, RefObject, useCallback, useEffect, useRef, useState } from 'react';
import { api, RoomMember, SessionCommand, SyncState, WatchIntentPeer } from '../api';
import { Sheet, snackbar } from '../components/Primitives';
import { t } from '../i18n';

// The driver's state is pushed the instant it plays/pauses/seeks, plus this tick to catch a
// fresh joiner up and re-align after buffering.
const ANCHOR_MS = 2000;
// A deliberate seek is applied when off by more than this (small, so it feels tight)...
const SEEK_SNAP_SEC = 0.75;
// ...while the periodic anchor only corrects a real desync (a joiner, a long buffer), so
// keyframe-rounding between players never turns into a tug-of-war of little seeks.
const ANCHOR_DRIFT_SEC = 4;
// After a seek, a stream (especially a remuxed VOD) takes a moment to land on the new spot; for
// this long we treat its position as still settling, so no one broadcasts or trusts a stale one.
const SETTLE_MS = 4000;
const SETTLE_TOL_MS = 1500;

type Peer = { peerId: string; peerName: string };

export interface WatchTogether {
  selfId: string;
  /** Show the group icon in the top bar. */
  available: boolean;
  /** A request is waiting (badge on the icon). */
  hasPending: boolean;
  inRoom: boolean;
  members: RoomMember[];
  isHost: boolean;
  canControl: boolean;
  /** Viewers on this content we could join (when not yet in a room). */
  peers: WatchIntentPeer[];
  joinRequests: Peer[];
  controlRequests: Peer[];
  /** Provider check in flight - hold playback so we never open a doomed connection. */
  checking: boolean;
  /** The provider is full and this stream needs its own connection: don't play. */
  blocked: boolean;
  /** Someone's already on this content: hold playback until the viewer picks alone or together. */
  choosing: boolean;
  /** Viewer chose to watch alone: release the hold and play (may then hit the provider limit). */
  watchAlone: () => void;
  ask: (peerId: string) => void;
  answerJoin: (peerId: string, accept: boolean) => void;
  requestControl: () => void;
  answerControl: (peerId: string, grant: boolean) => void;
  /** Host hands a member control (or takes it back) directly. */
  setControl: (id: string, grant: boolean) => void;
  kick: (id: string) => void;
  leave: () => void;
  /** Pass to useSessionReporter so room commands reach this hook. */
  onCommand: (command: SessionCommand) => void;
}

export function useWatchTogether(opts: {
  selfId: string;
  deviceName: string;
  video: RefObject<HTMLVideoElement>;
  /** The player is up on real content (not an error), so an intent check is worthwhile. */
  active: boolean;
  live: boolean;
  /** This content is served through the remux, so a same-content viewer shares its connection. */
  remuxEligible: boolean;
  contentKey: string;
  /** Encrypted source token, for reading the provider's connection limit. */
  source: string | null;
  playlistId: number | null;
  /** Sends a frame over the live socket (false when it's down); sync falls back to a POST. */
  send: MutableRefObject<((command: SessionCommand) => boolean) | null>;
  /** A controller changed the room's shared audio track: re-request the remux with it. */
  onRoomAudio?: (index: number) => void;
}): WatchTogether {
  const { selfId, deviceName, video, active, live, remuxEligible, contentKey, source, playlistId, send } = opts;
  const roomAudioRef = useRef(opts.onRoomAudio); roomAudioRef.current = opts.onRoomAudio;

  const [members, setMembers] = useState<RoomMember[]>([]);
  const [inRoom, setInRoom] = useState(false);
  const [peers, setPeers] = useState<WatchIntentPeer[]>([]);
  const [joinRequests, setJoinRequests] = useState<Peer[]>([]);
  const [controlRequests, setControlRequests] = useState<Peer[]>([]);
  const [checking, setChecking] = useState(true);
  const [blocked, setBlocked] = useState(false);
  // 'pending' while someone is already on this content and the viewer hasn't said whether to watch
  // alone or together - playback is held until they choose, so a seat is never taken by surprise.
  const [choice, setChoice] = useState<'pending' | 'decided'>('decided');

  const self = members.find((m) => m.id === selfId);
  const isHost = !!self?.host;
  const canControl = !!self?.controller;

  const contentRef = useRef(contentKey); contentRef.current = contentKey;
  const liveRef = useRef(live); liveRef.current = live;
  const remuxRef = useRef(remuxEligible); remuxRef.current = remuxEligible;
  // The content the room was formed on, so navigating elsewhere drops us out of it.
  const roomContent = useRef<string | null>(null);
  // The last state we applied from a peer (with when), so our own resulting events - which fire
  // within a few ms - aren't echoed straight back, while genuine actions later still send.
  const lastApplied = useRef<{ positionMs: number; paused: boolean; rate: number; atMs: number } | null>(null);
  // Where a just-issued seek is heading. Until our stream lands there (or it times out) we neither
  // broadcast our transient position nor let a stale drift anchor rewind us.
  const pendingSeek = useRef<{ ms: number; atMs: number } | null>(null);
  // True while a seek is still landing; clears once we're within tolerance of the target.
  const settling = useCallback((v: HTMLVideoElement) => {
    const p = pendingSeek.current;
    if (!p) return false;
    if (performance.now() - p.atMs > SETTLE_MS || Math.abs((v.currentTime || 0) * 1000 - p.ms) < SETTLE_TOL_MS) {
      pendingSeek.current = null;
      return false;
    }
    return true;
  }, []);

  const resetRoom = useCallback(() => {
    roomContent.current = null;
    setInRoom(false);
    setMembers([]);
    setJoinRequests([]);
    setControlRequests([]);
  }, []);

  const leave = useCallback(() => {
    api.sessionLeave(selfId);
    resetRoom();
  }, [selfId, resetRoom]);

  // Ask a specific viewer to watch together (there can be several on the same content).
  const ask = useCallback((peerId: string) => {
    snackbar(t('watch.requesting'));
    api.joinRequest(peerId, selfId, deviceName, contentRef.current)
      .catch(() => snackbar(t('watch.requestFailed')));
  }, [selfId, deviceName]);

  const answerJoin = useCallback((peerId: string, accept: boolean) => {
    api.joinAnswer(selfId, peerId, deviceName, contentRef.current, accept).catch(() => {});
    setJoinRequests((list) => list.filter((r) => r.peerId !== peerId));
  }, [selfId, deviceName]);

  const requestControl = useCallback(() => {
    snackbar(t('watch.controlAsked'));
    api.requestControl(selfId, deviceName).catch(() => {});
  }, [selfId, deviceName]);

  const answerControl = useCallback((peerId: string, grant: boolean) => {
    api.grantControl(selfId, peerId, grant).catch(() => {});
    setControlRequests((list) => list.filter((r) => r.peerId !== peerId));
  }, [selfId]);

  const kick = useCallback((id: string) => { api.kick(selfId, id).catch(() => {}); }, [selfId]);

  const setControl = useCallback((id: string, grant: boolean) => {
    api.setControl(selfId, id, grant).catch(() => {});
  }, [selfId]);

  const applySync = useCallback((sync: NonNullable<SessionCommand['sync']>) => {
    const v = video.current;
    if (!v) return;
    lastApplied.current = { positionMs: sync.positionMs, paused: sync.paused, rate: sync.rate, atMs: performance.now() };
    if (sync.paused && !v.paused) v.pause();
    else if (!sync.paused && v.paused) v.play().catch(() => {});
    if (sync.rate > 0 && v.playbackRate !== sync.rate) v.playbackRate = sync.rate;
    // Never re-seek while we're mid-seek ourselves (a fresh jump would fight the one in flight).
    if (liveRef.current || v.seeking) return;
    // While our own seek is still settling, ignore a peer's drift anchor: it's a stale position
    // reported by a stream that hasn't caught up, and would rewind us right off the new spot.
    const p = pendingSeek.current;
    if (!sync.seek && p && performance.now() - p.atMs < SETTLE_MS && Math.abs(sync.positionMs - p.ms) > SETTLE_TOL_MS) return;
    const target = sync.positionMs / 1000;
    const off = Math.abs(v.currentTime - target);
    if (isFinite(target) && off > (sync.seek ? SEEK_SNAP_SEC : ANCHOR_DRIFT_SEC)) v.currentTime = target;
    if (sync.seek) pendingSeek.current = { ms: sync.positionMs, atMs: performance.now() };
  }, [video]);

  // Send our playback state to the room, over the live socket (falling back to a POST). Event
  // sends are guarded so applying a peer's state doesn't bounce straight back; the anchor always
  // sends. [seek] tells receivers this is a deliberate jump, not a drift correction.
  const broadcast = useCallback((guarded: boolean, seek: boolean) => {
    const v = video.current;
    if (!v) return;
    // Don't anchor a transient position while our own playback isn't settled - mid-seek, still
    // buffering (readyState below HAVE_FUTURE_DATA), or a seek still landing. A loading peer that
    // kept broadcasting its stuck position would rewind everyone else again and again.
    if (!seek && (v.seeking || v.readyState < 3 || settling(v))) return;
    const state: SyncState = {
      positionMs: Math.floor((v.currentTime || 0) * 1000), paused: v.paused, rate: v.playbackRate || 1, seek,
    };
    if (seek) pendingSeek.current = { ms: state.positionMs, atMs: performance.now() };
    // Suppress only the echo of a just-applied peer state (fires within a few ms); a real
    // action later always sends, seek or not.
    const last = lastApplied.current;
    if (guarded && last && performance.now() - last.atMs < 800
        && last.paused === state.paused && last.rate === state.rate
        && Math.abs(last.positionMs - state.positionMs) < 1500) return;
    if (!send.current?.({ type: 'sync', sync: state })) api.sessionSync(selfId, state);
  }, [selfId, video, send, settling]);

  const onCommand = useCallback((command: SessionCommand) => {
    if (command.type === 'join-request' && command.peerId) {
      const peer = { peerId: command.peerId, peerName: command.peerName || t('watch.someone') };
      setJoinRequests((list) => (list.some((r) => r.peerId === peer.peerId) ? list : [...list, peer]));
      if (!command.quiet) snackbar(t('watch.wantsToJoin', { name: peer.peerName }));
    } else if (command.type === 'control-request' && command.peerId) {
      const peer = { peerId: command.peerId, peerName: command.peerName || t('watch.someone') };
      setControlRequests((list) => (list.some((r) => r.peerId === peer.peerId) ? list : [...list, peer]));
      snackbar(t('watch.wantsControl', { name: peer.peerName }));
    } else if (command.type === 'join-response') {
      snackbar(command.accepted ? t('watch.joined') : t('watch.declined'));
    } else if (command.type === 'control-response') {
      snackbar(command.accepted ? t('watch.controlGranted') : t('watch.controlDenied'));
    } else if (command.type === 'room-state' && command.members) {
      const roster = command.members;
      const ids = new Set(roster.map((m) => m.id));
      roomContent.current = contentRef.current;
      setInRoom(true);
      setMembers(roster);
      // Now watching together: the choice is settled and a remuxed viewer shares the room's read,
      // so a full provider no longer blocks them.
      setChoice('decided');
      if (remuxRef.current) setBlocked(false);
      // Anyone now in the room no longer has a pending request to answer.
      setJoinRequests((list) => list.filter((r) => !ids.has(r.peerId)));
      setControlRequests((list) => list.filter((r) => !ids.has(r.peerId)));
    } else if (command.type === 'sync' && command.sync) {
      applySync(command.sync);
    } else if (command.type === 'room-audio' && command.audioIndex != null) {
      roomAudioRef.current?.(command.audioIndex);
    } else if (command.type === 'room-ended') {
      if (roomContent.current) snackbar(t('watch.ended'));
      resetRoom();
    }
  }, [applySync, resetRoom]);

  // New content: clear stale prompts, and drop a room that belonged to the old content.
  useEffect(() => {
    setPeers([]);
    setBlocked(false);
    setChecking(true);
    setChoice('decided');
    if (roomContent.current && roomContent.current !== contentKey) leave();
  }, [contentKey, leave]);

  const watchAlone = useCallback(() => setChoice('decided'), []);

  // Ask the server who else is here and whether the provider is full - once per content.
  const checked = useRef<string | null>(null);
  useEffect(() => {
    if (!active || !contentKey || inRoom || checked.current === contentKey) return;
    checked.current = contentKey;
    let cancelled = false;
    // Never hold playback on the check for long: fail open if it's slow.
    const failOpen = setTimeout(() => { if (!cancelled) setChecking(false); }, 4000);
    api.watchIntent(selfId, contentKey, source, playlistId).then((intent) => {
      if (cancelled) return;
      clearTimeout(failOpen);
      setPeers(intent.sameContent);
      // A solo viewer needs its own connection even on the same content: sharing only happens
      // inside a room. So a full provider blocks playing alone - join someone to watch together
      // (which shares their read) or get the limit message.
      setBlocked(intent.full);
      setChecking(false);
      // Someone's already here: don't just start streaming (and maybe take the last seat) - hold
      // and ask whether to watch alone or together.
      if (intent.sameContent.length > 0) setChoice('pending');
    }).catch(() => { if (!cancelled) { clearTimeout(failOpen); setChecking(false); } });
    return () => { cancelled = true; clearTimeout(failOpen); };
  }, [active, contentKey, inRoom, selfId, source, playlistId]);

  // The host anchors the shared timeline: a snap whenever the roster grows (so a fresh joiner
  // jumps to where everyone else is) plus a gentle tick that only fixes a real desync.
  useEffect(() => {
    if (!isHost || members.length < 2) return;
    const v = video.current;
    if (!v) return;
    broadcast(false, true);
    const timer = setInterval(() => broadcast(false, false), ANCHOR_MS);
    return () => clearInterval(timer);
  }, [isHost, members.length, broadcast, video]);

  // Anyone with control drives: their play/pause/seek/rate reaches the rest of the room at once.
  // A seek is flagged so receivers apply it exactly instead of treating it as drift.
  useEffect(() => {
    if (!canControl) return;
    const v = video.current;
    if (!v) return;
    const onSeek = () => broadcast(true, true);
    const onEvt = () => broadcast(true, false);
    v.addEventListener('seeked', onSeek);
    ['play', 'pause', 'ratechange'].forEach((e) => v.addEventListener(e, onEvt));
    return () => {
      v.removeEventListener('seeked', onSeek);
      ['play', 'pause', 'ratechange'].forEach((e) => v.removeEventListener(e, onEvt));
    };
  }, [canControl, broadcast, video]);

  return {
    selfId,
    available: inRoom || peers.length > 0 || joinRequests.length > 0,
    hasPending: joinRequests.length > 0 || controlRequests.length > 0,
    inRoom, members, isHost, canControl, peers, joinRequests, controlRequests, checking, blocked,
    choosing: choice === 'pending', watchAlone,
    ask, answerJoin, requestControl, answerControl, setControl, kick, leave, onCommand,
  };
}

/** The watch-together sheet, opened from the top-bar icon: roster, pending requests, actions. */
export function WatchTogetherSheet({ wt, onDismiss, container }: {
  wt: WatchTogether;
  onDismiss: () => void;
  container?: Element | null;
}) {
  const rows: React.ReactNode[] = [];

  wt.members.forEach((m) => {
    const isSelf = m.id === wt.selfId;
    // The host sets each guest's role from one select (viewer / can control / remove), so there's
    // no scatter of tags and buttons. Everyone else just sees who's the host or a controller.
    const manage = wt.isHost && !isSelf && !m.host;
    rows.push(
      <div className="watch-row" key={`m-${m.id}`}>
        <span className="min-w-0 flex-1 truncate">{isSelf ? t('watch.you') : m.name}</span>
        {manage ? (
          <select className="watch-select" aria-label={m.name}
                  value={m.controller ? 'control' : 'viewer'}
                  onChange={(e) => {
                    const v = e.target.value;
                    if (v === 'remove') wt.kick(m.id);
                    else wt.setControl(m.id, v === 'control');
                  }}>
            <option value="viewer">{t('watch.roleViewer')}</option>
            <option value="control">{t('watch.roleController')}</option>
            <option value="remove">{t('watch.kick')}</option>
          </select>
        ) : (
          (m.host || m.controller) && (
            <span className="watch-role">{m.host ? t('watch.roleHost') : t('watch.roleController')}</span>
          )
        )}
      </div>,
    );
  });

  wt.joinRequests.forEach((r) => rows.push(
    <div className="watch-row" key={`j-${r.peerId}`}>
      <span className="min-w-0 flex-1 truncate">{t('watch.wantsToJoinShort', { name: r.peerName })}</span>
      <button className="btn text watch-act" onClick={() => wt.answerJoin(r.peerId, false)}>{t('watch.decline')}</button>
      <button className="btn text watch-act" onClick={() => wt.answerJoin(r.peerId, true)}>{t('watch.accept')}</button>
    </div>,
  ));

  wt.controlRequests.forEach((r) => rows.push(
    <div className="watch-row" key={`c-${r.peerId}`}>
      <span className="min-w-0 flex-1 truncate">{t('watch.wantsControlShort', { name: r.peerName })}</span>
      <button className="btn text watch-act" onClick={() => wt.answerControl(r.peerId, false)}>{t('watch.decline')}</button>
      <button className="btn text watch-act" onClick={() => wt.answerControl(r.peerId, true)}>{t('watch.allow')}</button>
    </div>,
  ));

  // Not in a room yet: each viewer on this content is someone you can join.
  if (!wt.inRoom) {
    wt.peers.forEach((p) => rows.push(
      <div className="watch-row" key={`p-${p.id}`}>
        <span className="min-w-0 flex-1 truncate">{p.name}</span>
        <button className="btn text watch-act" onClick={() => { wt.ask(p.id); onDismiss(); }}>{t('watch.joinPeer')}</button>
      </div>,
    ));
  }

  return (
    <Sheet onDismiss={onDismiss} container={container}
           header={<h3 className="sheet-title">{t('watch.title')}</h3>}>
      {!wt.inRoom && wt.peers.length > 0 && (
        <p className="py-1 type-body-medium text-on-surface-variant">{t('watch.offerBody')}</p>
      )}
      {rows}
      <div className="watch-actions">
        {wt.inRoom && !wt.canControl && (
          <button className="btn tonal" onClick={() => { wt.requestControl(); onDismiss(); }}>{t('watch.requestControl')}</button>
        )}
        {wt.inRoom && (
          <button className="btn text" onClick={() => { wt.leave(); onDismiss(); }}>{t('watch.leave')}</button>
        )}
      </div>
    </Sheet>
  );
}
