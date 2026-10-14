import { apiClient } from './client';

export interface ConfigStatus {
  configurationId?: number | null;
  currentRevision?: string | null;
  appliedRevision?: string | null;
  appliedAt?: number | null;
  inSync: boolean;
  supported: boolean;
  lastCommand?: { id: number; status: string; detail?: string | null; createdAt?: number; completedAt?: number } | null;
}

export interface ConfigSyncSummary {
  configurationId: number; total: number; inSync: number; outOfSync: number; unsupported: number; neverSeen: number;
  /** Of inSync: devices whose latest config.apply deferred some settings (absent on older servers). */
  deferred?: number;
}

/** Shape of `lastCommand.detail` for config.apply (proto/payloads/config-apply-result.schema.json). */
export interface ConfigOutcomes { revision?: string; outcomes: Record<string, string> }

export function getConfigStatus(deviceId: number | string): Promise<ConfigStatus | null> {
  return apiClient.get<ConfigStatus | null>(`/private/agent/v1/devices/${deviceId}/configStatus`);
}

export function getSyncSummary(): Promise<ConfigSyncSummary[]> {
  return apiClient.get<ConfigSyncSummary[]>('/private/agent/v1/configurations/syncSummary');
}

export function parseOutcomes(detail: string | null | undefined): ConfigOutcomes | null {
  if (!detail) return null;
  try {
    const v = JSON.parse(detail) as Partial<ConfigOutcomes>;
    return v && typeof v === 'object' && v.outcomes && typeof v.outcomes === 'object' ? { revision: v.revision, outcomes: v.outcomes } : null;
  } catch { return null; }
}

/** Applied revision an agent (0.3.4+) reports after turning Wi-Fi back on by itself (Wi-Fi radio self-heal). */
export const DEVICE_RESET_REVISION = '0'.repeat(64);
export const DEVICE_RESET_LABEL = 'Reset by device (Wi-Fi turned back on)';
/** In sync, but the agent skipped some settings on purpose (e.g. Wi-Fi radio Off while Wi-Fi is the only network). */
export const SOME_DEFERRED_LABEL = 'In sync (some settings deferred)';

/** One-line verdict for badges. Pure so it can be unit-tested once Vitest lands (Phase 6.1). */
export function summarizeStatus(s: ConfigStatus | null): { tone: 'ok' | 'warn' | 'alert' | 'idle'; label: string } {
  if (!s || s.configurationId == null) return { tone: 'idle', label: 'No configuration' };
  if (!s.supported) return { tone: 'warn', label: 'Agent too old' };
  if (s.inSync) {
    const outcomes = parseOutcomes(s.lastCommand?.detail)?.outcomes ?? {};
    const deferred = Object.values(outcomes).some((o) => o.startsWith('deferred'));
    return deferred ? { tone: 'warn', label: SOME_DEFERRED_LABEL } : { tone: 'ok', label: 'In sync' };
  }
  const st = s.lastCommand?.status;
  if (st === 'pending' || st === 'delivered') return { tone: 'warn', label: 'Applying…' };
  if (st === 'failed') return { tone: 'alert', label: 'Apply failed' };
  if (!s.appliedRevision) return { tone: 'idle', label: 'Not reported yet' };
  if (s.appliedRevision === DEVICE_RESET_REVISION) return { tone: 'warn', label: DEVICE_RESET_LABEL };
  return { tone: 'warn', label: 'Out of sync' };
}
