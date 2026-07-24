// Per-state download button: idle downloads, running pauses (right-click
// deletes), paused resumes, done deletes. Mirrors DownloadStateIcon.kt.

import { useState } from 'react';
import { api, Download, DownloadStatus } from '../api';
import { t } from '../i18n';
import { Icon } from './Icons';
import { ConfirmDialog, snackbar } from './Primitives';

function Ring({ fraction }: { fraction: number | null }) {
  const c = 2 * Math.PI * 10;
  return (
    <svg viewBox="0 0 24 24" className={`icon dl-ring${fraction == null ? ' spin' : ''}`}>
      <circle className="track" cx="12" cy="12" r="10" />
      <circle
        className="bar" cx="12" cy="12" r="10"
        strokeDasharray={c} strokeDashoffset={c * (1 - (fraction ?? 0.25))}
      />
    </svg>
  );
}

export function DownloadStateIcon({ state, onDownload, onChanged }: {
  state: Download | undefined;
  onDownload: () => Promise<{ message: string }>;
  onChanged: () => void;
}) {
  const [confirmDelete, setConfirmDelete] = useState(false);

  const act = (fn: () => Promise<unknown>) => async (e: React.MouseEvent) => {
    e.stopPropagation();
    try { await fn(); } catch (err) { snackbar((err as Error).message); }
    onChanged();
  };
  const openDelete = (e: React.MouseEvent) => { e.preventDefault(); e.stopPropagation(); setConfirmDelete(true); };

  let button;
  const displayedStatus = state && !state.active &&
    (state.status === DownloadStatus.RUNNING || state.status === DownloadStatus.QUEUED)
    ? DownloadStatus.PAUSED : state?.status;
  switch (displayedStatus) {
    case DownloadStatus.RUNNING:
    case DownloadStatus.QUEUED: {
      // Queued means the provider's connections are busy: it waits (spinner + a hint saying so)
      // rather than looking like a stuck active download.
      const queued = displayedStatus === DownloadStatus.QUEUED;
      const download = state!;
      const fraction = !queued && download.totalBytes > 0
        ? Math.min(1, download.downloadedBytes / download.totalBytes) : null;
      button = (
        <button className="icon-btn relative" aria-label={t('downloads.pauseAria')}
                title={t(queued ? 'downloads.queuedHint' : 'downloads.pauseHint')}
                onClick={act(() => api.pauseDownload(download.id))} onContextMenu={openDelete}>
          <Ring fraction={fraction} />
          <Icon name="pause" className="dl-pause-glyph" />
        </button>
      );
      break;
    }
    case DownloadStatus.PAUSED:
      button = (
        <button className="icon-btn primary" aria-label={t('downloads.resumeAria')}
                title={t('downloads.resumeHint')}
                onClick={act(() => api.resumeDownload(state!.id))} onContextMenu={openDelete}>
          <Icon name="play" />
        </button>
      );
      break;
    case DownloadStatus.DONE:
      button = (
        <button className="icon-btn primary" aria-label={t('downloads.downloaded')} title={t('downloads.downloadedHint')}
                onClick={openDelete}>
          <Icon name="downloadDone" />
        </button>
      );
      break;
    default:
      button = (
        <button className="icon-btn primary" aria-label={t('downloads.download')} title={t('downloads.download')}
                onClick={act(async () => snackbar((await onDownload()).message))}>
          <Icon name="download" />
        </button>
      );
  }

  return (
    <>
      {button}
      {confirmDelete && state && (
        <ConfirmDialog
          title={t('downloads.removeTitle')}
          message={state.status === DownloadStatus.DONE
            ? t('downloads.deleteFileMsg', { title: state.title })
            : t('downloads.deletePartialMsg', { title: state.title })}
          confirmLabel={t('common.delete')}
          onConfirm={async () => { await api.deleteDownload(state.id).catch(() => {}); onChanged(); }}
          onDismiss={() => setConfirmDelete(false)}
        />
      )}
    </>
  );
}
