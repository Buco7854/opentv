import { useState } from 'react';
import { browserAccessToken } from '../api/http';
import { RequireAuth } from './AuthProvider';
import { capturePendingDeviceLink } from './fragment';
import { LinkApprovalScreen } from './LinkApproval';

/**
 * Public landing boundary for `/link#t=…`.
 *
 * It captures and scrubs the fragment before RequireAuth sends an unauthenticated visitor
 * through the ordinary login screen. After password, MFA, passkey, or OIDC completes, the
 * same tab returns here and the authenticated approval route performs the claim.
 */
export function LinkLandingScreen() {
  const [pending] = useState(() => (
    capturePendingDeviceLink(browserAccessToken() === null)
  ));
  return (
    <RequireAuth>
      <LinkApprovalScreen pending={pending} />
    </RequireAuth>
  );
}
