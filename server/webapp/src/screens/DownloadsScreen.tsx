// One card per download. Web extra: "save" streams the finished file to the
// browser's own downloads. Mirrors DownloadsScreen.kt.

import { api, Download, downloadFileUrl, DownloadStatus } from '../api';
import { EmptyState } from '../components/Common';
import { Icon } from '../components/Icons';
import { IconBtn, ScreenHeader } from '../components/Primitives';
import { useDownloads } from '../hooks';
import { t } from '../i18n';
import { formatBytes } from '../lib/format';
import { usePlayer } from '../player/PlayerNavigation';

export function DownloadsScreen() {
  const { playDownload } = usePlayer();
  const downloads = useDownloads();

  return (
    <>
      <ScreenHeader title={t('downloads.title')} />
      {downloads.list.length === 0 ? (
        <EmptyState title={t('downloads.emptyTitle')} subtitle={t('downloads.emptySub')} />
      ) : (
        <div className="list" style={{ gap: 10 }}>
          {downloads.list.map((item) => (
            <DownloadCard
              key={item.id}
              item={item}
              onPlay={() => playDownload(item.id)}
              onChanged={downloads.refresh}
            />
          ))}
        </div>
      )}
    </>
  );
}

function DownloadCard({ item, onPlay, onChanged }: {
  item: Download;
  onPlay: () => void;
  onChanged: () => void;
}) {
  const act = (fn: () => Promise<unknown>) => async () => { await fn().catch(() => {}); onChanged(); };

  const progressText = item.totalBytes > 0
    ? t('downloads.ofBytes', { done: formatBytes(item.downloadedBytes), total: formatBytes(item.totalBytes) })
    : formatBytes(item.downloadedBytes);
  const statusText =
    item.status === DownloadStatus.QUEUED ? t('downloads.queued')
    : item.status === DownloadStatus.RUNNING ? progressText
    : item.status === DownloadStatus.PAUSED ? `${t('downloads.paused')} · ${progressText}`
    : item.status === DownloadStatus.DONE ? `${t('downloads.saved')} · ${formatBytes(item.totalBytes)}`
    : item.status === DownloadStatus.FAILED ? `${t('downloads.failed')}${item.error ? `: ${item.error}` : ''}`
    : t('downloads.cancelled');
  const statusClass =
    item.status === DownloadStatus.DONE ? ' done'
    : item.status === DownloadStatus.FAILED ? ' failed' : '';

  const action =
    item.status === DownloadStatus.DONE
      ? (
        <>
          <IconBtn name="play" label={t('common.play')} className="primary" onClick={onPlay} />
          <a className="icon-btn muted" href={downloadFileUrl(item.id, true)} download
             title={t('downloads.saveToDevice')} aria-label={t('downloads.saveToDevice')}>
            <Icon name="save" />
          </a>
        </>
      )
      : item.status === DownloadStatus.QUEUED || item.status === DownloadStatus.RUNNING
        ? <IconBtn name="pause" label={t('common.pause')} onClick={act(() => api.pauseDownload(item.id))} />
        : item.status === DownloadStatus.PAUSED
          ? <IconBtn name="play" label={t('common.resume')} className="primary" onClick={act(() => api.resumeDownload(item.id))} />
          : <IconBtn name="refresh" label={t('common.retry')} onClick={act(() => api.retryDownload(item.id))} />;

  const showBar =
    (item.status === DownloadStatus.RUNNING || item.status === DownloadStatus.PAUSED) && item.totalBytes > 0;

  return (
    <div className="card download-card">
      <div className="head">
        <div className="body">
          <div className="title truncate">{item.title}</div>
          <div className={`status truncate${statusClass}`}>{statusText}</div>
        </div>
        {action}
        <IconBtn name="del" label={t('common.delete')} className="muted" onClick={act(() => api.deleteDownload(item.id))} />
      </div>
      {showBar && (
        <div className="progress-track">
          <div className="progress-fill"
               style={{ width: `${Math.min(100, (item.downloadedBytes * 100) / item.totalBytes)}%` }} />
        </div>
      )}
    </div>
  );
}
