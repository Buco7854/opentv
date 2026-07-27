export type UserRole = 'USER' | 'ADMIN';
export type AuthStatus =
  | 'AUTHENTICATED'
  | 'MFA_REQUIRED'
  | 'ENROLLMENT_REQUIRED'
  | 'PENDING_APPROVAL';

export interface AuthCapabilities {
  passwordEnabled: boolean;
  oidcEnabled: boolean;
  bootstrapRequired: boolean;
  webAuthnRpId: string;
  oidcStartUrl: string | null;
  passkeyLoginEnabled: boolean;
  deviceLinkEnabled: boolean;
}

export interface CurrentUser {
  id: string;
  username: string;
  displayName: string;
  role: UserRole;
  authMethod: string;
  /** Whether the account has a password credential - not how this session signed in. */
  hasPassword: boolean;
  authSessionId: string;
  clientKind: string;
  playlistIds: number[];
  csrfToken: string;
}

export interface AuthFlow {
  status: AuthStatus;
  code: string | null;
  challenge: string | null;
  methods: string[];
  expiresAtMs: number | null;
  user: CurrentUser | null;
  csrfToken: string | null;
  recoveryCodes: string[];
}

export interface DeviceLinkStart {
  pollToken: string;
  linkToken: string;
  verificationUriComplete: string;
  expiresAtMs: number;
  intervalMs: number;
}

export type DeviceLinkState = 'PENDING' | 'SCANNED' | 'APPROVED' | 'DENIED' | 'EXPIRED';

export interface DeviceLinkPreview {
  displayName: string;
  username: string;
}

export interface DeviceLinkStatus {
  status: DeviceLinkState;
  preview: DeviceLinkPreview | null;
  flow: AuthFlow | null;
  intervalMs: number;
  expiresAtMs: number;
}

export interface DeviceLinkRequest {
  deviceName: string | null;
  userAgent: string | null;
  ip: string | null;
  requestedAtMs: number;
  expiresAtMs: number;
}

export interface TotpStatus {
  enrolled: boolean;
  confirmedAtMs: number | null;
}

export interface WebAuthnCredential {
  id: string;
  label: string;
  createdAtMs: number;
  lastUsedAtMs: number | null;
  backedUp: boolean;
}

export interface TotpEnrollment {
  challenge: string;
  secret: string;
  uri: string;
  expiresAtMs: number;
}

export interface WebAuthnDescriptor {
  type: 'public-key';
  id: string;
  transports: AuthenticatorTransport[];
}

export interface WebAuthnOptions {
  challenge: string;
  rp: { id: string; name: string } | null;
  user: { id: string; name: string; displayName: string } | null;
  rpId: string | null;
  pubKeyCredParams: PublicKeyCredentialParameters[];
  excludeCredentials: WebAuthnDescriptor[];
  allowCredentials: WebAuthnDescriptor[];
  authenticatorSelection: AuthenticatorSelectionCriteria | null;
  timeout: number;
  attestation: AttestationConveyancePreference | null;
  userVerification: UserVerificationRequirement | null;
  serverChallenge: string;
}

