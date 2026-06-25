import { apiClient } from './client';

export interface DeviceEvent {
  id: number;
  type: string;
  ts: number;
  detail?: string | null;
}

export async function getEvents(deviceId: number | string, since = 0): Promise<DeviceEvent[]> {
  return apiClient.get<DeviceEvent[]>(`/private/agent/v1/devices/${deviceId}/events?since=${since}`);
}
