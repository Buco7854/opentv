import { ApiError, browserApiHttp, post } from '../api/http';
import {
  AuthCapabilities, AuthFlow, CurrentUser, TotpEnrollment, WebAuthnOptions,
} from './types';

function isAuthFlow(value: unknown): value is AuthFlow {
  if (typeof value !== 'object' || value === null) return false;
  const flow = value as Partial<AuthFlow>;
  return typeof flow.status === 'string' && (
    flow.status === 'AUTHENTICATED'
    || flow.status === 'MFA_REQUIRED'
    || flow.status === 'ENROLLMENT_REQUIRED'
  );
}

const PUBLIC_AUTH = { broadcastAuthFailure: false } as const;

async function flow(path: string, body: unknown, protectedRequest = false): Promise<AuthFlow> {
  try {
    return await browserApiHttp.json<AuthFlow>(
      path,
      post(body),
      protectedRequest ? undefined : PUBLIC_AUTH,
    );
  } catch (error) {
    // A challenge is a successful first authentication step expressed as 409.
    if (error instanceof ApiError && error.status === 409 && isAuthFlow(error.body)) {
      return error.body;
    }
    throw error;
  }
}

const completeWebAuthn = (
  path: string,
  challenge: string,
  credential: string,
  label?: string,
) => flow(path, { challenge, credential, ...(label ? { label } : {}) });

export const authApi = {
  capabilities: () => browserApiHttp.json<AuthCapabilities>(
    '/auth/capabilities', undefined, PUBLIC_AUTH,
  ),
  me: () => browserApiHttp.json<CurrentUser>('/auth/me', undefined, PUBLIC_AUTH),
  password: (username: string, password: string) =>
    flow('/auth/password', { username, password }),
  bootstrap: (token: string, username: string, password: string, displayName: string) =>
    flow('/auth/bootstrap', {
      token, username, password, displayName,
    }),
  activate: (token: string, password: string) =>
    flow('/auth/activate', { token, password }),
  startTotpEnrollment: (challenge: string) =>
    browserApiHttp.json<TotpEnrollment>(
      '/auth/totp/enroll/start', post({ challenge }), PUBLIC_AUTH,
    ),
  completeTotpEnrollment: (challenge: string, code: string) =>
    flow('/auth/totp/enroll/complete', { challenge, code }),
  completeTotp: (challenge: string, code: string) =>
    flow('/auth/totp', { challenge, code }),
  completeRecovery: (challenge: string, code: string) =>
    flow('/auth/recovery', { challenge, code }),
  registrationOptions: (challenge: string) =>
    browserApiHttp.json<WebAuthnOptions>(
      '/auth/webauthn/register/options', post({ challenge }), PUBLIC_AUTH,
    ),
  completeRegistration: (challenge: string, credential: string, label?: string) =>
    completeWebAuthn('/auth/webauthn/register/complete', challenge, credential, label),
  authenticationOptions: (challenge: string) =>
    browserApiHttp.json<WebAuthnOptions>(
      '/auth/webauthn/authenticate/options', post({ challenge }), PUBLIC_AUTH,
    ),
  completeAuthentication: (challenge: string, credential: string) =>
    completeWebAuthn('/auth/webauthn/authenticate/complete', challenge, credential),
  logout: (all = false) =>
    browserApiHttp.json<null>('/auth/logout', post({ all })),
  additionalRegistrationOptions: () =>
    browserApiHttp.json<WebAuthnOptions>('/auth/webauthn/add/options', { method: 'POST' }),
  completeAdditionalRegistration: (challenge: string, credential: string, label: string) =>
    flow('/auth/webauthn/add/complete', { challenge, credential, label }, true),
  regenerateRecoveryCodes: () =>
    flow('/auth/recovery/regenerate', {}, true),
  changePassword: (password: string) =>
    flow('/auth/password/change', { password }, true),
};
