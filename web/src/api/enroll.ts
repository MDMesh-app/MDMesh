import { apiClient } from './client';

// Mint an enrollment token (see server agent v1 contract):
//   POST /rest/private/agent/v1/token  ->  { token, ... }
// The token is embedded into the device's QR provisioning bundle. The exact
// shape is not strongly typed server-side here, so we read defensively.

export interface EnrollTokenResponse {
  token?: string;
  expiresAt?: number;
  [key: string]: unknown;
}

export async function mintEnrollToken(): Promise<EnrollTokenResponse> {
  return apiClient.post<EnrollTokenResponse>('/private/agent/v1/token', {});
}
