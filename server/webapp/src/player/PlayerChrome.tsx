// The player's own surface: the overlays drawn over the video and the control bars. Purely
// presentational - it reads playback state and reports intent, and owns no engine or lease.

import { useCallback, useEffect, useRef, useState } from 'react';
import { api, Channel, GuideEntry } from '../api';
import { Icon } from '../components/Icons';
import { IconBtn } from '../components/Primitives';
import { t } from '../i18n';
import { useCssVars } from '../lib/cssVars';
import { prefs } from '../preferences';
import { SubtitleStyle } from './PlaybackSheets';
import { formatPlaybackTime } from './playbackPolicy';
import { WatchTogether } from './WatchTogether';

export type PlayerMenu = 'speed' | 'scale' | 'audio' | 'subs' | 'subStyle';

/** Chrome auto-hides while playing and comes back on any pointer activity. */
export function useChromeVisibility(videoRef: React.RefObject<HTMLVideoElement | null>) {
  const [uiVisible, setUiVisible] = useState(true);
  const hideTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const poke = useCallback(() => {
    setUiVisible(true);
    clearTimeout(hideTimer.current);
    hideTimer.current = setTimeout(() => {
      if (!videoRef.current?.paused) setUiVisible(false);
    }, 3000);
  }, [videoRef]);
  useEffect(() => () => clearTimeout(hideTimer.current), []);
  return { uiVisible, setUiVisible, poke };
}

/** Live: what's airing now, refreshed when the programme ends. */
function useEpgNow(live: boolean, channelId: number | undefined) {
  const [epgNow, setEpgNow] = useState<GuideEntry | null>(null);
  useEffect(() => {
    if (!live || channelId == null) { setEpgNow(null); return; }
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout>;
    const load = async () => {
      const entries = await api.guide(channelId).catch(() => [] as GuideEntry[]);
      if (cancelled) return;
      const now = Date.now();
      const current = entries.find((g) => g.startMs <= now && now < g.endMs) ?? null;
      setEpgNow(current);
      timer = setTimeout(load, current ? Math.min(current.endMs - now + 1000, 30 * 60_000) : 5 * 60_000);
    };
    load();
    return () => { cancelled = true; clearTimeout(timer); };
  }, [live, channelId]);
  return epgNow;
}

export function PlayerOverlays({
  error, busy, uiVisible, cueText, subStyle, wt, onClose, onOpenWatchTogether,
}: {
  error: string | null;
  busy: boolean;
  uiVisible: boolean;
  cueText: string;
  subStyle: SubtitleStyle;
  wt: WatchTogether;
  onClose: () => void;
  onOpenWatchTogether: () => void;
}) {
  const cueScale = useCssVars<HTMLSpanElement>({ '--cue-scale': subStyle.scale });
  return (
    <>
      {cueText && (
        <div className={`player-subs${uiVisible ? ' chrome' : ''}`} aria-live="off">
          <span ref={cueScale} className={`cue cue-${subStyle.style}${subStyle.bold ? ' bold' : ''}`}>
            {cueText.split('\n').map((line, i) => <span key={i} className="cue-line">{line}</span>)}
          </span>
        </div>
      )}

      {!error && busy && !uiVisible && !wt.choosing && !wt.loading && <div className="player-spinner" aria-hidden />}

      {/* Room track change: block input for everyone and show loading until all have reloaded. */}
      {!error && wt.loading && (
        <div className="player-lock"><span className="btn-spinner" aria-hidden /></div>
      )}

      {!error && wt.choosing && (
        <div className="player-error">
          <span className="player-choose-close">
            <IconBtn name="close" label={t('player.stop')} onClick={onClose} />
          </span>
          <h3>{t('watch.title')}</h3>
          <p>{t('watch.choosePrompt')}</p>
          <div className="mt-3 flex flex-wrap items-center justify-end gap-2">
            <button className="btn tonal w-auto" onClick={onOpenWatchTogether}>{t('watch.title')}</button>
            <button className="btn text w-auto" onClick={wt.watchAlone}>{t('watch.watchAlone')}</button>
          </div>
        </div>
      )}

      {error && (
        <div className="player-error">
          <h3>{t('player.errorTitle')}</h3>
          {/* The codec/Android hint only fits a decode failure, not a full-provider refusal. */}
          <p>{error}{error === t('player.connectionLimit') ? '' : ` ${t('player.errorHint')}`}</p>
          <button className="btn tonal w-auto" onClick={onClose}>{t('common.close')}</button>
        </div>
      )}
    </>
  );
}

export function PlayerControls({
  title, live, catchup, channelId, guideAvailable, wt, uiVisible, paused, busy,
  duration, position, bufferedEnd, canRotate,
  onTogglePlay, onSeekBy, onSeekTo, onOpenMenu, onOpenWatchTogether, onGuideChannel,
  onRotate, onPip, onToggleFullscreen, onClose, poke,
}: {
  title: string;
  live: boolean;
  catchup: boolean;
  channelId: number | undefined;
  guideAvailable: boolean;
  wt: WatchTogether;
  uiVisible: boolean;
  paused: boolean;
  busy: boolean;
  /** Full-file duration and the position the bar should show (a held seek target, else media). */
  duration: number;
  position: number;
  bufferedEnd: number;
  canRotate: boolean;
  onTogglePlay: () => void;
  onSeekBy: (delta: number) => void;
  onSeekTo: (target: number) => void;
  onOpenMenu: (menu: PlayerMenu) => void;
  onOpenWatchTogether: () => void;
  onGuideChannel: (channel: Channel) => void;
  onRotate: () => void;
  onPip: () => void;
  onToggleFullscreen: () => void;
  onClose: () => void;
  poke: () => void;
}) {
  const epgNow = useEpgNow(live, channelId);
  /* Slider position (0..1000) while the user is dragging; null otherwise. */
  const [scrub, setScrub] = useState<number | null>(null);

  const seekable = !!duration && isFinite(duration);
  const seekFrac = scrub != null ? scrub / 1000 : seekable ? position / duration : 0;
  const bufferedFrac = seekable ? Math.max(seekFrac, Math.min(1, bufferedEnd / duration)) : 0;
  const seekPercent = (seekFrac * 100).toFixed(2);
  const bufferedPercent = (bufferedFrac * 100).toFixed(2);
  const seekStyle = {
    background: `linear-gradient(to right, #fff ${seekPercent}%, `
      + `rgba(255,255,255,0.45) ${seekPercent}%, `
      + `rgba(255,255,255,0.45) ${bufferedPercent}%, `
      + `rgba(255,255,255,0.18) ${bufferedPercent}%)`,
  };
  const commitScrub = (value: number) => {
    if (seekable) onSeekTo((value / 1000) * duration);
    setScrub(null);
  };

  return (
    <div className={`player-ui${uiVisible ? '' : ' hidden'}`}>
      <div className="top">
        <IconBtn name="back" label={t('common.back')} onClick={onClose} />
        <div className="title-block">
          <div className="t">{title}</div>
          <div className="s">
            {live
              ? epgNow
                ? t('player.nowUntil', {
                    title: epgNow.title,
                    end: new Date(epgNow.endMs).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
                  })
                : t('player.live')
              : catchup ? t('player.catchup') : ''}
          </div>
        </div>
        {wt.available && (
          <span className="relative inline-flex">
            <IconBtn name="person" label={t('watch.title')} onClick={onOpenWatchTogether} />
            {wt.hasPending && <span className="wt-badge" aria-hidden />}
          </span>
        )}
        {live && channelId != null && guideAvailable && (
          <IconBtn name="calendar" label={t('guide.title')} onClick={async () => {
            const channel = await api.channel(channelId).catch(() => null);
            if (channel) onGuideChannel(channel);
          }} />
        )}
        <IconBtn name="close" label={t('player.stop')} onClick={onClose} />
      </div>
      <div className="middle">
        {!live && (
          <button className="icon-btn big-btn" aria-label={t('player.rewind')} onClick={() => onSeekBy(-prefs.seekSeconds)}>
            <Icon name="replay" />
          </button>
        )}
        <button className="icon-btn big-btn" aria-label={paused ? t('common.play') : t('common.pause')} onClick={onTogglePlay}>
          {busy ? <span className="btn-spinner" aria-hidden /> : <Icon name={paused ? 'play' : 'pause'} />}
        </button>
        {!live && (
          <button className="icon-btn big-btn" aria-label={t('player.forward')} onClick={() => onSeekBy(prefs.seekSeconds)}>
            <Icon name="forward" />
          </button>
        )}
      </div>
      <div className="bottom">
        {/* VOD/downloads and catch-up all get a scrubber. */}
        {!live && (
          <div className="seek-row">
            <span className="time-label">
              {formatPlaybackTime(scrub != null && isFinite(duration) ? (scrub / 1000) * duration : position)}
            </span>
            <input
              className="seek" type="range" min={0} max={1000} style={seekStyle}
              value={scrub ?? (duration ? Math.floor((position / duration) * 1000) : 0)}
              onChange={(e) => { setScrub(Number(e.target.value)); poke(); }}
              onPointerUp={(e) => commitScrub(Number((e.target as HTMLInputElement).value))}
              onKeyUp={(e) => commitScrub(Number((e.target as HTMLInputElement).value))}
            />
            <span className="time-label">{formatPlaybackTime(duration)}</span>
          </div>
        )}
        <div className="controls">
          {live && <span className="live-chip">{t('player.live').toUpperCase()}</span>}
          <IconBtn name="audio" label={t('player.audio')} onClick={() => onOpenMenu('audio')} />
          <IconBtn name="subtitles" label={t('player.subtitles')} onClick={() => onOpenMenu('subs')} />
          {!live && <IconBtn name="speed" label={t('player.speed')} onClick={() => onOpenMenu('speed')} />}
          <IconBtn name="aspect" label={t('player.scaling')} onClick={() => onOpenMenu('scale')} />
          {document.pictureInPictureEnabled &&
            <IconBtn name="pip" label={t('player.pip')} onClick={onPip} />}
          {canRotate && <IconBtn name="rotate" label={t('player.rotate')} onClick={onRotate} />}
          <IconBtn name="fullscreen" label={t('player.fullscreen')} onClick={onToggleFullscreen} />
        </div>
      </div>
    </div>
  );
}
