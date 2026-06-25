import { apiClient } from './client';

// Device location breadcrumb trail — GET /private/agent/v1/devices/{n}/locations?since=
// Each fix is stored server-side from the agent's telemetry (see device_location table).

export interface LocationFix {
  id?: number;
  deviceNumber?: string;
  lat: number;
  lon: number;
  accuracy?: number;
  provider?: string;
  capturedAt: number;
  recordedAt?: number;
}

export async function listLocations(
  deviceId: number | string,
  since = 0,
): Promise<LocationFix[]> {
  return apiClient.get<LocationFix[]>(
    `/private/agent/v1/devices/${deviceId}/locations?since=${since}`,
  );
}
