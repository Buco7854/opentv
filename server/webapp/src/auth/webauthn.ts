import { WebAuthnOptions } from './types';
import { authText as tx } from './copy';

const decodeBase64Url = (value: string): ArrayBuffer => {
  const normalized = value.replace(/-/g, '+').replace(/_/g, '/');
  const padding = '='.repeat((4 - (normalized.length % 4)) % 4);
  const binary = atob(normalized + padding);
  return Uint8Array.from(binary, (character) => character.charCodeAt(0)).buffer;
};

const encodeBase64Url = (value: ArrayBuffer): string => {
  const bytes = new Uint8Array(value);
  let binary = '';
  bytes.forEach((byte) => { binary += String.fromCharCode(byte); });
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
};

const descriptors = (
  values: WebAuthnOptions['allowCredentials'],
): PublicKeyCredentialDescriptor[] => values.map((value) => ({
  type: 'public-key',
  id: decodeBase64Url(value.id),
  transports: value.transports,
}));

function ensureAvailable() {
  if (!window.isSecureContext || !('PublicKeyCredential' in window)) {
    throw new Error(tx('securityKeyUnavailable'));
  }
}

export async function createCredential(options: WebAuthnOptions): Promise<string> {
  ensureAvailable();
  if (!options.rp || !options.user) throw new Error(tx('securityKeyOptionsInvalid'));
  const credential = await navigator.credentials.create({
    publicKey: {
      challenge: decodeBase64Url(options.challenge),
      rp: options.rp,
      user: {
        ...options.user,
        id: decodeBase64Url(options.user.id),
      },
      pubKeyCredParams: options.pubKeyCredParams,
      excludeCredentials: descriptors(options.excludeCredentials),
      authenticatorSelection: options.authenticatorSelection ?? undefined,
      timeout: options.timeout,
      attestation: options.attestation ?? 'none',
    },
  });
  if (!(credential instanceof PublicKeyCredential)
      || !(credential.response instanceof AuthenticatorAttestationResponse)) {
    throw new Error(tx('securityKeyRegistrationCancelled'));
  }
  const response = credential.response;
  return JSON.stringify({
    id: credential.id,
    rawId: encodeBase64Url(credential.rawId),
    type: credential.type,
    authenticatorAttachment: credential.authenticatorAttachment,
    clientExtensionResults: credential.getClientExtensionResults(),
    response: {
      clientDataJSON: encodeBase64Url(response.clientDataJSON),
      attestationObject: encodeBase64Url(response.attestationObject),
      transports: response.getTransports?.() ?? [],
    },
  });
}

export async function getCredential(options: WebAuthnOptions): Promise<string> {
  ensureAvailable();
  const credential = await navigator.credentials.get({
    publicKey: {
      challenge: decodeBase64Url(options.challenge),
      rpId: options.rpId ?? undefined,
      allowCredentials: descriptors(options.allowCredentials),
      timeout: options.timeout,
      userVerification: options.userVerification ?? 'discouraged',
    },
  });
  if (!(credential instanceof PublicKeyCredential)
      || !(credential.response instanceof AuthenticatorAssertionResponse)) {
    throw new Error(tx('securityKeyRequestCancelled'));
  }
  const response = credential.response;
  return JSON.stringify({
    id: credential.id,
    rawId: encodeBase64Url(credential.rawId),
    type: credential.type,
    authenticatorAttachment: credential.authenticatorAttachment,
    clientExtensionResults: credential.getClientExtensionResults(),
    response: {
      clientDataJSON: encodeBase64Url(response.clientDataJSON),
      authenticatorData: encodeBase64Url(response.authenticatorData),
      signature: encodeBase64Url(response.signature),
      userHandle: response.userHandle ? encodeBase64Url(response.userHandle) : null,
    },
  });
}
