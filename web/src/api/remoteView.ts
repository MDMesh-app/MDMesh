import { apiClient } from './client';

export type RemoteViewState = 'requested' | 'awaiting_consent' | 'signaling' | 'stopped' | 'denied' | 'failed' | 'disconnected';

export interface RemoteViewSession {
  id: string;
  state: RemoteViewState;
  consentExpiresAt: number;
  expiresAt: number;
  endedAt?: number | null;
  endReason?: string | null;
}

export function requestRemoteView(deviceNumber: string): Promise<RemoteViewSession> {
  return apiClient.post<RemoteViewSession>(`/private/remote-view/devices/${encodeURIComponent(deviceNumber)}/sessions`);
}

export function getRemoteViewSession(id: string): Promise<RemoteViewSession> {
  return apiClient.get<RemoteViewSession>(`/private/remote-view/sessions/${encodeURIComponent(id)}`);
}

export function stopRemoteViewSession(id: string): Promise<void> {
  return apiClient.post<void>(`/private/remote-view/sessions/${encodeURIComponent(id)}/stop`);
}

/** Same-origin authenticated WebSocket: the browser supplies the console's session cookie. */
export function remoteViewSocketUrl(id: string, locationLike: Pick<Location, 'protocol' | 'host'> = window.location): string {
  const scheme = locationLike.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${scheme}//${locationLike.host}/agent/viewer/${encodeURIComponent(id)}`;
}
