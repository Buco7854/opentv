// One card per download. Web extra: "save" streams the finished file to the
// browser's own downloads. Mirrors DownloadsScreen.kt.

import { useState } from 'react';
import { api, Download, downloadFileUrl, DownloadStatus } from '../api';
import { EmptyState, LoadFailed } from '../components/Common';
import { Icon } from '../components/Icons';
import { ConfirmDialog, IconBtn, ScreenHeader, Spinner } from '../components/Primitives';
import { reportError } from '../errors';
import { useDownloads } from '../hooks';
import { t } from '../i18n';
import { formatBytes } from '../lib/format';
import { usePlayer } from '../player/PlayerNavigation';
import { WatchProgressBar } from '../components/WatchProgress';

export function DownloadsScreen() {
  const { playDownload } = usePlayer();
  const downloads = useDownloads();

  return (
    <>
      <ScreenHeader title={t('downloads.title')} />
      {!downloads.loaded ? (
        downloads.error != null
          ? <LoadFailed message={downloads.error} onRetry={downloads.refresh} />
          : <Spinner />
      ) : downloads.list.length === 0 ? (
        <EmptyState title={t('downloads.emptyTitle')} subtitle={t('downloads.emptySub')} />
      ) : (
        <div className="list gap-2.5">
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
  const [confirmDelete, setConfirmDelete] = useState(false);
  const act = (fn: () => Promise<unknown>) => async () => {
    await fn().catch(reportError);
    onChanged();
  };

  const progressText = item.totalBytes > 0
    ? t('downloads.ofBytes', { done: formatBytes(item.downloadedBytes), total: formatBytes(item.totalBytes) })
    : formatBytes(item.downloadedBytes);
  const displayedStatus = !item.active &&
    (item.status === DownloadStatus.QUEUED || item.status === DownloadStatus.RUNNING)
    ? DownloadStatus.PAUSED : item.status;
  const statusText =
    displayedStatus === DownloadStatus.QUEUED ? t('downloads.queued')
    : displayedStatus === DownloadStatus.RUNNING ? progressText
    : displayedStatus === DownloadStatus.PAUSED ? `${t('downloads.paused')} · ${progressText}`
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
          <a className="icon-btn muted" href={downloadFileUrl(item.id, item.fileToken!, true)} download
             title={t('downloads.saveToDevice')} aria-label={t('downloads.saveToDevice')}>
            <Icon name="save" />
          </a>
        </>
      )
      : displayedStatus === DownloadStatus.QUEUED || displayedStatus === DownloadStatus.RUNNING
        ? <IconBtn name="pause" label={t('common.pause')} onClick={act(() => api.pauseDownload(item.id))} />
        : displayedStatus === DownloadStatus.PAUSED
          ? <IconBtn name="play" label={t('common.resume')} className="primary" onClick={act(() => api.resumeDownload(item.id))} />
          : <IconBtn name="refresh" label={t('common.retry')} onClick={act(() => api.retryDownload(item.id))} />;

  const showBar =
    (displayedStatus === DownloadStatus.RUNNING || displayedStatus === DownloadStatus.PAUSED) && item.totalBytes > 0;

  return (
    <div className="card download-card">
      <div className="head">
        <div className="body">
          <div className="title truncate">{item.title}</div>
          <div className={`status truncate${statusClass}`}>{statusText}</div>
        </div>
        {action}
        <IconBtn name="del" label={t('common.delete')} className="muted" onClick={() => setConfirmDelete(true)} />
      </div>
      {showBar && (
        <WatchProgressBar fraction={item.downloadedBytes / item.totalBytes} />
      )}
      {confirmDelete && (
        <ConfirmDialog
          title={t('downloads.removeTitle')}
          message={item.status === DownloadStatus.DONE
            ? t('downloads.deleteFileMsg', { title: item.title })
            : t('downloads.deletePartialMsg', { title: item.title })}
          confirmLabel={t('common.delete')}
          onConfirm={act(() => api.deleteDownload(item.id))}
          onDismiss={() => setConfirmDelete(false)}
        />
      )}
    </div>
  );
}
