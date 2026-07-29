import { ApiError, browserApiHttp, post } from '../api/http';
import {
  AuthCapabilities, AuthFlow, CurrentUser, DeviceLinkRequest, DeviceLinkStart,
  DeviceLinkStatus, TotpEnrollment, TotpStatus, WebAuthnCredential, WebAuthnOptions,
} from './types';

function isAuthFlow(value: unknown): value is AuthFlow {
  if (typeof value !== 'object' || value === null) return false;
  const flow = value as Partial<AuthFlow>;
  return typeof flow.status === 'string' && (
    flow.status === 'AUTHENTICATED'
    || flow.status === 'MFA_REQUIRED'
    || flow.status === 'ENROLLMENT_REQUIRED'
    || flow.status === 'PENDING_APPROVAL'
  );
}

const PUBLIC_AUTH = { broadcastAuthFailure: false } as const;

export interface LinkTarget { linkToken: string }

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
  passkeyOptions: () =>
    browserApiHttp.json<WebAuthnOptions>(
      '/auth/webauthn/login/options', post({}), PUBLIC_AUTH,
    ),
  completePasskeyLogin: (challenge: string, credential: string) =>
    flow('/auth/webauthn/login/complete', { challenge, credential }),
  linkStart: (deviceName?: string) =>
    browserApiHttp.json<DeviceLinkStart>(
      '/auth/link/start', post(deviceName ? { deviceName } : {}), PUBLIC_AUTH,
    ),
  linkPoll: (pollToken: string) =>
    browserApiHttp.json<DeviceLinkStatus>(
      '/auth/link/poll', post({ pollToken }), PUBLIC_AUTH,
    ),
  linkLookup: (target: LinkTarget) =>
    browserApiHttp.json<DeviceLinkRequest>('/auth/link/lookup', post(target)),
  linkApprove: (target: LinkTarget) =>
    browserApiHttp.json<null>('/auth/link/approve', post(target)),
  linkDeny: (target: LinkTarget) =>
    browserApiHttp.json<null>('/auth/link/deny', post(target)),
  totpStatus: () => browserApiHttp.json<TotpStatus>('/auth/totp/status'),
  startTotpAdd: () =>
    browserApiHttp.json<TotpEnrollment>('/auth/totp/add/start', post({})),
  completeTotpAdd: (challenge: string, code: string) =>
    flow('/auth/totp/add/complete', { challenge, code }, true),
  deleteTotp: () => flow('/auth/totp/delete', {}, true),
  webAuthnCredentials: () =>
    browserApiHttp.json<WebAuthnCredential[]>('/auth/webauthn/credentials'),
  deleteWebAuthnCredential: (id: string) =>
    flow('/auth/webauthn/credentials/delete', { id }, true),
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
