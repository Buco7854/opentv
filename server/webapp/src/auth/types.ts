export type UserRole = 'USER' | 'ADMIN';
export type AuthStatus =
  | 'AUTHENTICATED'
  | 'MFA_REQUIRED'
  | 'ENROLLMENT_REQUIRED';

export interface AuthCapabilities {
  passwordEnabled: boolean;
  oidcEnabled: boolean;
  bootstrapRequired: boolean;
  webAuthnRpId: string;
  oidcStartUrl: string | null;
}

export interface CurrentUser {
  id: string;
  username: string;
  displayName: string;
  role: UserRole;
  authMethod: string;
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

