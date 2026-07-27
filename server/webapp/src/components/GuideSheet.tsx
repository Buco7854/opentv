// Channel guide with catch-up replay of past programmes. Mirrors GuideSheet.kt.

import { Fragment, useEffect, useRef, useState } from 'react';
import { api, Channel, GuideEntry } from '../api';
import { useAsync } from '../hooks';
import { t } from '../i18n';
import { dayKey, fmtGuideDay, fmtTime } from '../lib/format';
import { asyncFallback, ChannelLogo } from './Common';
import { Icon } from './Icons';
import { Sheet } from './Primitives';

const programmeId = (entry: GuideEntry) => `${entry.startMs}:${entry.title}`;

export function GuideSheet({ channel, onDismiss, onPlayCatchup, container }: {
  channel: Channel;
  onDismiss: () => void;
  onPlayCatchup: (channelId: number, startMs: number, endMs: number) => void;
  container?: Element | null;
}) {
  const guide = useAsync(() => api.guide(channel.contentId), [channel.contentId]);
  const entries = guide.data;
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const nowRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => setExpanded(new Set()), [channel.id]);

  // Open at the present, not at a week of history.
  useEffect(() => {
    if (entries?.length) nowRef.current?.scrollIntoView({ block: 'center' });
  }, [entries]);

  const anyReplay = entries?.some((e) => e.replayable) ?? false;
  const now = Date.now();
  const anchor = entries?.findIndex((e) => e.endMs > now) ?? -1;

  const toggleExpanded = (id: string) => {
    setExpanded((old) => {
      const next = new Set(old);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  };

  function replay(entry: GuideEntry) {
    onDismiss();
    onPlayCatchup(channel.id, entry.startMs, entry.endMs);
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
      {asyncFallback(guide)}
      {entries?.length === 0 && (
        <div className="guide-empty">
          <Icon name="calendar" />
          <p>{t('guide.noData')}<br />{t('guide.addEpg')}</p>
        </div>
      )}
      {entries?.map((entry, i) => {
        const isNow = entry.startMs <= now && entry.endMs > now;
        const isPast = entry.endMs <= now;
        const previous = entries[i - 1];
        const newDay = previous === undefined || dayKey(entry.startMs) !== dayKey(previous.startMs);
        const isExpanded = expanded.has(programmeId(entry));
        const titleClass = `prog-title${isNow ? ' now' : isPast && !entry.replayable ? ' past' : ''}`;
        const titleBody = (
          <>
            <span className="t">{entry.title}</span>
            {isNow && <span className="now-pill">{t('guide.now')}</span>}
          </>
        );
        return (
          <Fragment key={i}>
            {newDay && <div className="guide-day">{fmtGuideDay(entry.startMs)}</div>}
            <div
              ref={i === anchor ? nowRef : undefined}
              className={`guide-row${entry.replayable ? ' replayable' : ''}${isNow ? ' airing' : ''}${entry.description ? ' has-desc' : ''}`}
            >
              <div className={`time${isNow ? ' now' : ''}`}>{fmtTime(entry.startMs)}</div>
              <div className="body">
                {entry.replayable ? (
                  <button type="button" className={`${titleClass} w-full text-left`}
                          title={t('guide.replay')} onClick={() => replay(entry)}>
                    {titleBody}
                  </button>
                ) : (
                  <div className={titleClass}>{titleBody}</div>
                )}
                {entry.description && (
                  <button
                    type="button"
                    className={`prog-desc w-full text-left${isExpanded ? ' expanded' : ''}`}
                    aria-expanded={isExpanded}
                    title={isExpanded ? t('common.showLess') : t('common.showMore')}
                    onClick={() => toggleExpanded(programmeId(entry))}
                  >
                    {entry.description}
                  </button>
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
