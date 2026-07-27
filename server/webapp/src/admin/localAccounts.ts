import type { AuthCapabilities } from '../auth/types';

/** Local account creation and credential resets both depend on password authentication. */
export const canProvisionLocalAccounts = (
  capabilities: Pick<AuthCapabilities, 'passwordEnabled'> | null,
) => capabilities?.passwordEnabled === true;
