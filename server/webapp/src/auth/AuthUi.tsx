import { useId, useState } from 'react';
import { Icon, IconName } from '../components/Icons';
import {
  ErrorOverrides, errorMessage as sharedErrorMessage, reportError as sharedReportError,
} from '../errors';
import { authText as tx } from './copy';

/**
 * Codes that mean something sharper inside an authentication flow than they do anywhere
 * else. Everything not listed here resolves through the shared mapping in src/errors.ts.
 */
const AUTH_OVERRIDES: ErrorOverrides = {
  cancelled: () => tx('cancelled'),
  challenge_invalid: () => tx('challengeInvalid'),
  auth_rate_limited: () => tx('rateLimited'),
  invalid_credentials: () => tx('invalidCredentials'),
  forbidden: () => tx('recentAuthRequired'),
  totp_exists: () => tx('authenticatorExists'),
  csrf_rejected: () => tx('csrfRejected'),
  unauthenticated: () => tx('sessionEnded'),
  last_factor: () => tx('passkeyLastFactor'),
  webauthn_unavailable: () => tx('passkeyAddressUnsupported'),
  password_required_for_mfa: () => tx('noPasswordAccount'),
};

export const errorMessage = (error: unknown, overrides: ErrorOverrides = {}) =>
  sharedErrorMessage(error, { ...AUTH_OVERRIDES, ...overrides });

/** Raises the error toast for a failure with no form to sit next to. */
export const reportAuthError = (error: unknown, overrides: ErrorOverrides = {}) =>
  sharedReportError(error, { ...AUTH_OVERRIDES, ...overrides });

export function ErrorNotice({ message }: { message: string | null }) {
  if (!message) return null;
  return (
    <p className="auth-error" role="alert">
      <Icon name="alert" />
      <span>{message}</span>
    </p>
  );
}

export function ChoiceRow({ icon, title, subtitle, onClick }: {
  icon: IconName;
  title: string;
  subtitle: string;
  onClick: () => void;
}) {
  return (
    <button type="button" className="card" onClick={onClick}>
      <div className="row">
        <span className="logo-box"><Icon name={icon} /></span>
        <div className="body">
          <div className="title">{title}</div>
          <div className="sub">{subtitle}</div>
        </div>
        <Icon name="chevron" className="chev" />
      </div>
    </button>
  );
}

export function OtpField({ label, value, onChange, autoFocus, length = 6 }: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  autoFocus?: boolean;
  length?: number;
}) {
  const id = useId();
  const [focused, setFocused] = useState(false);
  return (
    <div className="otp">
      <input
        id={id}
        type="text"
        inputMode="numeric"
        pattern="[0-9]*"
        autoComplete="one-time-code"
        aria-label={label}
        maxLength={length}
        value={value}
        autoFocus={autoFocus}
        onFocus={() => setFocused(true)}
        onBlur={() => setFocused(false)}
        onChange={(event) => onChange(event.target.value.replace(/\D/g, '').slice(0, length))}
      />
      <div className="cells" aria-hidden>
        {Array.from({ length }, (_, index) => {
          const active = focused && index === Math.min(value.length, length - 1);
          return (
            <div key={index} className={`cell${active ? ' on' : ''}`}>
              {value[index] ?? (active && index === value.length ? <span className="caret" /> : '')}
            </div>
          );
        })}
      </div>
    </div>
  );
}
