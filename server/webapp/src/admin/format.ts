import {
  ErrorOverrides, GENERIC, errorMessage as sharedErrorMessage, reportError as sharedReportError,
} from '../errors';
import { AdminPlaylist, AdminUser } from './api';
import { adminLocale, adminText as c } from './copy';
import type { AdminTextKey } from './copy';

const STATUS_TEXT: Record<AdminUser['status'], AdminTextKey> = {
  INVITED: 'invited',
  PENDING: 'pending',
  ACTIVE: 'active',
  DISABLED: 'disabled',
};

const AUTH_METHOD_TEXT: Record<string, AdminTextKey> = {
  PASSWORD: 'password',
  OIDC: 'oidc',
  TOTP: 'totp',
  WEBAUTHN: 'webauthn',
};

const PLAYLIST_MODE_TEXT: Record<AdminPlaylist['mode'], AdminTextKey> = {
  xtream: 'modeXtream',
  url: 'modeUrl',
  file: 'modeFile',
};

const DOWNLOAD_STATUS_TEXT: Record<string, AdminTextKey> = {
  QUEUED: 'downloadQueued',
  RUNNING: 'downloadRunning',
  DONE: 'downloadDone',
  FAILED: 'downloadFailed',
  CANCELLED: 'downloadCancelledStatus',
  PAUSED: 'downloadPaused',
};

export const dateTime = (value: number | null) => value == null
  ? c('never')
  : new Intl.DateTimeFormat(adminLocale(), { dateStyle: 'medium', timeStyle: 'short' }).format(value);

export const bytes = (value: number) => {
  if (value < 0) return '-';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  const unit = value === 0
    ? 0
    : Math.min(Math.floor(Math.log(value) / Math.log(1024)), units.length - 1);
  return `${(value / 1024 ** unit).toFixed(unit === 0 ? 0 : 1)} ${units[unit]}`;
};

/**
 * Administration says it in its own words: these codes all arrive while managing someone
 * else's account, where "you are not allowed" and "that user is gone" need naming. The
 * shared mapping in src/errors.ts covers everything else.
 */
const ADMIN_TEXT: Record<string, AdminTextKey> = {
  invalid_request: 'errorRejected',
  last_admin: 'errorLastAdmin',
  username_taken: 'errorUsernameTaken',
  unknown_playlist: 'errorUnknownPlaylist',
  not_found: 'errorNotFound',
  forbidden: 'errorForbidden',
  unauthenticated: 'errorUnauthenticated',
  auth_rate_limited: 'errorRateLimited',
  request_too_large: 'errorTooLarge',
  internal_error: 'errorServer',
  local_account_provisioning_disabled: 'errorLocalProvisioningDisabled',
  password_auth_disabled: 'errorPasswordAuthDisabled',
  user_status_not_settable: 'errorStatusNotSettable',
};

// A failure with nothing readable in it is still a failed administrative action, and
// saying that beats saying nothing.
const ADMIN_OVERRIDES: ErrorOverrides = {
  ...Object.fromEntries(
    Object.entries(ADMIN_TEXT).map(([code, key]) => [code, () => c(key)]),
  ),
  [GENERIC]: () => c('operationFailed'),
};

export const errorMessage = (error: unknown) => sharedErrorMessage(error, ADMIN_OVERRIDES);

/** Raises the error toast for a failed administrative action. */
export const reportAdminError = (error: unknown) => sharedReportError(error, ADMIN_OVERRIDES);

export const initials = (user: { displayName: string; username: string }) =>
  (user.displayName || user.username || '?')
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => Array.from(part)[0])
    .join('');

export const roleLabel = (role: AdminUser['effectiveRole']) =>
  c(role === 'ADMIN' ? 'administrator' : 'user');
export const statusLabel = (status: AdminUser['status']) => c(STATUS_TEXT[status]);
export const statusTone = (status: AdminUser['status']) =>
  status === 'ACTIVE' ? ' good' : status === 'DISABLED' ? ' bad' : '';
export const authMethodLabel = (method: string) =>
  c(AUTH_METHOD_TEXT[method.toUpperCase()] ?? 'downloadUnknown');
const CLIENT_KIND_TEXT: Record<string, AdminTextKey> = {
  BROWSER: 'clientBrowser',
  LINKED_DEVICE: 'clientLinked',
  NATIVE: 'clientNative',
};

export const clientLabel = (kind: string) => {
  const key = CLIENT_KIND_TEXT[kind.toUpperCase()];
  return key ? c(key) : kind;
};
export const playlistModeLabel = (mode: AdminPlaylist['mode']) => c(PLAYLIST_MODE_TEXT[mode]);
export const downloadStatusLabel = (status: string) =>
  c(DOWNLOAD_STATUS_TEXT[status.toUpperCase()] ?? 'downloadUnknown');
