// One place where a failure becomes something a person can read, and one place where it
// reaches the screen.
//
// Which surface to use:
//  - A form submission explains itself next to the form: `ErrorNotice` / `errorMessage`.
//  - Everything else - background loads, list actions, saves, retries - calls
//    `reportError`, which raises the error toast. No screen may swallow a failure.

import { ApiError } from './api/http';
import { toast } from './components/Primitives';
import { MessageKey, t } from './i18n';

/** A prompt the user closed (WebAuthn, a file picker) or a request the app abandoned. */
const CANCELLED = 'cancelled';

/**
 * Server error codes (`ApiErrorDto.code`) and transport codes, mapped to copy. Codes no
 * user can act on differently fall through to the generic message on purpose.
 */
const CODE_TEXT: Record<string, MessageKey> = {
  [CANCELLED]: 'error.cancelled',
  // api/http.ts transport failures.
  network: 'common.networkError',
  timeout: 'common.requestTimeout',
  unexpected_response: 'common.unexpectedResponse',
  // Server contracts.
  unauthenticated: 'error.unauthenticated',
  forbidden: 'error.forbidden',
  auth_rate_limited: 'error.rateLimited',
  invalid_credentials: 'error.invalidCredentials',
  challenge_invalid: 'error.challengeInvalid',
  not_found: 'error.notFound',
  invalid_request: 'error.invalidRequest',
  request_too_large: 'error.tooLarge',
  internal_error: 'error.server',
  provider_unreachable: 'error.providerUnreachable',
  provider_login_rejected: 'error.providerRejected',
  playback_revoked: 'error.playbackRevoked',
};

/**
 * The code a failure is identified by. A dismissed browser prompt and an abandoned request
 * are folded into one `cancelled` code so callers can override or ignore both at once.
 */
function errorCode(error: unknown): string | null {
  if (error instanceof DOMException && error.name === 'NotAllowedError') return CANCELLED;
  if (error instanceof ApiError) return error.code === 'aborted' ? CANCELLED : error.code;
  return null;
}

/** Nothing failed: the user (or the app) called it off. */
export const isCancellation = (error: unknown) => errorCode(error) === CANCELLED;

/**
 * Copy overrides, keyed by error code, for a screen where a code means something sharper.
 * The reserved key [GENERIC] replaces the last-resort line - use it when the screen knows
 * which action failed and the raw cause would not say.
 */
export type ErrorOverrides = Partial<Record<string, () => string>>;

export const GENERIC = '*';

/**
 * The message to show for a failure.
 *
 * A mapped code wins over the server's own message: the mapping is localized and written
 * for the person reading it. An unmapped `ApiError` still carries a server message worth
 * showing, and anything else falls back to the generic line rather than surfacing
 * `[object Object]` or a stack.
 */
export function errorMessage(error: unknown, overrides: ErrorOverrides = {}): string {
  const code = errorCode(error);
  if (code) {
    const override = overrides[code];
    if (override) return override();
    const mapped = CODE_TEXT[code];
    if (mapped) return t(mapped);
  }
  if (error instanceof Error && error.message) return error.message;
  return (overrides[GENERIC] ?? (() => t('error.generic')))();
}

/**
 * Reports a failure on the toast surface and returns the message, so a caller that also
 * keeps inline state does not have to format it twice.
 *
 * A cancellation is reported to nobody: the user already knows they closed the prompt.
 */
export function reportError(error: unknown, overrides: ErrorOverrides = {}): string {
  const message = errorMessage(error, overrides);
  if (!isCancellation(error)) toast(message, { tone: 'error' });
  return message;
}

/**
 * Reports a failure inside a sentence of the caller's own - `(message) =>
 * t('browse.refreshFailed', { message })` - when naming the action that failed tells the
 * user more than the bare cause does.
 */
export function reportErrorAs(
  describe: (message: string) => string,
  error: unknown,
  overrides: ErrorOverrides = {},
) {
  if (isCancellation(error)) return;
  toast(describe(errorMessage(error, overrides)), { tone: 'error' });
}

/** Confirms something the user did. Same surface, opposite tone. */
export function reportSuccess(message: string) {
  toast(message, { tone: 'success' });
}
