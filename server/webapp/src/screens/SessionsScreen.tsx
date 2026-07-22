// Viewer activity dashboard: who is watching what, with remote pause/resume,
// a message channel, and on-the-fly stream diagnostics (direct / proxy / remux,
// and why ffmpeg is copying or transcoding). Web-client sessions only.

import { useEffect, useRef, useState } from 'react';
import { api, RemuxDiag, Session } from '../api';
import { ChannelLogo, EmptyState, Pill } from '../components/Common';
import { Dialog, IconBtn, ScreenHeader, Spinner, TextField, snackbar } from '../components/Primitives';
import { deviceLabel } from '../lib/format';
import { getLocale, t } from '../i18n';

const POLL_MS = 3000;

/** ChannelLogo wants a ChannelKind number for its fallback glyph. */
const kindNumber = (kind: Session['kind']) =>
  kind === 'movie' || kind === 'download' ? 1 : kind === 'series' ? 2 : 0;

const clock = (ms: number) => {
  const s = Math.max(0, Math.floor(ms / 1000));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s % 60;
  const mm = String(m).padStart(2, '0');
  return h ? `${h}:${mm}:${String(sec).padStart(2, '0')}` : `${m}:${String(sec).padStart(2, '0')}`;
};

const channelsName = (n: number | null) =>
  n == null ? null : n === 1 ? 'Mono' : n === 2 ? 'Stereo' : n === 6 ? '5.1' : n === 8 ? '7.1' : `${n}ch`;

/** The playback path in one label, for the card's chip. */
function modeLabel(stream: Session['stream']): string {
  if (stream.preparing) return t('sessions.modePreparing');
  if (stream.remux) {
    return stream.remux.transcodeVideo ? t('sessions.modeRemuxTranscode') : t('sessions.modeRemuxCopy');
  }
  if (stream.audioTranscoded) return t('sessions.modeAudioTranscode');
  return stream.direct ? t('sessions.modeDirect') : t('sessions.modeProxy');
}

const kindLabel = (kind: Session['kind']) =>
  kind === 'live' ? t('player.live')
    : kind === 'catchup' ? t('player.catchup')
      : kind === 'movie' ? t('nav.movies')
        : kind === 'series' ? t('nav.series') : t('downloads.download');

const up = (codec: string) => codec.toUpperCase();

/** Plain-language "what is happening and why", for viewers who don't know what
 *  remux/transcode/AAC mean. One sentence per media aspect. */
function explain(stream: Session['stream']): string[] {
  if (stream.preparing) return [t('sessions.whyPreparing')];
  const r = stream.remux;
  if (r) {
    const lines: string[] = [];
    // Copy (remux) and native-copy are the same story to a viewer: the browser can
    // decode this codec, so it passes through untouched.
    lines.push(r.transcodeVideo
      ? t('sessions.whyVideoTranscode', { codec: up(r.videoCodec) })
      : t('sessions.whyVideoCopy', { codec: up(r.videoCodec) }));
    lines.push(r.audioCodec.toLowerCase() === 'aac'
      ? t('sessions.whyAudioKeep')
      : t('sessions.whyAudioConvert', { codec: up(r.audioCodec) }));
    lines.push(t('sessions.whyContainer'));
    return lines;
  }
  if (stream.audioTranscoded) return [t('sessions.whyAudioLive'), t('sessions.whyRelay')];
  if (stream.direct) return [t('sessions.whyDirect')];
  return [t('sessions.whyRelay')];
}

export function SessionsScreen() {
  const [sessions, setSessions] = useState<Session[] | null>(null);
  const [messaging, setMessaging] = useState<Session | null>(null);
  const timer = useRef<ReturnType<typeof setTimeout>>();
  // A pause/play command takes a poll or two to reach the server's reported
  // state. Hold the optimistic `paused` for each commanded session until a poll
  // agrees (or a short safety window elapses), so an in-flight poll carrying the
  // pre-command value can't flip the icon back and forth.
  const pendingPaused = useRef<Map<string, { paused: boolean; until: number }>>(new Map());

  const reconcile = (list: Session[]): Session[] => {
    const now = Date.now();
    return list.map((s) => {
      const pending = pendingPaused.current.get(s.id);
      if (!pending) return s;
      if (now > pending.until || s.paused === pending.paused) {
        pendingPaused.current.delete(s.id);
        return s;
      }
      return { ...s, paused: pending.paused };
    });
  };

  const tick = async () => {
    clearTimeout(timer.current);
    try { setSessions(reconcile(await api.sessions())); } catch { /* keep last */ }
    timer.current = setTimeout(tick, POLL_MS);
  };
  useEffect(() => { tick(); return () => clearTimeout(timer.current); }, []);

  const command = async (session: Session, type: 'pause' | 'play') => {
    const paused = type === 'pause';
    pendingPaused.current.set(session.id, { paused, until: Date.now() + 8000 });
    setSessions((list) => list?.map((s) => (s.id === session.id ? { ...s, paused } : s)) ?? list);
    await api.sessionCommand(session.id, { type }).catch(() => { pendingPaused.current.delete(session.id); });
  };

  return (
    <>
      <ScreenHeader
        title={t('sessions.title')}
        subtitle={sessions && sessions.length > 0
          ? <span className="subtitle">{t('sessions.activeCount', { count: sessions.length })}</span>
          : undefined}
      />

      {sessions === null && <Spinner />}
      {sessions?.length === 0 && (
        <EmptyState title={t('sessions.emptyTitle')} subtitle={t('sessions.emptySub')} />
      )}
      {sessions && sessions.length > 0 && (
        <div className="list" style={{ gap: 12 }}>
          {sessions.map((s) => (
            <SessionCard
              key={s.id}
              session={s}
              onToggle={() => command(s, s.paused ? 'play' : 'pause')}
              onMessage={() => setMessaging(s)}
            />
          ))}
        </div>
      )}

      {messaging && (
        <MessageDialog
          session={messaging}
          onDismiss={() => setMessaging(null)}
        />
      )}
    </>
  );
}

function SessionCard({ session, onToggle, onMessage }: {
  session: Session;
  onToggle: () => void;
  onMessage: () => void;
}) {
  const [detailsOpen, setDetailsOpen] = useState(false);
  const { positionMs, durationMs, live } = session;
  const fraction = durationMs > 0 ? Math.min(1, positionMs / durationMs) : 0;

  return (
    <div className="card r20 session-card">
      <div className="head">
        <ChannelLogo url={session.logo} kind={kindNumber(session.kind)} />
        <div className="body">
          <div className="title truncate">{session.title}</div>
          <div className="sub truncate">
            {[session.playlistName, kindLabel(session.kind)].filter(Boolean).join(' · ')}
          </div>
          <div className="who truncate">{[deviceLabel(session.userAgent), session.ip].filter(Boolean).join(' · ')}</div>
        </div>
        <div className="tags">
          {live && <span className="live-chip">{t('player.live').toUpperCase()}</span>}
          <Pill>{modeLabel(session.stream)}</Pill>
        </div>
      </div>

      {!live && durationMs > 0 && (
        <div className="timeline">
          <div className="progress-track"><div className="progress-fill" style={{ width: `${fraction * 100}%` }} /></div>
          <div className="times">
            <span>{clock(positionMs)}{session.paused ? ` · ${t('sessions.paused')}` : ''}</span>
            <span>{clock(durationMs)}</span>
          </div>
        </div>
      )}

      <div className="actions">
        <IconBtn
          name={session.paused ? 'play' : 'pause'}
          className={session.paused ? 'primary' : ''}
          label={session.paused ? t('sessions.resumeAria') : t('sessions.pauseAria')}
          onClick={onToggle}
        />
        <IconBtn name="message" label={t('sessions.sendMessage')} onClick={onMessage} />
        <IconBtn name="info" label={t('sessions.details')}
                 onClick={() => setDetailsOpen(true)} />
      </div>

      {detailsOpen && <StreamDetailsDialog session={session} onDismiss={() => setDetailsOpen(false)} />}
    </div>
  );
}

function StreamDetailsDialog({ session, onDismiss }: { session: Session; onDismiss: () => void }) {
  return (
    <Dialog
      title={t('sessions.details')}
      className="stream-dialog"
      onDismiss={onDismiss}
      buttons={<button className="btn text" onClick={onDismiss}>{t('common.close')}</button>}
    >
      <div className="dialog-sub truncate">{session.title}</div>
      <StreamDetails session={session} />
    </Dialog>
  );
}

function StreamDetails({ session }: { session: Session }) {
  const { stream } = session;
  const rows: [string, React.ReactNode][] = [];
  // Nothing technical to show until the server has finished probing.
  if (!stream.preparing) rows.push([t('sessions.engine'), stream.engine]);

  const r: RemuxDiag | null = stream.preparing ? null : stream.remux;
  if (r) {
    const videoAction = r.transcodeVideo
      ? t('sessions.transcodeTo', { encoder: r.videoEncoder })
      : t('sessions.copy');
    rows.push([t('sessions.video'), `${r.videoCodec.toUpperCase()} · ${videoAction}`]);
    rows.push([t('sessions.audio'),
      [r.audioCodec.toUpperCase(), channelsName(r.audioChannels), r.audioLabel, '→ AAC']
        .filter(Boolean).join(' · ')]);
    if (r.subtitleCount > 0) rows.push([t('sessions.subtitlesRow'), t('sessions.tracksCount', { count: r.subtitleCount })]);
    rows.push([t('sessions.segments'), String(r.segmentCount)]);
    rows.push([t('sessions.provider'), `${r.providerKey} · ${t('sessions.maxConnections', { count: r.connectionLimit })}`]);
    rows.push([t('sessions.ffmpeg'), r.ffmpegRunning ? t('sessions.running') : t('sessions.idle')]);
  }

  return (
    <div className="stream-details">
      <div className="explain">
        {explain(stream).map((line, i) => <p key={i}>{line}</p>)}
      </div>
      {rows.length > 0 && <div className="tech-label">{t('sessions.technical')}</div>}
      {r?.timeshift && <div className="note">{t('sessions.timeshift')}</div>}
      {rows.map(([k, v], i) => (
        <div className="kv-row" key={i}>
          <div className="k">{k}</div>
          <div className="v">{v}</div>
        </div>
      ))}
      {r?.lastLog && (
        <div className="log">
          <div className="k">{t('sessions.lastLog')}</div>
          <code>{r.lastLog}</code>
        </div>
      )}
      <div className="stamp">{new Date(session.lastSeenMs).toLocaleTimeString(getLocale())}</div>
    </div>
  );
}

function MessageDialog({ session, onDismiss }: { session: Session; onDismiss: () => void }) {
  const [text, setText] = useState('');
  const send = async () => {
    const message = text.trim();
    if (!message) return;
    onDismiss();
    await api.sessionCommand(session.id, { type: 'message', text: message }).catch(() => {});
    snackbar(t('sessions.messageSent'));
  };
  return (
    <Dialog
      title={t('sessions.messageTitle')}
      onDismiss={onDismiss}
      buttons={
        <>
          <button className="btn text" onClick={onDismiss}>{t('common.cancel')}</button>
          <button className="btn text" onClick={send} disabled={!text.trim()}>{t('sessions.send')}</button>
        </>
      }
    >
      <TextField label={t('sessions.messagePlaceholder')} value={text} onChange={setText} autoFocus />
    </Dialog>
  );
}
