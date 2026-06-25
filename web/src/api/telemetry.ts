import { apiClient } from './client';

export interface TelemetrySnapshot {
  dynamic?: Record<string, unknown>;
  hardware?: Record<string, unknown>;
  identity?: Record<string, unknown>;
  security?: Record<string, unknown>;
}

export async function getTelemetry(deviceId: number | string): Promise<TelemetrySnapshot | null> {
  return apiClient.get<TelemetrySnapshot | null>(`/private/agent/v1/devices/${deviceId}/telemetry`);
}
