import { apiClient } from './client';

// Mint an enrollment token (see server agent v1 contract):
//   POST /rest/private/agent/v1/token  ->  { token, ... }
// The token is embedded into the device's QR provisioning bundle. The exact
// shape is not strongly typed server-side here, so we read defensively.

export interface EnrollTokenResponse {
  token?: string;
  expiresAt?: number;
  /** How many devices the token enrolls; absent from a server that predates it, whose tokens are single-use. */
  maxUses?: number;
  [key: string]: unknown;
}

/** How many devices a token enrolls and for how long it is valid; omitted: one device, for 24 hours. */
export interface EnrollTokenLimits {
  maxUses?: number;
  validHours?: number;
}

export async function mintEnrollToken(
  configurationId?: number,
  limits: EnrollTokenLimits = {},
): Promise<EnrollTokenResponse> {
  // configurationId binds the enrolled device to that configuration (server validates ownership);
  // omitted -> the server's settings default applies.
  return apiClient.post<EnrollTokenResponse>('/private/agent/v1/token', {
    ...(configurationId ? { configurationId } : {}),
    ...(limits.maxUses ? { maxUses: limits.maxUses } : {}),
    ...(limits.validHours ? { validHours: limits.validHours } : {}),
  });
}

/**
 * Stop every enrollment code of this account that still works, those for a batch already printed or shared
 * included: POST /rest/private/agent/v1/token/revoke-all -> { revoked }. Devices already enrolled are not affected.
 * A server that predates it answers 404.
 */
export async function revokeAllEnrollTokens(): Promise<{ revoked?: number }> {
  return (await apiClient.post<{ revoked?: number } | undefined>('/private/agent/v1/token/revoke-all', {})) ?? {};
}
