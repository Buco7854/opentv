// Channel guide with catch-up replay of past programmes. Mirrors GuideSheet.kt.

import { Fragment, useEffect, useRef, useState } from 'react';
import { api, Channel, GuideEntry } from '../api';
import { t } from '../i18n';
import { dayKey, fmtGuideDay, fmtTime } from '../lib/format';
import { ChannelLogo } from './Common';
import { Icon } from './Icons';
import { Sheet, snackbar, Spinner } from './Primitives';

export function GuideSheet({ channel, onDismiss, onPlayCatchup, container }: {
  channel: Channel;
  onDismiss: () => void;
  onPlayCatchup: (channelId: number, startMs: number, endMs: number) => void;
  container?: Element | null;
}) {
  const [entries, setEntries] = useState<GuideEntry[] | null>(null);
  const [expanded, setExpanded] = useState<Set<number>>(new Set());
  const nowRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    api.guide(channel.id).then(setEntries, () => setEntries([]));
  }, [channel.id]);

  // Open at the present, not at a week of history.
  useEffect(() => {
    if (entries?.length) nowRef.current?.scrollIntoView({ block: 'center' });
  }, [entries]);

  const anyReplay = entries?.some((e) => e.replayable) ?? false;
  const now = Date.now();
  const anchor = entries?.findIndex((e) => e.endMs > now) ?? -1;

  const toggleExpanded = (i: number) => {
    setExpanded((old) => {
      const next = new Set(old);
      if (next.has(i)) next.delete(i); else next.add(i);
      return next;
    });
  };

  async function replay(entry: GuideEntry) {
    try {
      // Confirm the panel has this window before navigating.
      const { url } = await api.catchupUrl(channel.id, entry.startMs, entry.endMs);
      if (!url) {
        snackbar(t('guide.catchupUnavailable'));
        return;
      }
      onDismiss();
      onPlayCatchup(channel.id, entry.startMs, entry.endMs);
    } catch (e) {
      snackbar((e as Error).message);
    }
  }

  return (
    <Sheet
      onDismiss={onDismiss}
      container={container}
      header={
        <div className="guide-head">
          <ChannelLogo url={channel.logo} kind={channel.kind} />
          <div className="body">
            <h3>{channel.name}</h3>
            <p className="sub">
              {anyReplay ? t('guide.catchupHint') : t('guide.programmeGuide')}
            </p>
          </div>
        </div>
      }
    >
      {entries === null && <Spinner />}
      {entries?.length === 0 && (
        <div className="guide-empty">
          <Icon name="calendar" />
          <p>{t('guide.noData')}<br />{t('guide.addEpg')}</p>
        </div>
      )}
      {entries?.map((entry, i) => {
        const isNow = entry.startMs <= now && entry.endMs > now;
        const isPast = entry.endMs <= now;
        const newDay = i === 0 || dayKey(entry.startMs) !== dayKey(entries[i - 1].startMs);
        const isExpanded = expanded.has(i);
        return (
          <Fragment key={i}>
            {newDay && <div className="guide-day">{fmtGuideDay(entry.startMs)}</div>}
            <div
              ref={i === anchor ? nowRef : undefined}
              className={`guide-row${entry.replayable ? ' replayable' : ''}${isNow ? ' airing' : ''}${entry.description ? ' has-desc' : ''}`}
              onClick={entry.replayable ? () => replay(entry) : entry.description ? () => toggleExpanded(i) : undefined}
            >
              <div className={`time${isNow ? ' now' : ''}`}>{fmtTime(entry.startMs)}</div>
              <div className="body">
                <div className={`prog-title${isNow ? ' now' : isPast && !entry.replayable ? ' past' : ''}`}>
                  <span className="t">{entry.title}</span>
                  {isNow && <span className="now-pill">{t('guide.now')}</span>}
                </div>
                {entry.description && (
                  <div
                    className={`prog-desc${isExpanded ? ' expanded' : ''}`}
                    title={isExpanded ? t('common.showLess') : t('common.showMore')}
                    onClick={(e) => { e.stopPropagation(); toggleExpanded(i); }}
                  >
                    {entry.description}
                  </div>
                )}
              </div>
              {entry.replayable && <Icon name="replay" className="replay" />}
            </div>
          </Fragment>
        );
      })}
    </Sheet>
  );
}
