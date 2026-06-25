// Staged agent-APK rollout (canary → fleet). Talks to /private/agent/v1/rollout on the Java server
// (session-cookie auth via apiClient). Progress counts are derived server-side from each device's
// reported agentVersion + the app.silentInstall capability gate.
import { apiClient } from './client';

export interface RolloutCounts {
  total: number;
  updated: number;
  pending: number;
  outstanding: number;
  ineligible: number;
}

export interface RolloutProgress {
  stage: string;
  targetVersion: string;
  canary: RolloutCounts;
  fleet: RolloutCounts | null; // null until the rollout is promoted to fleet
}

export interface ActiveRollout {
  id: number;
  targetVersion: string;
  packageName: string;
  apkVersionCode: number | null;
  stage: 'canary' | 'fleet' | 'done' | 'cancelled';
  createdAt: number;
  updatedAt: number;
  progress: RolloutProgress;
}

export interface CreateRolloutRequest {
  targetVersion: string;
  packageName: string;
  apkVersionCode: number;
  apkSha256: string;
  canaryDeviceNumbers: string[];
  // apkUrl is built server-side from the deployment's base URL — not sent by the client.
}

const BASE = '/private/agent/v1/rollout';

/** The current canary/fleet rollout for this customer, or null if none is active. */
export async function getActiveRollout(): Promise<ActiveRollout | null> {
  return apiClient.get<ActiveRollout | null>(`${BASE}/active`);
}

/** Start a canary-stage rollout. Throws ApiError (e.g. a rollout is already active). */
export async function createRollout(req: CreateRolloutRequest): Promise<ActiveRollout> {
  return apiClient.post<ActiveRollout>(BASE, req);
}

/** Advance a canary rollout to the rest of the fleet. */
export async function promoteRollout(id: number): Promise<ActiveRollout> {
  return apiClient.post<ActiveRollout>(`${BASE}/${id}/promote`);
}

/** Stop offering the update (in-flight installs run their course). */
export async function cancelRollout(id: number): Promise<void> {
  await apiClient.post<void>(`${BASE}/${id}/cancel`);
}
